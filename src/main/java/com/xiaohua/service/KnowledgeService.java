package com.xiaohua.service;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
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
public class KnowledgeService {

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private QwenEmbeddingModel embeddingModel;

    /**
     * 根据查询检索相关知识片段，拼接成一段文本返回。
     *
     * @param query 查询文本（例如学习方向：Java后端开发 / 前端开发 / 软件测试）
     * @return 检索到的知识片段（无结果时返回空字符串）
     */
    public String retrieve(String query) {
        // 1. 把查询也向量化 —— 必须用和知识库同一个模型，才能在同一空间比较相似度
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // 2. 在向量库里做相似度检索，取最像的前 3 段
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(3)
                .minScore(0.2)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        if (matches.isEmpty()) {
            return "";
        }
        // 3. 把命中的片段原文拼起来（Augment 的原料）
        return matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
