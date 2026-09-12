package com.xiaohua.service;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 知识检索服务：RAG 的「R」（Retrieval）。
 *
 * <p>三步检索：先「混合召回」（向量查语义 + BM25 查字面关键词），
 * 再用 RRF 把两路结果融合成一份候选，最后用 rerank 模型「精排」取最相关的几个。
 * 拼成一段文本返回给调用方，塞进提示词（Augment）后交给模型生成。</p>
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

    /** 是否开启 rerank 精排 */
    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    /** 向量检索先召回的候选数量 */
    @Value("${rag.rerank.recall:20}")
    private int recall;

    /** 最终交给大模型的片段数量 */
    @Value("${rag.retrieve.top-k:3}")
    private int topK;

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
            // 1. 双路召回：向量查「语义相近」，BM25 查「字面命中关键词」
            List<TextSegment> vectorCandidates = vectorRecall(query);
            List<TextSegment> keywordCandidates = keywordRecall(query);

            // 2. RRF 融合两路结果，去重成一份候选
            List<TextSegment> candidates = fuse(vectorCandidates, keywordCandidates);
            if (candidates.isEmpty()) {
                log.info("查询 [{}] 未检索到相关知识", query);
                return "";
            }
            log.info("检索召回：向量 {} 条 / BM25 {} 条 / 融合 {} 条 → rerank 取 top{}",
                    vectorCandidates.size(), keywordCandidates.size(), candidates.size(), topK);

            // 3. rerank 精排，取最相关的 topK 段
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
     * RRF（Reciprocal Rank Fusion）倒数排名融合：只看两路里各自的排名，不看原始分数。
     *
     * <p>{@code score(d) = Σ 1 / (k + rank)}，rank 从 1 开始。用排名而非分数，
     * 天然免疫量纲差异：向量余弦分在 [-1,1]、BM25 分无上界，直接相加得先归一化再调权重，
     * 而 RRF 只依赖「谁排在前面」，无需任何调参。</p>
     *
     * <p>去重按文本（trim 后）而非对象身份：向量侧是 Milvus 反序列化出的新对象，
     * 和 BM25 索引里的实例不是同一个，比 identity 必然去不掉。</p>
     */
    private List<TextSegment> fuse(List<TextSegment> vectorCandidates, List<TextSegment> keywordCandidates) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, TextSegment> byText = new LinkedHashMap<>();
        accumulateRrf(vectorCandidates, scores, byText);
        accumulateRrf(keywordCandidates, scores, byText);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxCandidates)
                .map(entry -> byText.get(entry.getKey()))
                .collect(Collectors.toList());
    }

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
     */
    private List<TextSegment> rerank(String query, List<TextSegment> segments) {
        if (!rerankEnabled || segments.size() <= topK) {
            return segments.stream().limit(topK).collect(Collectors.toList());
        }
        try {
            List<Double> scores = scoringModel.scoreAll(segments, query).content();
            if (scores == null || scores.size() != segments.size()) {
                log.warn("Rerank 返回分数数量不匹配，退回原始顺序");
                return segments.stream().limit(topK).collect(Collectors.toList());
            }
            return IntStream.range(0, segments.size()).boxed()
                    .sorted((a, b) -> Double.compare(scores.get(b), scores.get(a)))
                    .limit(topK)
                    .map(segments::get)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Rerank 失败，退回向量检索原始顺序: {}", e.getMessage());
            return segments.stream().limit(topK).collect(Collectors.toList());
        }
    }
}
