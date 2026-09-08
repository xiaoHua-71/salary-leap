package com.xiaohua.config;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库首次入库：应用启动时检查 Milvus 集合是否为空，为空才入库。
 *
 * <p>Milvus 是落盘向量库，不像 InMemory 那样重启就丢，
 * 所以不能每次启动都重复入库（会产生重复数据），必须先判空。</p>
 */
@Component
@Slf4j
public class RagDataInitializer {

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private QwenEmbeddingModel embeddingModel;

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.collection-name:level_knowledge}")
    private String collectionName;

    @PostConstruct
    public void ingestIfEmpty() {
        long rowCount = countRows();
        if (rowCount > 0) {
            log.info("知识库集合 [{}] 已有 {} 条向量，跳过入库", collectionName, rowCount);
            return;
        }
        log.info("知识库集合 [{}] 为空，开始首次入库...", collectionName);
        List<TextSegment> segments = loadAndSplit();
        embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);
        log.info("知识库入库完成，共 {} 段", segments.size());
    }

    /**
     * 加载 + 切分知识库文档（和之前 InMemory 版本逻辑一致）。
     */
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
     * 查 Milvus 集合的向量条数（row_count），集合不存在时返回 0。
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
