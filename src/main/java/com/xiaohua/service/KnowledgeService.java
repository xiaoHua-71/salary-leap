package com.xiaohua.service;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识检索服务：RAG 的「R」（Retrieval）。
 *
 * <p>把用户查询也向量化，然后在向量库里找语义最接近的若干片段，
 * 拼成一段文本返回给调用方，供其塞进提示词（Augment）再交给模型生成。</p>
 */
@Service
@Slf4j
public class KnowledgeService {

    @Resource
    private RagIndexService ragIndexService;

    @Resource
    private QwenEmbeddingModel embeddingModel;

    /**
     * 根据查询检索相关知识片段，拼接成一段文本返回。
     *
     * <p>检索失败不抛异常：embedding 调用失败、Milvus 不可用等都返回空字符串，
     * 由调用方决定兜底策略（退回纯模型出题），保证出题主流程不被检索环节拖垮。</p>
     *
     * @param query 查询文本（例如学习方向：Java后端开发 / 前端开发 / 软件测试）
     * @return 检索到的知识片段（无结果或检索失败时返回空字符串）
     */
    public String retrieve(String query) {
        try {
            // 1. 把查询也向量化 —— 必须用和知识库同一个模型，才能在同一空间比较相似度
            Embedding queryEmbedding = embeddingModel.embed(query).content();//拿到向量化后的数据

            // 2. 在向量库里做相似度检索，取最像的前 3 段
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(3)
                    .minScore(0.2)
                    .build();
            EmbeddingStore<TextSegment> store = ragIndexService.getEmbeddingStore();
            EmbeddingSearchResult<TextSegment> result = store.search(request);

            List<EmbeddingMatch<TextSegment>> matches = result.matches();
            if (matches.isEmpty()) {
                log.info("查询 [{}] 未检索到相关知识", query);
                return "";
            }
            // 3. 把命中的片段原文拼起来（Augment 的原料）
            return matches.stream()
                    .map(match -> match.embedded().text())
                    .collect(Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            // 兜底降级：检索环节任何异常都不影响出题，退回"无知识"出题
            log.warn("知识检索失败，将退回纯模型出题: {}", e.getMessage(), e);
            return "";
        }
    }
}
