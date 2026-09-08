package com.xiaohua.config;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置：向量化模型。
 *
 * <p>向量库（Milvus）的生命周期由 {@code RagIndexService} 管理，
 * 这里只负责提供 embedding 模型 bean。</p>
 */
@Configuration
public class RagConfig {

    /**
     * 向量化模型：把文字转成向量。复用出题 chat 模型的 api-key。
     */
    @Bean
    public QwenEmbeddingModel qwenEmbeddingModel(
            @Value("${langchain4j.community.dashscope.chat-model.api-key}") String apiKey) {
        return QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-v2")
                .build();
    }
}
