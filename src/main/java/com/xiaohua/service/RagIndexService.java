package com.xiaohua.service;

import com.xiaohua.model.vo.RebuildStatusVO;
import com.xiaohua.service.loader.WebPageLoader;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 知识库索引服务：管理向量库生命周期（建库、首次入库、增量更新、全量重建）。
 *
 * <p>两种写入方式：</p>
 * <ul>
 *   <li><b>增量更新</b>（默认，{@code POST /rag/rebuild}）：集合不变，
 *       只对「内容变了的来源」重新 embedding，并先删掉该来源的旧片段再插新的。
 *       依据是 {@link IndexManifest} 记的来源指纹 —— 指纹一致就一个 embedding 调用都不发。</li>
 *   <li><b>全量重建</b>（{@code POST /rag/rebuild?full=true}）：新建一个集合把全部来源灌进去，
 *       成功后才切引用，中途失败线上索引原封不动。清单缺失/不属于当前集合/切分参数变了时也会自动走这条路。</li>
 * </ul>
 *
 * <p><b>为什么增量是原地更新、而不是「新建集合 + 从旧集合搬向量」</b>：原地更新不需要把几千条向量
 * 搬来搬去，代码也少一大截；代价是不再有「全有或全无」的切换，所以失败语义改成了
 * <b>按来源独立成/败</b> —— 某个来源更新失败时，它的旧片段原样留在索引里、清单也不更新，
 * 下次重建自动重试。这比现在「整批失败 = 白等 2 分钟且什么都没变」更好用。</p>
 *
 * <p><b>为什么不用 Milvus 的统计接口判断「集合是否已入库」</b>：
 * {@code getCollectionStatistics} 返回的 {@code row_count} <b>只统计已 flush 的数据</b>，
 * 而 {@code MilvusEmbeddingStore.addAll} 并不触发 flush —— 于是刚重建完的集合
 * 统计出来的行数是 0，重启时会被误判成「空集合」而<b>把整个知识库再灌一遍</b>。
 * 所以入库状态只认我们自己写的两个文件：{@code milvus-current-collection.txt}（集合名 + 段数）
 * 和 {@code milvus-index-manifest.txt}（每个来源的指纹），完全不依赖 Milvus 的内部行为。</p>
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

    /** 记录每个来源指纹的清单文件，增量更新的依据 */
    @Value("${milvus.manifest-file:milvus-index-manifest.txt}")
    private String manifestFilePath;

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
        // 知识片段只加载/切分一次：入库、BM25、差分三处共用同一份，避免结果对不上
        Loaded loaded = loadSources();
        List<TextSegment> segments = flatten(loaded.sources());
        IndexManifest manifest = loadManifest();

        CollectionMeta meta = loadMeta();
        boolean freshCollection = meta == null || meta.collectionName() == null
                || meta.collectionName().isBlank();
        currentCollectionName = freshCollection ? newCollectionName() : meta.collectionName();
        embeddingStore = buildStore(currentCollectionName);

        if (freshCollection) {
            log.info("新建集合 [{}]，开始入库...", currentCollectionName);
            ingestAll(embeddingStore, loaded.sources());
            manifest = buildManifest(currentCollectionName, loaded.sources());
            saveManifest(manifest);
        } else {
            log.info("复用集合 [{}]，跳过入库", currentCollectionName);
            warnIfOutOfSync(manifest, loaded);
        }

        // BM25 索引是纯内存的、进程重启就没了，所以必须无条件重建
        // （不能只在「入库」分支里建，否则复用上次集合时 BM25 会是空的）
        bm25Index.rebuild(segments);

        // 段数优先信清单（它记的是索引里真实的数量）；没有可用清单时退回本次切分的结果
        int indexSize = manifest != null && currentCollectionName.equals(manifest.collectionName())
                ? manifest.totalChunks()
                : segments.size();
        saveMeta(new CollectionMeta(currentCollectionName, indexSize));
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
     * @param full true 强制全量重建（新建集合 + 全量灌入）；false 走增量，只有变化的来源会重新 embedding
     * @return true 表示已提交；false 表示已有重建在进行中
     */
    public synchronized boolean triggerRebuild(boolean full) {
        if ("RUNNING".equals(rebuildStatus.getState())) {
            return false;
        }
        RebuildStatusVO running = new RebuildStatusVO();
        running.setState("RUNNING");
        running.setMessage(full ? "全量重建中" : "增量更新中");
        running.setStartTime(System.currentTimeMillis());
        this.rebuildStatus = running;

        rebuildExecutor.submit(() -> doRebuild(full));
        return true;
    }

    /**
     * 获取当前重建状态（供前端轮询）。
     */
    public RebuildStatusVO getRebuildStatus() {
        return rebuildStatus;
    }

    /**
     * 重建入口：先算差分，再决定走增量、全量，还是「什么都没变、直接返回」。
     */
    private void doRebuild(boolean full) {
        long start = System.currentTimeMillis();
        try {
            Loaded loaded = loadSources();
            if (full) {
                doFullRebuild(loaded, "手动指定全量重建", start);
                return;
            }

            IndexManifest prev = loadManifest();
            IndexDiff.Result diff = IndexDiff.compare(prev, currentCollectionName, chunkSize, overlap,
                    entryMap(loaded.sources()), new LinkedHashSet<>(loaded.failed()));
            if (diff.fullRequired()) {
                doFullRebuild(loaded, diff.reason(), start);
                return;
            }
            if (!diff.hasChanges()) {
                // 连 BM25 都不用重建：清单没变就意味着知识库文件没变，启动时建的 BM25 就是对的
                int total = totalSegments(loaded.sources());
                log.info("知识库无变化（{} 个来源 / {} 段），本次不做任何更新", loaded.sources().size(), total);
                success("知识库无变化，未做任何更新（跳过 " + total + " 段 embedding）", start, currentCollectionName);
                return;
            }
            doIncremental(prev, loaded, diff, start);
        } catch (Exception e) {
            failed(e, start);
        }
    }

    /**
     * 全量重建：新建集合 → 灌入全部来源 → 切引用。
     */
    private void doFullRebuild(Loaded loaded, String reason, long start) {
        String newName = newCollectionName();
        log.info("开始全量重建索引（原因：{}），新集合 [{}]", reason, newName);
        List<TextSegment> segments = flatten(loaded.sources());

        MilvusEmbeddingStore fresh = buildStore(newName);
        // 向量和 BM25 都先建好，成功后才切 live 引用；中途失败线上索引原封不动
        ingestAll(fresh, loaded.sources());
        bm25Index.rebuild(segments);

        IndexManifest manifest = buildManifest(newName, loaded.sources());
        this.currentCollectionName = newName;
        this.embeddingStore = fresh;
        saveManifest(manifest);
        saveMeta(new CollectionMeta(newName, manifest.totalChunks()));
        // 知识库已变，清空检索缓存，避免返回旧结果
        retrievalCache.clearAll();

        success("全量重建完成（" + reason + "）：重嵌 " + segments.size() + " 段", start, newName);
        log.info("全量重建完成，新集合 [{}]，耗时 {} ms", newName, System.currentTimeMillis() - start);
    }

    /**
     * 增量更新：只重灌变化的来源，其余来源的向量原地不动。
     *
     * <p>每个来源的顺序是「先 embedding（网络，最可能失败）→ 删旧片段 → 插新片段」：
     * embedding 失败时还没删任何东西，索引里保留旧内容，清单也不更新，下次重建自动重试。</p>
     *
     * <p>删除范围取「旧段数」和「新段数」的并集（见 {@link #deleteSource}），
     * 所以上一次跑到一半崩溃留下的残留片段也会被覆盖，重试一定收敛。</p>
     */
    private void doIncremental(IndexManifest prev, Loaded loaded, IndexDiff.Result diff, long start) {
        Map<IndexManifest.SourceKey, LoadedSource> byKey = new LinkedHashMap<>();
        loaded.sources().forEach(source -> byKey.put(source.key(), source));

        // 从旧清单出发逐个改：失败来源的条目自然保持原样，等价于「索引里还是旧内容」
        IndexManifest updated = prev;
        List<IndexManifest.SourceKey> failures = new ArrayList<>();

        int removed = 0;
        for (IndexManifest.SourceKey key : diff.removed()) {
            try {
                deleteSource(embeddingStore, key, prev.sources().get(key).chunkCount());
                updated = updated.without(key);
                removed++;
                log.info("来源已移除，删除其片段: {}", key.describe());
            } catch (Exception e) {
                failures.add(key);
                log.warn("删除来源失败，索引里保留旧内容、下次重建会重试 [{}]: {}", key.describe(), e.getMessage());
            }
        }

        int embedded = 0;
        for (IndexManifest.SourceKey key : reembedTargets(diff)) {
            LoadedSource source = byKey.get(key);
            int newCount = source.segments().size();
            try {
                // 先算向量：这一步失败概率最高（网络），失败时下面的删除还没发生
                List<Embedding> embeddings = embeddingModel.embedAll(source.segments()).content();
                int oldCount = prev.sources().containsKey(key) ? prev.sources().get(key).chunkCount() : 0;
                deleteSource(embeddingStore, key, Math.max(oldCount, newCount));
                embeddingStore.addAll(key.embeddingIds(newCount), embeddings, source.segments());
                updated = updated.with(key, new IndexManifest.SourceEntry(source.fingerprint(), newCount));
                embedded += newCount;
                log.info("来源已更新: {}（{} 段）", key.describe(), newCount);
            } catch (Exception e) {
                failures.add(key);
                log.warn("来源更新失败，索引里保留旧内容、清单不更新，下次重建会重试 [{}]: {}",
                        key.describe(), e.getMessage());
            }
        }

        // BM25 跟着重建：它是内存索引，没有「局部更新」这一说，好在只用本地正文、不发 embedding
        bm25Index.rebuild(flatten(loaded.sources()));
        if (!failures.isEmpty()) {
            log.warn("有 {} 个来源没更新成功：它们的旧内容还在向量库里，但 BM25 用的是本地新正文，"
                    + "两者会短暂不一致，直到下次重建重试成功", failures.size());
        }

        saveManifest(updated);
        saveMeta(new CollectionMeta(currentCollectionName, updated.totalChunks()));
        retrievalCache.clearAll();

        int skipped = totalSegments(loaded.sources()) - embedded;
        String message = "增量完成：新增 " + diff.added().size() + " / 变更 " + diff.changed().size()
                + " / 删除 " + removed + "；重嵌 " + embedded + " 段，跳过 " + skipped + " 段"
                + (failures.isEmpty() ? "" : "；" + failures.size() + " 个来源没更新成功（下次重建会重试）");
        success(message, start, currentCollectionName);
        log.info("增量更新完成，耗时 {} ms：{}", System.currentTimeMillis() - start, message);
    }

    private static List<IndexManifest.SourceKey> reembedTargets(IndexDiff.Result diff) {
        List<IndexManifest.SourceKey> targets = new ArrayList<>(diff.added());
        targets.addAll(diff.changed());
        return targets;
    }

    /**
     * 删除某个来源的前 {@code chunkCount} 个片段。
     *
     * <p>传进来的 {@code chunkCount} 取「旧段数」和「新段数」的较大值：主键是
     * 「来源 + 序号」确定性生成的，所以删除范围能直接算出来，走的是 {@code id in [...]}
     * 这条最基础的主键删除路径，不依赖 Milvus 的 JSON 字段过滤。</p>
     */
    private void deleteSource(EmbeddingStore<TextSegment> store, IndexManifest.SourceKey key, int chunkCount) {
        if (chunkCount <= 0) {
            return;
        }
        store.removeAll(key.embeddingIds(chunkCount));
    }

    private RebuildStatusVO idleStatus() {
        RebuildStatusVO idle = new RebuildStatusVO();
        idle.setState("IDLE");
        idle.setMessage("空闲");
        return idle;
    }

    private void success(String message, long start, String collectionName) {
        RebuildStatusVO ok = new RebuildStatusVO();
        ok.setState("SUCCESS");
        ok.setMessage(message);
        ok.setCollectionName(collectionName);
        ok.setStartTime(start);
        ok.setCostMillis(System.currentTimeMillis() - start);
        this.rebuildStatus = ok;
    }

    private void failed(Exception e, long start) {
        RebuildStatusVO failed = new RebuildStatusVO();
        failed.setState("FAILED");
        failed.setMessage(e.getMessage());
        failed.setStartTime(start);
        failed.setCostMillis(System.currentTimeMillis() - start);
        this.rebuildStatus = failed;
        log.error("重建索引失败", e);
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdown();
    }

    private String newCollectionName() {
        return baseCollectionName + "_" + System.currentTimeMillis();
    }

    /**
     * 建 store。一致性级别用 {@code BOUNDED}：增量更新是「先删后插」，
     * 库的默认值 {@code EVENTUALLY} 下紧接着的检索可能还看得到刚删掉的行。
     */
    private MilvusEmbeddingStore buildStore(String collectionName) {
        return MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .dimension(dimension)
                // text-embedding-v2 的向量已归一化，IP 等价于余弦相似度
                .metricType(MetricType.IP)
                // FLAT 是最基础的暴力检索索引，任何 Milvus 部署都支持
                .indexType(IndexType.FLAT)
                .consistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build();
    }

    private void ingestAll(MilvusEmbeddingStore store, List<LoadedSource> sources) {
        int total = 0;
        for (LoadedSource source : sources) {
            int count = source.segments().size();
            store.addAll(source.key().embeddingIds(count),
                    embeddingModel.embedAll(source.segments()).content(), source.segments());
            total += count;
            log.debug("入库: {}（{} 段）", source.key().describe(), count);
        }
        log.info("知识库入库完成，共 {} 个来源 / {} 段", sources.size(), total);
    }

    /**
     * 汇总知识库的全部来源：内置 classpath 文本 + 抓取的网页正文，并切分成片段。
     *
     * <p>每个来源都带来源元数据（网页用 {@code url}、内置文件用 {@code file_name}，
     * 都是 LangChain4j 的约定键），切分器会把它复制到每个片段上，
     * Milvus 自动存成 JSON 字段、检索时还原 —— 所以「这段知识出自哪」一路可查。</p>
     *
     * <p>只会加载一次、结果被复用：{@code webPageLoader.load()} 在没有缓存时是要联网的。</p>
     */
    private Loaded loadSources() {
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, overlap);
        Map<IndexManifest.SourceKey, LoadedSource> sources = new LinkedHashMap<>();

        for (String path : CLASSPATH_DOCS) {
            Document doc = Document.from(readClasspath(path), Metadata.from(Document.FILE_NAME, path));
            addSource(sources, new IndexManifest.SourceKey(Document.FILE_NAME, path), doc, splitter);
        }

        WebPageLoader.LoadResult web = webPageLoader.load();
        for (Document doc : web.documents()) {
            String url = doc.metadata().getString(Document.URL);
            addSource(sources, new IndexManifest.SourceKey(Document.URL, url), doc, splitter);
        }
        List<IndexManifest.SourceKey> failed = web.failedUrls().stream()
                .map(url -> new IndexManifest.SourceKey(Document.URL, url))
                .collect(Collectors.toList());

        log.info("知识库加载完成：{} 个来源 → {} 个片段（chunk={}, overlap={}），{} 个来源加载失败",
                sources.size(), totalSegments(sources.values()), chunkSize, overlap, failed.size());
        return new Loaded(List.copyOf(sources.values()), failed);
    }

    private static void addSource(Map<IndexManifest.SourceKey, LoadedSource> sources,
                                  IndexManifest.SourceKey key,
                                  Document doc,
                                  DocumentSplitter splitter) {
        if (sources.containsKey(key)) {
            log.warn("同一个来源被配置了多次，只保留第一次: {}", key.describe());
            return;
        }
        // 指纹取切分前的正文：只随内容变，不受 chunk/overlap 影响（切分参数单独记在清单里）
        sources.put(key, new LoadedSource(key, IndexManifest.fingerprint(doc.text()), splitter.split(doc)));
    }

    private static List<TextSegment> flatten(List<LoadedSource> sources) {
        return sources.stream()
                .flatMap(source -> source.segments().stream())
                .collect(Collectors.toList());
    }

    private static Map<IndexManifest.SourceKey, IndexManifest.SourceEntry> entryMap(List<LoadedSource> sources) {
        Map<IndexManifest.SourceKey, IndexManifest.SourceEntry> map = new LinkedHashMap<>();
        for (LoadedSource source : sources) {
            map.put(source.key(), new IndexManifest.SourceEntry(source.fingerprint(), source.segments().size()));
        }
        return map;
    }

    private static int totalSegments(Collection<LoadedSource> sources) {
        return sources.stream().mapToInt(source -> source.segments().size()).sum();
    }

    private IndexManifest buildManifest(String collectionName, List<LoadedSource> sources) {
        return new IndexManifest(collectionName, chunkSize, overlap, entryMap(sources));
    }

    /**
     * 启动时对一次账：知识库文件是不是和索引一致（离线，只读本地缓存）。
     *
     * <p>替代了原先「比 meta 文件里的段数」的做法 —— 增量更新后段数本来就会变，
     * 那个信号已经失真；比指纹既精确，还能说清是新增还是变更。</p>
     */
    private void warnIfOutOfSync(IndexManifest manifest, Loaded loaded) {
        IndexDiff.Result diff = IndexDiff.compare(manifest, currentCollectionName, chunkSize, overlap,
                entryMap(loaded.sources()), new LinkedHashSet<>(loaded.failed()));
        if (diff.fullRequired()) {
            log.warn("下次 POST /rag/rebuild 会做全量重建：{}", diff.reason());
        } else if (diff.hasChanges()) {
            log.warn("知识库与索引不一致（新增 {} / 变更 {} / 删除 {}），调 POST /rag/rebuild 做增量更新",
                    diff.added().size(), diff.changed().size(), diff.removed().size());
        } else {
            log.info("知识库与索引一致：{} 个来源 / {} 段",
                    loaded.sources().size(), manifest.totalChunks());
        }
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
     * 读来源清单。文件不存在或读不出来时返回 null（调用方按「必须全量重建」处理）。
     */
    private IndexManifest loadManifest() {
        try {
            Path p = Paths.get(manifestFilePath);
            if (!Files.exists(p)) {
                return null;
            }
            return IndexManifest.parse(Files.readString(p));
        } catch (IOException e) {
            log.warn("读取来源清单失败，下次重建将走全量: {}", e.getMessage());
            return null;
        }
    }

    private void saveManifest(IndexManifest manifest) {
        try {
            Files.writeString(Paths.get(manifestFilePath), manifest.format());
        } catch (IOException e) {
            log.warn("写入来源清单失败（下次重建会退化成全量）: {}", e.getMessage());
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

    /**
     * 一个已加载的来源：来源标识 + 正文指纹 + 切好的片段。
     */
    private record LoadedSource(IndexManifest.SourceKey key, String fingerprint, List<TextSegment> segments) {
    }

    /**
     * 一次加载的全部结果：成功的来源 + 加载失败的来源。
     */
    private record Loaded(List<LoadedSource> sources, List<IndexManifest.SourceKey> failed) {
    }
}
