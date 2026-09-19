package com.xiaohua.service.eval;

import com.xiaohua.model.ai.RetrieveOptions;
import com.xiaohua.model.eval.EvalCase;
import com.xiaohua.model.eval.EvalCaseResult;
import com.xiaohua.model.eval.EvalCaseScore;
import com.xiaohua.model.eval.EvalMetrics;
import com.xiaohua.model.eval.EvalSet;
import com.xiaohua.model.eval.EvalVariant;
import com.xiaohua.model.eval.RagEvalReport;
import com.xiaohua.model.eval.VariantReport;
import com.xiaohua.model.vo.RagEvalStatusVO;
import com.xiaohua.service.KnowledgeService;
import com.xiaohua.service.RagIndexService;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 离线检索评估：把「检索好不好」从主观印象变成可复现的数字。
 *
 * <p>流程就是四件套：<b>评估集</b>（查询 → 期望命中的文章）× <b>变体</b>（不同开关组合）
 * → 走真实检索链路 → 算 <b>指标</b> → 写 <b>报告</b>。</p>
 *
 * <p>三个刻意的设计：</p>
 * <ul>
 *   <li><b>走 {@code retrieveSegments} 而不是 {@code retrieve}</b>：绕开 Caffeine 缓存。
 *       评估要反复用同一批查询跑不同的参数组合，命中缓存会让后一个变体直接拿到
 *       前一个变体的结果，指标全错。</li>
 *   <li><b>strict = true</b>：多路召回的降级链是生产该有的行为，但评估必须看到真实失败 ——
 *       否则「DashScope 挂了」和「真的检索不到」在报告里长得一模一样。</li>
 *   <li><b>单线程、串行</b>：一次评估就是几百次外部 API 调用，并发会撞限流，
 *       而且串行才能让「第几个变体 / 第几条用例」的进度真实可信。</li>
 * </ul>
 *
 * <p>用的是<b>独立的线程池</b>，不复用知识库重建那个：一次评估要几分钟，
 * 混在一起会让评估期间的 {@code POST /rag/rebuild} 一直排队。</p>
 */
@Service
@Slf4j
public class RagEvalService {

    @Resource
    private KnowledgeService knowledgeService;

    @Resource
    private RagIndexService ragIndexService;

    @Resource
    private RagEvalSetLoader evalSetLoader;

    @Resource
    private RagEvalProperties properties;

    @Resource
    private EvalReportWriter reportWriter;

