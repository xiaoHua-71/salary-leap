package com.xiaohua.service;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库索引服务：负责向量库的生命周期（建库、首次入库、重建）。
 *
 * <p>Milvus 是落盘向量库，集合一旦建好就持久化。重建索引时
 * 「删集合 → 重建 → 重新入库」，并把新 store 替换到当前引用上。</p>
 */
@Service
@Slf4j
public class RagIndexService {

    @Resource
    private QwenEmbeddingModel embeddingModel;

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.collection-name:level_knowledge}")
    private String collectionName;

    @Value("${milvus.dimension:1536}")
    private int dimension;

    /** 当前生效的向量库，rebuild 时替换引用。volatile 保证替换对其它线程可见。 */
    private volatile MilvusEmbeddingStore embeddingStore;

    @PostConstruct
    public void init() {
        embeddingStore = buildStore();
        long rowCount = countRows();
        if (rowCount > 0) {
            log.info("知识库集合 [{}] 已有 {} 条向量，跳过入库", collectionName, rowCount);
            return;
        }
        log.info("知识库集合 [{}] 为空，开始首次入库...", collectionName);
        ingest(embeddingStore);
    }

    /**
     * 获取当前向量库（供检索使用）。
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }

    /**
     * 重建索引：删集合 → 重建（自动建集合+索引）→ 重新入库 → 替换引用。
     */
    public synchronized void rebuild() {
        log.info("开始重建索引...");
        embeddingStore.dropCollection(collectionName);
        MilvusEmbeddingStore fresh = buildStore();
        ingest(fresh);
        this.embeddingStore = fresh;
        log.info("重建索引完成");
    }

    /**
     * 建一个 Milvus store。集合不存在时会自动建集合 + 建索引 + load 进内存。
     */
    private MilvusEmbeddingStore buildStore() {
        return MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .dimension(dimension)
                // Milvus Lite 2.2.x 只支持 L2/IP；text-embedding-v2 向量已归一化，IP 等价余弦
                .metricType(MetricType.IP)
                // FLAT 是最基础的暴力检索索引，Lite 一定支持
                .indexType(IndexType.FLAT)
                .build();
    }

    /**
     * 加载 + 切分知识库文档，向量化后写入指定 store。
     */
    private void ingest(MilvusEmbeddingStore store) {
        List<TextSegment> segments = loadAndSplit();
        store.addAll(embeddingModel.embedAll(segments).content(), segments);
        log.info("知识库入库完成，共 {} 段", segments.size());
    }

    private List<TextSegment> loadAndSplit() {
        List<Document> documents = List.of(
                Document.from(readClasspath("knowledge/java-backend.txt")),
                Document.from(readClasspath("knowledge/frontend.txt")),
                Document.from(readClasspath("knowledge/testing.txt")));
        var splitter = DocumentSplitters.recursive(300, 50);
        List<TextSegment> segments = new ArrayList<>();
        for (Document doc : documents) {
            segments.addAll(splitter.split(doc));
        }
        return segments;
    }

    /**
     * 查集合的向量条数，集合不存在时返回 0。
     */
    private long countRows() {
        MilvusServiceClient client = new MilvusServiceClient(
                ConnectParam.newBuilder().withHost(host).withPort(port).build());
        try {
            R<GetCollectionStatisticsResponse> resp = client.getCollectionStatistics(
                    GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (resp.getStatus() != 0 || resp.getData() == null) {
                return 0;
            }
            return resp.getData().getStatsList().stream()
                    .filter(kv -> "row_count".equals(kv.getKey()))
                    .mapToLong(kv -> Long.parseLong(kv.getValue()))
                    .findFirst()
                    .orElse(0L);
        } finally {
            client.close();
        }
    }

    private String readClasspath(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取知识库失败: " + path, e);
        }
    }
}
