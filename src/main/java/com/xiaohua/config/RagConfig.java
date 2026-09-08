package com.xiaohua.config;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置：准备好「向量化模型」和「向量库（Milvus）」。
 *
 * <p>这里只负责创建 Milvus 向量库（连接 + 自动建集合），
 * 知识库的入库动作在 {@link RagDataInitializer} 里做，两者职责分离。</p>
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

    /**
     * Milvus 向量库：连接本地 Milvus，集合不存在时自动创建。
     * dimension 必须和 embedding 模型输出维度一致（text-embedding-v2 = 1536）。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${milvus.host:localhost}") String host,
            @Value("${milvus.port:19530}") int port,
            @Value("${milvus.collection-name:level_knowledge}") String collectionName,
            @Value("${milvus.dimension:1536}") int dimension) {
        return MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .dimension(dimension)
                // Milvus Lite 2.2.x 只支持 L2/IP，不支持 COSINE；
                // text-embedding-v2 向量已归一化，IP 等价于余弦相似度
                .metricType(MetricType.IP)
                // FLAT 是最基础的暴力检索索引，Lite 一定支持；数据量大再换 HNSW
                .indexType(IndexType.FLAT)
                .build();
    }
}