    /** 评估的单线程执行器（串行执行，避免并发打爆 DashScope 限流） */
    private final ExecutorService evalExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-eval");
        t.setDaemon(true);
        return t;
    });

    /** 评估状态（volatile，每次状态变化整体替换） */
    private volatile RagEvalStatusVO status = idleStatus();

    /** 最近一次的成功报告，供 {@code GET /rag/eval/report/latest} 快速返回 */
    private volatile RagEvalReport latestReport;

    /**
     * 提交一次评估。立即返回，实际评估在后台线程执行。
     *
     * @param variantIds 要跑的变体 id；为空则用配置里的 {@code rag.eval.default-variants}
     * @param caseIds    只跑指定的用例；为空则跑评估集里所有启用的用例
     * @return true 表示已提交；false 表示已有评估在进行中
     */
    public synchronized boolean triggerEval(List<String> variantIds, List<String> caseIds) {
        if ("RUNNING".equals(status.getState())) {
            return false;
        }
        // 变体 id 在这里就解析掉：写错了要立刻告诉调用方，而不是提交完在后台悄悄失败
        List<EvalVariant> variants = EvalVariant.parseAll(
                variantIds == null || variantIds.isEmpty() ? properties.getDefaultVariants() : variantIds);

        RagEvalStatusVO running = new RagEvalStatusVO();
        running.setState("RUNNING");
        running.setMessage("评估中");
        running.setStartTime(System.currentTimeMillis());
        running.setTotalVariants(variants.size());
        running.setDoneVariants(0);
        running.setDoneCases(0);
        this.status = running;

        evalExecutor.submit(() -> doEval(variants, caseIds));
        return true;
    }

    /**
     * 当前评估状态（供前端轮询）。
     */
    public RagEvalStatusVO getStatus() {
        return status;
    }

    /**
     * 最近一次评估报告。内存里没有就去报告目录找最新的一份（应用重启后也能读到）。
     */
    public RagEvalReport getLatestReport() {
        RagEvalReport cached = latestReport;
        return cached != null ? cached : reportWriter.loadLatest();
    }

    @PreDestroy
    public void shutdown() {
        evalExecutor.shutdown();
    }

    private void doEval(List<EvalVariant> variants, List<String> caseIds) {
        long start = System.currentTimeMillis();
        String runId = reportWriter.newRunId();
        try {
            EvalSet evalSet = evalSetLoader.load();
            List<EvalCase> cases = selectCases(evalSet, caseIds);

            RetrieveOptions base = knowledgeService.defaultOptions();
            Set<String> indexSources = ragIndexService.currentSources();
            String collectionAtStart = ragIndexService.getCurrentCollectionName();
            RagEvalReport previous = reportWriter.loadPrevious(runId);

            log.info("开始评估：{} 条用例 × {} 个变体（{}），集合 [{}]",
                    cases.size(), variants.size(), EvalVariant.allIds(), collectionAtStart);

            List<VariantReport> variantReports = new ArrayList<>();
            for (int i = 0; i < variants.size(); i++) {
                EvalVariant variant = variants.get(i);
                variantReports.add(runVariant(runId, variant, i, variants.size(), cases, base, indexSources, previous));
            }

            boolean collectionChanged = !String.valueOf(collectionAtStart)
                    .equals(String.valueOf(ragIndexService.getCurrentCollectionName()));
            if (collectionChanged) {
                log.warn("评估期间索引集合发生了变化（{} → {}），这批指标前后不可比",
                        collectionAtStart, ragIndexService.getCurrentCollectionName());
            }

            RagEvalReport report = new RagEvalReport(runId, start, System.currentTimeMillis() - start,
                    collectionAtStart, collectionChanged, true, evalSetLoader.getEvalSetFile(),
                    cases.size(), previous == null ? null : previous.runId(), variantReports);
            Path reportPath = reportWriter.write(report);
            latestReport = report;
            success(reportPath.toString(), start);
        } catch (Exception e) {
            failed(e, start);
        }
    }

    /**
     * 跑一个变体的全部用例。
     */
    private VariantReport runVariant(String runId, EvalVariant variant, int index, int variantCount,
                                    List<EvalCase> cases, RetrieveOptions base,
                                    Set<String> indexSources, RagEvalReport previous) {
        RetrieveOptions options = variant.apply(base);
        log.info("变体 [{}/{}] {}：{}", index + 1, variantCount, variant.getLabel(), options.describe());
        progress(runId, variant, index, variantCount, 0, cases.size());

        List<EvalCaseScore> scores = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            scores.add(RetrievalMetrics.score(runCase(cases.get(i), options), indexSources));
            progress(runId, variant, index, variantCount, i + 1, cases.size());
        }

        EvalMetrics metrics = RetrievalMetrics.aggregate(scores);
        log.info("变体 {} 完成：Hit@K={} / MRR={} / P50={}ms",
                variant.getId(), String.format("%.1f%%", metrics.hitRate() * 100),
                String.format("%.3f", metrics.mrr()), metrics.p50Millis());

        Map<String, Double> delta = deltaVs(previous, variant.getId(), metrics);
        return new VariantReport(variant.getId(), variant.getLabel(), options.describe(), metrics, scores, delta);
    }

    /**
     * 跑一条用例。检索失败记进 {@code error} 而不是中断整次评估 ——
     * 一条用例挂了，剩下 29 条的指标依然有价值。
     */
    private EvalCaseResult runCase(EvalCase evalCase, RetrieveOptions options) {
        long start = System.currentTimeMillis();
        try {
            List<TextSegment> segments = knowledgeService.retrieveSegments(evalCase.query(), options, true);
            List<String> sources = segments.stream()
                    .map(KnowledgeService::sourceOf)
                    .collect(Collectors.toList());
            return new EvalCaseResult(evalCase.id(), evalCase.query(), evalCase.expectedSources(),
                    sources, evalCase.tagsOrEmpty(), System.currentTimeMillis() - start, null);
        } catch (Exception e) {
            log.warn("用例 [{}] 检索失败: {}", evalCase.id(), e.getMessage());
            return new EvalCaseResult(evalCase.id(), evalCase.query(), evalCase.expectedSources(),
                    List.of(), evalCase.tagsOrEmpty(), System.currentTimeMillis() - start, e.getMessage());
        }
    }

    /**
     * 取上次同名变体的指标算差值。上次没跑过这个变体（或压根没有上次）时返回空。
     */
    private Map<String, Double> deltaVs(RagEvalReport previous, String variantId, EvalMetrics metrics) {
        if (previous == null || previous.variants() == null) {
            return Map.of();
        }
        return previous.variants().stream()
                .filter(item -> variantId.equals(item.variantId()))
                .findFirst()
                .map(item -> EvalReportWriter.delta(metrics, item.metrics()))
                .orElseGet(Map::of);
    }

    /**
     * 按 {@code caseIds} 过滤出本次要跑的用例。
     */
    private List<EvalCase> selectCases(EvalSet evalSet, List<String> caseIds) {
        List<EvalCase> enabled = evalSet.enabledCases();
        if (caseIds == null || caseIds.isEmpty()) {
            return enabled;
        }
        Set<String> wanted = Set.copyOf(caseIds);
        List<EvalCase> picked = enabled.stream().filter(item -> wanted.contains(item.id())).toList();
        if (picked.isEmpty()) {
            throw new IllegalArgumentException("caseIds " + caseIds + " 没有匹配到任何启用的用例");
        }
        return picked;
    }

    private RagEvalStatusVO idleStatus() {
        RagEvalStatusVO idle = new RagEvalStatusVO();
        idle.setState("IDLE");
        idle.setMessage("空闲");
        return idle;
    }

    private void progress(String runId, EvalVariant variant, int variantIndex, int variantCount,
                         int doneCases, int totalCases) {
        RagEvalStatusVO current = new RagEvalStatusVO();
        current.setState("RUNNING");
        current.setMessage("评估中：" + variant.getLabel());
        current.setRunId(runId);
        current.setStartTime(status.getStartTime());
        current.setTotalVariants(variantCount);
        current.setDoneVariants(variantIndex);
        current.setCurrentVariantId(variant.getId());
        current.setTotalCases(totalCases);
        current.setDoneCases(doneCases);
        this.status = current;
    }

    private void success(String reportPath, long start) {
        RagEvalStatusVO done = new RagEvalStatusVO();
        done.setState("SUCCESS");
        done.setMessage("评估完成，报告已写入 " + reportPath);
        done.setStartTime(start);
        done.setCostMillis(System.currentTimeMillis() - start);
        done.setReportPath(reportPath);
        this.status = done;
    }

    private void failed(Exception e, long start) {
        RagEvalStatusVO failed = new RagEvalStatusVO();
        failed.setState("FAILED");
        failed.setMessage(e.getMessage());
        failed.setStartTime(start);
        failed.setCostMillis(System.currentTimeMillis() - start);
        this.status = failed;
        log.error("评估失败", e);
    }
}
