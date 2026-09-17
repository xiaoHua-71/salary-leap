package com.xiaohua.service;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库来源清单：记录「当前索引里有哪些来源、各自是什么指纹」。
 *
 * <p>增量更新的唯一依据。重建时拿当前知识库和清单比一次，<b>指纹没变就不重新 embedding</b>，
 * 于是「改一个页面」的代价从「全量 2929 段约 115 秒」降到「该页面 30 段约 1 秒」。</p>
 *
 * <p>文件格式（{@code milvus-index-manifest.txt}）：</p>
 * <pre>
 * collection=level_knowledge_1789208288062
 * chunk=500
 * overlap=100
 * source|url|https://javaguide.cn/java/basis/proxy.html|&lt;md5&gt;|30
 * source|file_name|knowledge/java-backend.txt|&lt;md5&gt;|12
 * </pre>
 *
 * <p><b>为什么指纹取的是「切分前的正文」而不是片段</b>：正文哈希只随内容变，
 * 所以切分参数（chunk/overlap）变了指纹不会变 —— 那就必须在清单里单独记下切分参数，
 * 不然「改了 chunk 大小但指纹一致」会被误判成「无变化」，索引里留着旧边界的片段。
 * 集合名同理：清单可能属于上一个集合，套用之前先比集合名。</p>
 *
 * <p>字段用 {@code |} 分隔：URL 和 classpath 路径里不会出现这个字符（URL 会编码成 {@code %7C}），
 * 所以不需要转义。字段数不是 5 的行按「读不出来」跳过并告警。</p>
 */
