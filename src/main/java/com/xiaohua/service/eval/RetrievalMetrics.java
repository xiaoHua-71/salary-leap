package com.xiaohua.service.eval;

import com.xiaohua.model.eval.EvalCaseResult;
import com.xiaohua.model.eval.EvalCaseScore;
import com.xiaohua.model.eval.EvalMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 检索指标计算：把「一条条观测结果」变成「一组数字」。
 *
 * <p><b>整类都是纯函数</b>：不碰网络、不碰 Spring、不碰文件系统，输入是数据、输出是数据。
 * 所以指标算法可以完全离线单测 —— 这件事很值得，因为指标算错的后果是
 * 「看起来很专业的报告，结论全是错的」，而且没人会怀疑一张表格。</p>
 *
 * <p>分母统一做了保护：任何一项算不出（分母为 0）都返回 0 而不是 {@code NaN}。
 * 报告里出现 NaN 会一路传染下去，最后变成「这个指标我们不看」。</p>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * 给一条观测结果算分。
     *
     * <p>顺带做出那个关键分类：没命中的期望来源，到底是<b>不在索引里</b>（补数据）
     * 还是<b>在索引里没捞到</b>（调检索）。</p>
     *
     * @param result       一条用例的原始观测
     * @param indexSources 当前索引里的全部来源（{@code RagIndexService.currentSources()}）；
     *                     传空表示「不知道索引里有什么」，此时未命中的一律归为检索的锅
     */
    public static EvalCaseScore score(EvalCaseResult result, Set<String> indexSources) {
        List<String> returned = result.returnedSources() == null ? List.of() : result.returnedSources();
        List<String> expected = result.expectedSources() == null ? List.of() : result.expectedSources();
        boolean indexKnown = indexSources != null && !indexSources.isEmpty();

        List<String> hitExpected = new ArrayList<>();
        int hitRank = 0;
        int hitCount = 0;
        for (int i = 0; i < returned.size(); i++) {
            String actual = returned.get(i);
            if (actual == null) {
                continue;
            }
            for (String exp : expected) {
                if (EvalSourceMatcher.matches(exp, actual)) {
                    hitCount++;
                    if (hitRank == 0) {
                        // 名次从 1 开始：第 1 个位置命中就是最理想的情况（MRR 记满分）
                        hitRank = i + 1;
                    }
                    if (!hitExpected.contains(exp)) {
                        hitExpected.add(exp);
                    }
                    // 一个片段只算命中一次，别因为期望写了多条就重复计数
                    break;
                }
            }
        }

        List<String> notInIndex = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (String exp : expected) {
            if (hitExpected.contains(exp)) {
                continue;
            }
            if (indexKnown && !inIndex(exp, indexSources)) {
                notInIndex.add(exp);
            } else {
                missed.add(exp);
            }
        }

        int distinctSources = (int) returned.stream()
                .filter(Objects::nonNull)
                .map(EvalSourceMatcher::normalize)
                .filter(source -> !source.isEmpty())
                .distinct()
                .count();

        return new EvalCaseScore(result.caseId(), result.query(), List.copyOf(expected),
                // 不能用 List.copyOf：没有来源元数据的片段会给出一条 null，
                // 而 List.copyOf 拒绝 null 元素，会直接抛 NPE 把整条用例搞挂
                Collections.unmodifiableList(new ArrayList<>(returned)),
                List.copyOf(hitExpected), List.copyOf(notInIndex),
                List.copyOf(missed), hitRank, hitCount, returned.size(), distinctSources,
                result.costMillis(), result.error());
    }

    /**
     * 期望来源是否在当前索引里。
     */
    private static boolean inIndex(String expected, Set<String> indexSources) {
        return indexSources.stream().anyMatch(actual -> EvalSourceMatcher.matches(expected, actual));
    }

    /**
     * 汇总一组用例的指标。
     *
     * <p>召回率/准确率用<b>微平均</b>（先各自累加再相除），不是「每条用例算一遍再平均」——
     * 后者会让只有 1 个期望来源的用例和写了 3 个的用例权重相同，掩盖掉覆盖面差异。</p>
     */
    public static EvalMetrics aggregate(List<EvalCaseScore> scores) {
        int total = scores.size();
        if (total == 0) {
            return new EvalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        int failed = 0;
        int hits = 0;
        int empty = 0;
        double mrrSum = 0;
        double diversitySum = 0;
        int expectedTotal = 0;
        int expectedHit = 0;
        int returnedTotal = 0;
        int hitTotal = 0;
        List<Long> costs = new ArrayList<>(total);

        for (EvalCaseScore score : scores) {
            if (score.error() != null) {
                failed++;
            }
            if (score.hit()) {
                hits++;
                mrrSum += 1.0 / score.hitRank();
            }
            if (score.error() == null && score.returnedCount() == 0) {
                empty++;
            }
            if (score.returnedCount() > 0) {
                diversitySum += (double) score.distinctSources() / score.returnedCount();
            }
            expectedTotal += score.expectedSources().size();
            expectedHit += score.hitExpected().size();
            returnedTotal += score.returnedCount();
            hitTotal += score.hitCount();
            costs.add(score.costMillis());
        }

        return new EvalMetrics(total, failed,
                div(hits, total),
                div(mrrSum, total),
                div(expectedHit, expectedTotal),
                div(hitTotal, returnedTotal),
                div(diversitySum, total),
                div(returnedTotal, total),
                div(empty, total),
                percentile(costs, 50),
                percentile(costs, 95));
    }

    /** 除法保护：分母为 0 时返回 0，绝不产生 NaN（NaN 一旦写进报告就没人再看这个指标了） */
    private static double div(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    /**
     * 最近秩百分位（nearest-rank）：把样本排序后取第 {@code ceil(p/100 * n)} 个。
     *
     * <p>样本少时它比插值法更保守 —— 2 个样本的 P50 取的是较小那个。
     * 评估集只有几十条、耗时又是长尾分布，用这个足够了，不值得引入统计库。</p>
     */
    static long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        int index = (int) Math.ceil(percentile / 100.0 * n) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= n) {
            index = n - 1;
        }
        return sorted.get(index);
    }
}
