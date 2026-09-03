package com.xiaohua.config;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 配置：准备好「向量化模型」和「内存向量库」。
 *
 * <p>这里的知识库在应用启动时一次性加载、切分、向量化、入库，
 * 之后查询时直接在内存向量库里做相似度检索，不依赖外部向量数据库。</p>
 */
@Configuration
public class RagConfig {

    /**
     * 向量化模型：把文字转成向量。
     * 必须和出题用的 chat 模型配合，但 api-key 复用同一个即可。
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
     * 内存向量库：启动时把知识库切分、向量化后存进来。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(QwenEmbeddingModel embeddingModel) {
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        // 1. 加载知识库文档（这里用 classpath 下的 3 个 txt 作为知识库）
        List<Document> documents = List.of(
                Document.from(readClasspath("knowledge/java-backend.txt")),
                Document.from(readClasspath("knowledge/frontend.txt")),
                Document.from(readClasspath("knowledge/testing.txt"))
        );

        // 2. 切分：每段最多 300 字、相邻段重叠 50 字，避免把一句话从中间切断
        var splitter = DocumentSplitters.recursive(300, 50);
        List<TextSegment> segments = new ArrayList<>();
        for (Document doc : documents) {
            segments.addAll(splitter.split(doc));
        }

        // 3. 向量化 + 4. 写入内存向量库（每个片段对应一个向量）
        store.addAll(embeddingModel.embedAll(segments).content(), segments);
        return store;
    }

    private String readClasspath(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取知识库失败: " + path, e);
        }
    }
}
