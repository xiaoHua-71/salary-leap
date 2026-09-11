package com.xiaohua.service;

import com.xiaohua.model.vo.RebuildStatusVO;
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
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库索引服务：管理向量库生命周期（建库、首次入库、重建）。
 *
 * <p>集合名带时间戳后缀（如 {@code level_knowledge_1725816000000}），
 * 每次重建都新建一个集合再切引用，<b>不做同名 drop + 重建</b>，
 * 从而绕开 Milvus Lite 上删同名集合容易崩溃的问题。</p>
 */
@Service
@Slf4j
public class RagIndexService {

    @Resource
    private QwenEmbeddingModel embeddingModel;

    @Resource
    private RetrievalCache retrievalCache;

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    /** 集合名前缀，实际集合名会在后面拼时间戳 */
    @Value("${milvus.collection-name:level_knowledge}")
    private String baseCollectionName;

    @Value("${milvus.dimension:1536}")
    private int dimension;

    /** 记录当前集合名的小文件，重启后据此复用上次的集合 */
    @Value("${milvus.collection-meta-file:milvus-current-collection.txt}")
    private String metaFilePath;

    /** 当前生效的集合名 */
    private volatile String currentCollectionName;

    /** 当前生效的向量库 */
    private volatile MilvusEmbeddingStore embeddingStore;

    /** 重建索引的单线程执行器（串行执行，避免并发重建） */
    private final ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-rebuild");
        t.setDaemon(true);
        return t;
    });

    /** 重建状态（volatile，每次状态变化整体替换） */
    private volatile RebuildStatusVO rebuildStatus = idleStatus();

    @PostConstruct
    public void init() {
        currentCollectionName = loadCurrentCollectionName();
        if (currentCollectionName == null || currentCollectionName.isBlank()) {
            currentCollectionName = newCollectionName();
        }
        embeddingStore = buildStore(currentCollectionName);
        long rowCount = countRows(currentCollectionName);
        if (rowCount > 0) {
            log.info("集合 [{}] 已有 {} 条向量，跳过入库", currentCollectionName, rowCount);
        } else {
            log.info("集合 [{}] 为空，开始入库...", currentCollectionName);
            ingest(embeddingStore);
        }
        saveCurrentCollectionName(currentCollectionName);
    }

    /**
     * 获取当前向量库（供检索使用）。
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }

    /**
     * 触发异步重建索引。立即返回，实际重建在后台线程执行。
     *
     * @return true 表示已提交；false 表示已有重建在进行中
     */
    public synchronized boolean triggerRebuild() {
        if ("RUNNING".equals(rebuildStatus.getState())) {
            return false;
        }
        RebuildStatusVO running = new RebuildStatusVO();
        running.setState("RUNNING");
        running.setMessage("重建中");
        running.setStartTime(System.currentTimeMillis());
        this.rebuildStatus = running;

        rebuildExecutor.submit(this::doRebuild);
        return true;
    }

    /**
     * 获取当前重建状态（供前端轮询）。
     */
    public RebuildStatusVO getRebuildStatus() {
        return rebuildStatus;
    }

    /**
     * 实际重建：新建一个集合 → 入库 → 切引用。旧集合保留，不做同名 drop。
     */
    private void doRebuild() {
        long start = System.currentTimeMillis();
        try {
            String newName = newCollectionName();
            log.info("开始重建索引，新集合 [{}]", newName);
            MilvusEmbeddingStore fresh = buildStore(newName);
            ingest(fresh);
            this.currentCollectionName = newName;
            this.embeddingStore = fresh;
            saveCurrentCollectionName(newName);
            // 知识库已变，清空检索缓存，避免返回旧结果
            retrievalCache.clearAll();

            long cost = System.currentTimeMillis() - start;
            RebuildStatusVO ok = new RebuildStatusVO();
            ok.setState("SUCCESS");
            ok.setMessage("重建完成");
            ok.setCollectionName(newName);
            ok.setStartTime(start);
            ok.setCostMillis(cost);
            this.rebuildStatus = ok;
            log.info("重建索引完成，新集合 [{}]，耗时 {} ms", newName, cost);
        } catch (Exception e) {
            RebuildStatusVO failed = new RebuildStatusVO();
            failed.setState("FAILED");
            failed.setMessage(e.getMessage());
            failed.setStartTime(start);
            failed.setCostMillis(System.currentTimeMillis() - start);
            this.rebuildStatus = failed;
            log.error("重建索引失败", e);
        }
    }

    private RebuildStatusVO idleStatus() {
        RebuildStatusVO idle = new RebuildStatusVO();
        idle.setState("IDLE");
        idle.setMessage("空闲");
        return idle;
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdown();
    }

    private String newCollectionName() {
        return baseCollectionName + "_" + System.currentTimeMillis();
    }

    private MilvusEmbeddingStore buildStore(String collectionName) {
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

    private long countRows(String collectionName) {
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

    private String loadCurrentCollectionName() {
        try {
            Path p = Paths.get(metaFilePath);
            if (Files.exists(p)) {
                return Files.readString(p).trim();
            }
        } catch (IOException e) {
            log.warn("读取集合名元文件失败: {}", e.getMessage());
        }
        return null;
    }

    private void saveCurrentCollectionName(String name) {
        try {
            Files.writeString(Paths.get(metaFilePath), name);
        } catch (IOException e) {
            log.warn("写入集合名元文件失败: {}", e.getMessage());
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
