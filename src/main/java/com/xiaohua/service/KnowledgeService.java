package com.xiaohua.service;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 知识检索服务：RAG 的「R」（Retrieval）。
 *
 * <p>两步检索：先用向量做「召回」（取较多候选），再用 rerank 模型「精排」（取最相关的几个）。
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

    /** 是否开启 rerank 精排 */
    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    /** 向量检索先召回的候选数量 */
    @Value("${rag.rerank.recall:20}")
    private int recall;

    /** 最终交给大模型的片段数量 */
    @Value("${rag.retrieve.top-k:3}")
    private int topK;

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
            // 1. 把查询也向量化 —— 必须用和知识库同一个模型，才能在同一空间比较相似度
            Embedding queryEmbedding = embeddingModel.embed(query).content();//拿到向量化后的数据

            // 2. 向量检索（召回）：取较多候选，交给 rerank 精排
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(recall)
                    .minScore(0.2)
                    .build();
            EmbeddingStore<TextSegment> store = ragIndexService.getEmbeddingStore();
            EmbeddingSearchResult<TextSegment> result = store.search(request);

            List<TextSegment> candidates = result.matches().stream()
                    .map(EmbeddingMatch::embedded)
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                log.info("查询 [{}] 未检索到相关知识", query);
                return "";
            }

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
