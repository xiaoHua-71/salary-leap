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
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
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
 *
 * <p><b>为什么不用 Milvus 的统计接口判断「集合是否已入库」</b>：
 * {@code getCollectionStatistics} 返回的 {@code row_count} <b>只统计已 flush 的数据</b>，
 * 而 {@code MilvusEmbeddingStore.addAll} 并不触发 flush —— 于是刚重建完的集合
 * 统计出来的行数是 0，重启时会被误判成「空集合」而<b>把整个知识库再灌一遍</b>。
 * 所以入库状态只认我们自己写的元文件：它记录「哪个集合、入了多少段」，
 * 是入库成功之后才落盘的，重启时据此复用即可，完全不依赖 Milvus 的内部行为。</p>
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
        // 知识片段只切一次：向量入库和 BM25 索引共用同一份，避免两份切分结果对不上
        List<TextSegment> segments = loadAndSplit();

        CollectionMeta meta = loadMeta();
        boolean freshCollection = meta == null || meta.collectionName() == null
                || meta.collectionName().isBlank();
        currentCollectionName = freshCollection ? newCollectionName() : meta.collectionName();
        embeddingStore = buildStore(currentCollectionName);

        if (freshCollection) {
            log.info("新建集合 [{}]，开始入库...", currentCollectionName);
            ingest(embeddingStore, segments);
        } else {
            log.info("复用集合 [{}]，跳过入库", currentCollectionName);
            if (meta.segmentCount() == null) {
                log.info("元文件是旧格式（只记了集合名、没记片段数），本次按「已入库」处理");
            } else if (meta.segmentCount() != segments.size()) {
                log.warn("集合 [{}] 入库时是 {} 段，当前知识库切出 {} 段，两者不一致；"
                                + "改过知识库内容或切分参数后请调 POST /rag/rebuild 重建。"
                                + "注意不要在旧集合上重复入库",
                        currentCollectionName, meta.segmentCount(), segments.size());
            }
        }

        // BM25 索引是纯内存的、进程重启就没了，所以必须无条件重建
        // （不能只在「入库」分支里建，否则复用上次集合时 BM25 会是空的）
        bm25Index.rebuild(segments);
        saveMeta(new CollectionMeta(currentCollectionName, segments.size()));
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
            saveMeta(new CollectionMeta(newName, segments.size()));
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
        documents.addAll(webPageLoader.load());
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

    /**
     * 读元文件。文件不存在或读不出来时返回 null（调用方按「新建集合」处理）。
     */
    private CollectionMeta loadMeta() {
        try {
            Path p = Paths.get(metaFilePath);
            if (!Files.exists(p)) {
                return null;
            }
            return parseMeta(Files.readString(p));
        } catch (IOException e) {
            log.warn("读取集合元文件失败，将按新建集合处理: {}", e.getMessage());
            return null;
        }
    }

    private void saveMeta(CollectionMeta meta) {
        try {
            Files.writeString(Paths.get(metaFilePath), formatMeta(meta));
        } catch (IOException e) {
            log.warn("写入集合元文件失败: {}", e.getMessage());
        }
    }

    /**
     * 解析元文件内容。
     *
     * <p>新格式两行 {@code collection=} / {@code segments=}；
     * 兼容旧格式（整个文件只有集合名一行，此时片段数未知，返回 null）。</p>
     */
    static CollectionMeta parseMeta(String content) {
        String name = null;
        Integer segmentCount = null;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                // 旧格式：只有集合名，没有片段数
                if (name == null) {
                    name = line;
                }
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if ("collection".equals(key)) {
                name = value;
            } else if ("segments".equals(key)) {
                try {
                    segmentCount = Integer.valueOf(value);
                } catch (NumberFormatException ignored) {
                    // 片段数写坏了就当未知，不影响集合名的复用
                    log.warn("元文件里的片段数无法解析: {}", value);
                }
            }
        }
        return new CollectionMeta(name, segmentCount);
    }

    static String formatMeta(CollectionMeta meta) {
        return "collection=" + meta.collectionName() + System.lineSeparator()
                + "segments=" + meta.segmentCount() + System.lineSeparator();
    }

    private String readClasspath(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取知识库失败: " + path, e);
        }
    }

    /**
     * 集合元信息：当前生效的集合名 + 入库时的片段数。
     *
     * <p>{@code segmentCount} 可能是 null —— 旧版元文件只记了集合名。</p>
     */
    record CollectionMeta(String collectionName, Integer segmentCount) {
    }
}
