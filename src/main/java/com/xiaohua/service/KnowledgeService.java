package com.xiaohua.service;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 知识检索服务：RAG 的「R」（Retrieval）。
 *
 * <p>四步检索：</p>
 * <ol>
 *   <li><b>查询改写</b>：把「学习方向」这类短标签扩展成若干条具体主题查询
 *       （{@link QueryRewriter}），解决短标签召回不出知识面广度的问题</li>
 *   <li><b>混合召回</b>：每条查询各做一次「向量查语义 + BM25 查字面」双路召回</li>
 *   <li><b>RRF 融合</b>：所有召回列表累加进同一张分数表，多路都靠前的片段浮上来</li>
 *   <li><b>rerank 精排</b>：用<b>原查询</b>评判，取最相关的前 topK 段</li>
 * </ol>
 *
 * <p>拼成一段文本返回给调用方，塞进提示词（Augment）后交给模型生成。</p>
 */
@Service
@Slf4j
public class KnowledgeService {

    @Resource
    private RagIndexService ragIndexService;

    @Resource
    private QwenEmbeddingModel embeddingModel;

    @Resource
    private RetrievalCache retrievalCache;

    @Resource
    private ScoringModel scoringModel;

    @Resource
    private Bm25Index bm25Index;

    @Resource
    private QueryRewriter queryRewriter;

    /** 是否开启 rerank 精排 */
    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    /** 向量检索先召回的候选数量 */
    @Value("${rag.rerank.recall:20}")
    private int recall;

    /** 最终交给大模型的片段数量 */
    @Value("${rag.retrieve.top-k:3}")
    private int topK;

    /** 同一来源（一篇文章）最多占 topK 里的几个位置；{@code <= 0} 表示不限制 */
    @Value("${rag.retrieve.max-per-source:1}")
    private int maxPerSource = 1;

    /** 是否开启混合检索（BM25 关键词 + 向量双路召回） */
    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;

    /** BM25 这一路召回多少候选 */
    @Value("${rag.hybrid.recall:20}")
    private int bm25Recall;

    /** RRF 平滑常数 */
    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;

    /** 融合去重后、送进 rerank 前的候选上限 */
    @Value("${rag.hybrid.max-candidates:30}")
    private int maxCandidates;