@Slf4j
public record IndexManifest(String collectionName,
                            int chunkSize,
                            int overlap,
                            Map<SourceKey, SourceEntry> sources) {

    private static final String FIELD = "|";

    /** 清单行前缀，用来和 {@code key=value} 行区分 */
    private static final String SOURCE_LINE = "source";

    public IndexManifest {
        // 保留插入顺序：清单文件进 git，顺序稳定才好 diff
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    /**
     * 来源标识：{@code key} 是 LangChain4j 的元数据键（网页 {@code url}、内置文件 {@code file_name}），
     * {@code value} 是具体地址。
     *
     * <p>为什么要连 key 一起记：按来源删除片段时要用它拼 Milvus 的过滤条件，
     * 而「这个来源用的是 url 还是 file_name」只有入库时知道，事后靠猜（比如看字符串像不像网址）不可靠。</p>
     */
    public record SourceKey(String key, String value) {

        /**
         * 该来源第 {@code index} 个片段的主键。
         *
         * <p><b>为什么主键要确定性生成而不是随机 UUID</b>：随机主键的话「按来源删旧片段」
         * 只能靠 Milvus 的 JSON 字段表达式过滤；而确定性主键（来源 + 序号）能直接算出要删哪些 id，
         * 走 {@code id in [...]} 这条最基础的主键删除路径 —— 不依赖 JSON 过滤、不依赖一致性级别的细节。</p>
         *
         * <p>{@code nameUUIDFromBytes} 是 MD5 派生的 v3 UUID，跨进程、跨机器稳定；
         * 结果固定 36 字符，正好等于集合 schema 里主键字段 {@code VarChar(36)} 的上限。</p>
         */
        public String embeddingId(int index) {
            String seed = key + FIELD + value + "#" + index;
            return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
        }

        /**
         * 该来源第 {@code 0 .. count-1} 个片段的主键列表。
         */
        public List<String> embeddingIds(int count) {
            List<String> ids = new ArrayList<>(Math.max(count, 0));
            for (int i = 0; i < count; i++) {
                ids.add(embeddingId(i));
            }
            return ids;
        }

        /** 日志里用的可读描述，如 {@code url=https://...} */
        public String describe() {
            return key + "=" + value;
        }
    }

    /**
     * 一个来源的指纹与片段数。
     *
     * <p>{@code chunkCount} 有两个用途：算索引总段数（写进 collection-meta 文件）、
     * 以及算出「该来源旧片段的主键范围」用于删除。</p>
     */
    public record SourceEntry(String fingerprint, int chunkCount) {
    }

    /**
     * 正文指纹：MD5 十六进制串。
     *
     * <p>用 MD5 不是为了防碰撞攻击，只是要一个稳定的短摘要（32 字符）判断「内容是否变过」；
     * 真出现碰撞的后果也只是「漏掉一次重建」，而知识库的内容不是对抗性输入。</p>
     */
    public static String fingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必须提供的算法，走不到这里
            throw new IllegalStateException("JDK 不支持 MD5", e);
        }
    }

    /**
     * 替换/新增一个来源，返回新清单（record 不可变）。
     */
    public IndexManifest with(SourceKey key, SourceEntry entry) {
        Map<SourceKey, SourceEntry> merged = new LinkedHashMap<>(sources);
        merged.put(key, entry);
        return new IndexManifest(collectionName, chunkSize, overlap, merged);
    }

    /**
     * 移除一个来源，返回新清单。
     */
    public IndexManifest without(SourceKey key) {
        Map<SourceKey, SourceEntry> remaining = new LinkedHashMap<>(sources);
        remaining.remove(key);
        return new IndexManifest(collectionName, chunkSize, overlap, remaining);
    }

    /**
     * 索引里当前的片段总数 = 各来源片段数之和。
     *
     * <p>比「重新切一遍当前知识库有多少段」更可信：更新失败的来源保留的是<b>旧</b>片段数，
     * 累加出来正好是向量库里真实的数量。</p>
     */
    public int totalChunks() {
        return sources.values().stream().mapToInt(SourceEntry::chunkCount).sum();
    }

    /**
     * 解析清单内容。读不出来的行跳过并告警，不抛异常 —— 清单坏了不该让应用起不来，
     * 最多退化成「下次全量重建」。
     */
    public static IndexManifest parse(String content) {
        String collection = null;
        int chunkSize = 0;
        int overlap = 0;
        Map<SourceKey, SourceEntry> sources = new LinkedHashMap<>();

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(SOURCE_LINE + FIELD)) {
                parseSourceLine(line, sources);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "collection" -> collection = value;
                case "chunk" -> chunkSize = parseIntOrZero(value, key);
                case "overlap" -> overlap = parseIntOrZero(value, key);
                default -> log.debug("清单里有无法识别的键，已忽略: {}", key);
            }
        }
        return new IndexManifest(collection, chunkSize, overlap, sources);
    }

    private static void parseSourceLine(String line, Map<SourceKey, SourceEntry> sources) {
        String[] fields = line.split("\\" + FIELD, -1);
        if (fields.length != 5) {
            log.warn("来源清单里有格式不对的行（字段数 {}，期望 5），已跳过；"
                    + "该来源会被当成新增来源重新入库，可能残留旧片段: {}", fields.length, line);
            return;
        }
        String fingerprint = fields[3].trim();
        int chunkCount = parseIntOrZero(fields[4].trim(), "chunkCount");
        if (fingerprint.isEmpty() || chunkCount <= 0) {
            log.warn("来源清单里的来源缺少指纹或片段数，已跳过: {}", line);
            return;
        }
        SourceKey key = new SourceKey(fields[1].trim(), fields[2].trim());
        sources.put(key, new SourceEntry(fingerprint, chunkCount));
    }

    private static int parseIntOrZero(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // 读坏了就当未知：切分参数为 0 会和当前配置不符 → 差分判定「不能增量」，退化成全量重建
            log.warn("来源清单里的 {} 无法解析，按未知处理: {}", field, value);
            return 0;
        }
    }

    /**
     * 序列化成文件内容。与 {@link #parse} 互为逆操作。
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("collection=").append(collectionName == null ? "" : collectionName).append('\n');
        sb.append("chunk=").append(chunkSize).append('\n');
        sb.append("overlap=").append(overlap).append('\n');
        for (Map.Entry<SourceKey, SourceEntry> entry : sources.entrySet()) {
            SourceKey key = entry.getKey();
            SourceEntry value = entry.getValue();
            sb.append(SOURCE_LINE).append(FIELD)
                    .append(key.key()).append(FIELD)
                    .append(key.value()).append(FIELD)
                    .append(value.fingerprint()).append(FIELD)
                    .append(value.chunkCount()).append('\n');
        }
        return sb.toString();
    }
}
