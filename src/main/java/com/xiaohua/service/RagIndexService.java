package com.xiaohua.service;

import com.xiaohua.model.vo.RebuildStatusVO;
import com.xiaohua.service.loader.WebPageLoader;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
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

    /** 随包发布的基础知识文件（classpath 下的相对路径） */
    private static final List<String> CLASSPATH_DOCS = List.of(
            "knowledge/java-backend.txt",
            "knowledge/frontend.txt",
            "knowledge/testing.txt");

    @Resource
    private QwenEmbeddingModel embeddingModel;

    @Resource
    private RetrievalCache retrievalCache;

    @Resource
    private Bm25Index bm25Index;

    @Resource
    private WebPageLoader webPageLoader;

    /** 单个知识片段的最大字符数 */
    @Value("${rag.split.chunk-size:500}")
    private int chunkSize;

    /** 相邻片段的重叠字符数 */
    @Value("${rag.split.overlap:100}")
    private int overlap;

    /** 要抓取入库的网页地址（rag.web.urls，可为空） */
    @Value("${rag.web.urls:}")
    private List<String> webUrls;

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
        // 知识片段只切一次：向量入库和 BM25 索引共用同一份，避免两份切分结果对不上
        List<TextSegment> segments = loadAndSplit();
        long rowCount = countRows(currentCollectionName);
        if (rowCount > 0) {
            log.info("集合 [{}] 已有 {} 条向量，跳过入库", currentCollectionName, rowCount);
            if (rowCount != segments.size()) {
                log.warn("集合 [{}] 有 {} 条向量，但知识库文件切出 {} 段，两者可能不一致；"
                                + "改过 knowledge/*.txt 后请调 POST /rag/rebuild 重建",
                        currentCollectionName, rowCount, segments.size());
            }
        } else {
            log.info("集合 [{}] 为空，开始入库...", currentCollectionName);
            ingest(embeddingStore, segments);
        }
        // BM25 索引是纯内存的、进程重启就没了，所以必须无条件重建
        // （不能只在「入库」分支里建，否则复用上次集合时 BM25 会是空的）
        bm25Index.rebuild(segments);
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
            List<TextSegment> segments = loadAndSplit();
            MilvusEmbeddingStore fresh = buildStore(newName);
            // 向量和 BM25 都先建好，成功后才切 live 引用；中途失败线上索引原封不动
            ingest(fresh, segments);
            bm25Index.rebuild(segments);
            // 两次 volatile 写紧挨着，中间存在「新 BM25 + 旧向量」的极小窗口。
            // 重建读的是同一批知识库文件、两套语料内容一致，加上融合时按文本去重，
            // 该窗口最多让某一次检索的排序略脏，不值得为此引入额外的持有类重构。
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

    private void ingest(MilvusEmbeddingStore store, List<TextSegment> segments) {
        store.addAll(embeddingModel.embedAll(segments).content(), segments);
        log.info("知识库入库完成，共 {} 段", segments.size());
    }

    /**
     * 汇总知识库的全部文档来源：内置 classpath 文本 + 抓取的网页正文。
     *
     * <p>每个文档都带来源元数据（网页用 {@code url}、内置文件用 {@code file_name}，
     * 都是 LangChain4j 的约定键），切分器会把它复制到每个片段上，
     * Milvus 自动存成 JSON 字段、检索时还原 —— 所以「这段知识出自哪」一路可查。</p>
     */
    private List<Document> loadDocuments() {
        List<Document> documents = new ArrayList<>();
        for (String path : CLASSPATH_DOCS) {
            documents.add(Document.from(readClasspath(path), Metadata.from(Document.FILE_NAME, path)));
        }
        documents.addAll(webPageLoader.load(webUrls));
        return documents;
    }

    private List<TextSegment> loadAndSplit() {
        List<Document> documents = loadDocuments();
        var splitter = DocumentSplitters.recursive(chunkSize, overlap);
        List<TextSegment> segments = new ArrayList<>();
        for (Document doc : documents) {
            segments.addAll(splitter.split(doc));
        }
        log.info("知识库加载完成：{} 个文档 → {} 个片段（chunk={}, overlap={}）",
                documents.size(), segments.size(), chunkSize, overlap);
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