    /**
     * 根据查询检索相关知识片段，拼接成一段文本返回。
     *
     * <p>检索失败不抛异常：embedding 调用失败、Milvus 不可用等都返回空字符串，
     * 由调用方决定兜底策略（退回纯模型出题），保证出题主流程不被检索环节拖垮。</p>
     *
     * <p>带缓存：相同查询直接返回上次结果，不重复向量化和检索。
     * 缓存由 {@code RagIndexService} 在重建索引后清空。</p>
     *
     * @param query 查询文本（例如学习方向：Java后端开发 / 前端开发 / 软件测试）
     * @return 检索到的知识片段（无结果或检索失败时返回空字符串）
     */
    public String retrieve(String query) {
        // 0. 先查缓存：相同查询不重复「向量化 + 检索」
        String cached = retrievalCache.get(query);
        if (cached != null) {
            log.debug("检索缓存命中: {}", query);
            return cached;
        }
        try {
            // 1. 查询改写：把方向标签扩展成若干条具体主题查询（原查询始终在第一位）
            List<String> queries = queryRewriter.rewrite(query);

            // 2. 每条查询各做一次双路召回：向量查「语义相近」，BM25 查「字面命中关键词」
            Map<String, Double> scores = new LinkedHashMap<>();
            Map<String, TextSegment> byText = new LinkedHashMap<>();
            int vectorTotal = 0;
            int keywordTotal = 0;
            for (String currentQuery : queries) {
                List<TextSegment> vectorCandidates = vectorRecall(currentQuery);
                List<TextSegment> keywordCandidates = keywordRecall(currentQuery);
                vectorTotal += vectorCandidates.size();
                keywordTotal += keywordCandidates.size();
                accumulateRrf(vectorCandidates, scores, byText);
                accumulateRrf(keywordCandidates, scores, byText);
            }

            // 3. 按融合分降序取候选：多路都排在前面的片段会自然浮上来
            List<TextSegment> candidates = topCandidates(scores, byText);
            if (candidates.isEmpty()) {
                log.info("查询 [{}] 未检索到相关知识", query);
                return "";
            }
            log.info("检索召回：{} 条查询 → 向量 {} 条 / BM25 {} 条 / 融合 {} 条 → rerank 取 top{}",
                    queries.size(), vectorTotal, keywordTotal, candidates.size(), topK);

            // 4. rerank 用**原查询**精排 —— 评判标准始终是用户真正要的那个方向
            List<TextSegment> top = rerank(query, candidates);

            // 4. 把片段原文拼起来（Augment 的原料）
            String joined = top.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.joining("\n\n---\n\n"));
            // 5. 回写缓存
            retrievalCache.put(query, joined);
            return joined;
        } catch (Exception e) {
            // 兜底降级：检索环节任何异常都不影响出题，退回"无知识"出题
            log.warn("知识检索失败，将退回纯模型出题: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * 向量召回：把查询向量化后，到 Milvus 里找语义相近的片段。
     *
     * <p>失败只记 warn 返回空列表，交由 BM25 那一路顶上 —— 比「任一路挂了就整体返回空」更抗故障。</p>
     */
    private List<TextSegment> vectorRecall(String query) {
        try {
            // 查询必须用和知识库同一个 embedding 模型，才能在同一空间比较相似度
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(recall)
                    .minScore(0.2)
                    .build();
            EmbeddingStore<TextSegment> store = ragIndexService.getEmbeddingStore();
            return store.search(request).matches().stream()
                    .map(EmbeddingMatch::embedded)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("向量召回失败，本次仅用 BM25 关键词结果: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * BM25 关键词召回：捞出字面命中查询词/术语的片段。
     *
     * <p>关闭混合检索或索引为空时返回空列表。</p>
     */
    private List<TextSegment> keywordRecall(String query) {
        if (!hybridEnabled) {
            return List.of();
        }
        try {
            return bm25Index.search(query, bm25Recall);
        } catch (Exception e) {
            log.warn("BM25 召回失败，本次仅用向量结果: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 RRF 融合分降序取出候选，截断到 {@code max-candidates}。
     */
    private List<TextSegment> topCandidates(Map<String, Double> scores, Map<String, TextSegment> byText) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxCandidates)
                .map(entry -> byText.get(entry.getKey()))
                .collect(Collectors.toList());
    }

    /**
     * RRF（Reciprocal Rank Fusion）倒数排名融合：把一个召回列表的排名累加进分数表。
     *
     * <p>{@code score(d) = Σ 1 / (k + rank)}，rank 从 1 开始。用排名而非分数，
     * 天然免疫量纲差异：向量余弦分在 [-1,1]、BM25 分无上界，直接相加得先归一化再调权重，
     * 而 RRF 只依赖「谁排在前面」，无需任何调参。</p>
     *
     * <p>查询改写后会有多条查询 × 两条召回路径，也就是多路列表累加进同一张分数表 ——
     * 这是 RRF 的天然优势：<b>在越多路里都排得靠前的片段，分数越高</b>，
     * 相当于让多路「投票」选出共识片段。</p>
     *
     * <p>去重按文本（trim 后）而非对象身份：向量侧是 Milvus 反序列化出的新对象，
     * 和 BM25 索引里的实例不是同一个，比 identity 必然去不掉。</p>
     */
    private void accumulateRrf(List<TextSegment> candidates,
                              Map<String, Double> scores,
                              Map<String, TextSegment> byText) {
        for (int i = 0; i < candidates.size(); i++) {
            TextSegment segment = candidates.get(i);
            String key = segment.text().trim();
            // k 起平滑作用：越大越削弱「两路都排第一」的叠加优势，60 是原论文默认值
            scores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
            byText.putIfAbsent(key, segment);
        }
    }

    /**
     * 对候选片段做 rerank 精排，返回最相关的前 topK 个。
     *
     * <p>几层保护：关闭 rerank / 候选本来就不多 → 直接取前 topK；
     * rerank 调用失败或返回分数不匹配 → 退回向量检索的原始顺序。
     * 保证「rerank 挂了」不会拖垮检索。</p>
     *
     * <p>四条返回路径都走 {@link #selectDiverse}：选出前 topK 时优先让**不同来源**的片段占位。</p>
     */
    private List<TextSegment> rerank(String query, List<TextSegment> segments) {
        if (!rerankEnabled || segments.size() <= topK) {
            return selectDiverse(segments, topK, maxPerSource);
        }
        try {
            List<Double> scores = scoringModel.scoreAll(segments, query).content();
            if (scores == null || scores.size() != segments.size()) {
                log.warn("Rerank 返回分数数量不匹配，退回原始顺序");
                return selectDiverse(segments, topK, maxPerSource);
            }
            List<TextSegment> ordered = IntStream.range(0, segments.size()).boxed()
                    .sorted((a, b) -> Double.compare(scores.get(b), scores.get(a)))
                    .map(segments::get)
                    .collect(Collectors.toList());
            return selectDiverse(ordered, topK, maxPerSource);
        } catch (Exception e) {
            log.warn("Rerank 失败，退回向量检索原始顺序: {}", e.getMessage());
            return selectDiverse(segments, topK, maxPerSource);
        }
    }

    /**
     * 按排序取前 limit 个片段，并限制**同一来源最多占 maxPerSource 个位置**。
     *
     * <p>为什么需要：切片参数是 {@code chunk=500, overlap=100}，
     * 同一篇文章的相邻片段自带 100 字重叠 —— 让一篇文章占满 top-3 里两格，
     * 等于把一整格浪费在近似重复的内容上。实测确实出现过（同一篇 MySQL 文章占了两格）。</p>
     *
     * <p>两个边界：</p>
     * <ul>
     *   <li>{@code maxPerSource <= 0} 表示不限制，等价于直接取前 limit 个</li>
     *   <li>当不同来源不足（知识库本来就没几篇）时，用被跳过的片段**补齐**，
     *       保证返回数量不缩水 —— 宁可有重复，也不要少给知识</li>
     * </ul>
     *
     * @param ordered      已按相关性排好序的候选
     * @param limit        最多返回几个
     * @param maxPerSource 同一来源最多占几个位置；{@code <= 0} 表示不限制
     */
    static List<TextSegment> selectDiverse(List<TextSegment> ordered, int limit, int maxPerSource) {
        if (limit <= 0) {
            return List.of();
        }
        if (maxPerSource <= 0) {
            return ordered.stream().limit(limit).collect(Collectors.toList());
        }
        Map<String, Integer> used = new HashMap<>();
        List<TextSegment> picked = new ArrayList<>();
        List<TextSegment> skipped = new ArrayList<>();
        for (TextSegment segment : ordered) {
            if (picked.size() >= limit) {
                break;
            }
            String source = sourceOf(segment);
            if (source == null) {
                // 来源未知：不参与限制。若用 null 当 key，一批「未知」片段会被当成同一篇互相挤掉
                picked.add(segment);
                continue;
            }
            if (used.getOrDefault(source, 0) < maxPerSource) {
                picked.add(segment);
                used.merge(source, 1, Integer::sum);
            } else {
                skipped.add(segment);
            }
        }
        // 来源不够多，用跳过的片段按原顺序补齐，避免返回数量变少
        for (TextSegment segment : skipped) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(segment);
        }
        return picked;
    }

    /**
     * 取片段的来源标识（网页是 {@code url}、内置文件是 {@code file_name}）。
     *
     * <p>没有来源元数据的片段返回 null —— 这些片段不参与去重限制，
     * 否则一堆「来源未知」的片段会被当成同一篇而互相挤掉。</p>
     */
    static String sourceOf(TextSegment segment) {
        if (segment.metadata() == null) {
            return null;
        }
        String url = segment.metadata().getString(Document.URL);
        if (url != null && !url.isBlank()) {
            return url;
        }
        String fileName = segment.metadata().getString(Document.FILE_NAME);
        return fileName == null || fileName.isBlank() ? null : fileName;
    }
}
