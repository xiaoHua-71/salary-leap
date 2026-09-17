package com.xiaohua.service.loader;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页文档加载器：把配置里的 URL 列表抓成带来源的 {@link Document}，交给
 * {@code RagIndexService} 切分入库。
 *
 * <p>为什么要它：知识库只有几个 txt 时，向量一路就把全部片段召回回来了，
 * 混合检索、rerank 都看不出差别。把真实网页正文灌进去、片段数上到几百条，
 * 向量召回的固定条数才会真正「漏」，检索优化的收益才可观测。</p>
 *
 * <p>两个刻意的设计：</p>
 * <ul>
 *   <li><b>只抓给定 URL，不跟链接爬取</b>：行为可预测、可复现，不会失控爬到别人站点上。</li>
 *   <li><b>抓回来的正文落盘缓存</b>：重复重建不打目标站点、结果可复现、可人工审阅、能进 git。
 *       想重新抓某个页面，删掉对应的缓存文件即可。</li>
 * </ul>
 *
 * <p>注意类名冲突：jsoup 也有一个 {@code Document}。本类 import 的是 LangChain4j 的，
 * jsoup 的那个只在 {@link #cleanHtml} 里以 {@code var} 局部使用，不写出类型名。</p>
 *
 * <p><b>为什么用 {@code @ConfigurationProperties} 而不是 {@code @Value}</b>：
 * {@code @Value} 解析不了 YAML 列表 —— YAML 列表在属性源里是 {@code rag.web.urls[0]}、
 * {@code rag.web.urls[1]} 这种带下标的键，直接取 {@code rag.web.urls} 取不到，
 * 给了默认值 {@code :} 的话会<b>静默退化成空列表</b>（不报错，只是永远抓不到东西），
 * 排查起来很费时间。列表一律走 {@code @ConfigurationProperties} 绑定。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.web")
@Getter
@Setter
@Slf4j
public class WebPageLoader {

    /** 缓存文件头，记录来源 URL */
    private static final String SOURCE_PREFIX = "source: ";

    /** 缓存文件名里保留的 URL 片段长度上限 */
    private static final int NAME_LIMIT = 60;

    /** 正文容器候选，按优先级依次尝试 */
    private static final String[] CONTENT_SELECTORS = {"article", "main", "[role=main]"};

    /** 视为「一个段落」的块级元素 */
    private static final String BLOCK_SELECTOR = "h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,dd,dt,td,th";

    /** 与正文无关的噪声节点 */
    private static final String NOISE_SELECTOR = "script,style,noscript,nav,header,footer,aside,iframe,form,svg";

    /** 是否抓取网页入库 */
    private boolean enabled = true;

    /** 要抓取的页面地址列表（rag.web.urls） */
    private List<String> urls = List.of();

    /** 正文缓存目录 */
    private String cacheDir = "docs/web";

    private int timeoutMs = 10000;

    /** 相邻两次抓取之间的间隔，避免给目标站点压力 */
    private long delayMs = 500;

    private String userAgent = "Mozilla/5.0 (compatible; salary-leap-rag/1.0)";

    /**
     * 把 URL 列表加载成文档列表。优先读缓存，没有缓存才联网抓。
     *
     * <p>单个 URL 失败只记 warn 并跳过，不打断整批 —— 一个页面挂掉不该让整次入库失败。</p>
     *
     * <p><b>失败清单要单独交回去</b>：增量更新靠「清单里有、这次没加载出来」判断来源被删除，
     * 如果分不清「页面被删了」和「这次没抓到」，网络一抖就会把索引里的正文删掉。</p>
     *
     * @return 加载结果：带来源元数据的文档 + 加载失败的 URL；未启用或列表为空时两者皆空
     */
    public LoadResult load() {
        if (!enabled) {
            log.info("网页抓取已关闭（rag.web.enabled=false），跳过");
            return new LoadResult(List.of(), List.of());
        }
        if (urls == null || urls.isEmpty()) {
            log.info("网页抓取未配置（rag.web.urls 为空），跳过");
            return new LoadResult(List.of(), List.of());
        }

        Path dir = Paths.get(cacheDir);
        List<Document> documents = new ArrayList<>();
        List<String> failedUrls = new ArrayList<>();
        boolean fetchedAny = false;
        for (String rawUrl : urls) {
            String url = rawUrl == null ? "" : rawUrl.trim();
            if (url.isEmpty()) {
                continue;
            }
            try {
                Path cacheFile = dir.resolve(cacheFileName(url));
                String source;
                String body;
                if (Files.exists(cacheFile)) {
                    CachedPage cached = parseCache(Files.readString(cacheFile, StandardCharsets.UTF_8), url);
                    source = cached.source();
                    body = cached.body();
                    log.info("网页缓存命中: {}", source);
                } else {
                    if (fetchedAny) {
                        sleepBetweenFetches();
                    }
                    body = fetch(url);
                    writeCache(cacheFile, url, body);
                    source = url;
                    fetchedAny = true;
                    log.info("网页抓取完成: {} → {} 字", url, body.length());
                }
                if (body.isBlank()) {
                    log.warn("页面正文为空，跳过: {}", source);
                    failedUrls.add(url);
                    continue;
                }
                documents.add(toDocument(source, body));
            } catch (Exception e) {
                log.warn("网页加载失败，跳过该 URL [{}]: {}", url, e.getMessage());
                failedUrls.add(url);
            }
        }
        log.info("网页文档加载完成：{}/{} 个 URL 成功", documents.size(), urls.size());
        return new LoadResult(documents, failedUrls);
    }

    /**
     * 联网抓取并清洗正文。
     */
    private String fetch(String url) throws IOException {
        String html = Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeoutMs)
                .followRedirects(true)
                .get()
                .html();
        return cleanHtml(html);
    }

    /**
     * 清洗正文。入参是 HTML 字符串，所以单测可以完全不联网地验证提取质量。
     */
    static String cleanHtml(String html) {
        var page = Jsoup.parse(html);
        // 这里是有意从 DOM 里摘掉噪声节点（jsoup 的 Elements.remove 会连带从文档中移除）
        page.select(NOISE_SELECTOR).remove();
        Element root = findContentRoot(page);

        List<String> lines = new ArrayList<>();
        for (Element block : root.select(BLOCK_SELECTOR)) {
            if (!hasBlockDescendant(block)) {
                String text = block.text();
                if (!text.isBlank()) {
                    lines.add(text);
                }
            }
        }
        return lines.isEmpty() ? root.text().trim() : String.join("\n", lines);
    }

    /**
     * 是否含块级「子孙」元素。
     *
     * <p>为什么要单独判断：jsoup 的 {@code select} <b>会把元素自身也算作匹配</b>，
     * 所以不能直接拿 {@code block.select(BLOCK_SELECTOR).isEmpty()} 当「有没有块级子孙」；
     * 更不能用 {@code Elements.removeIf} 过滤 —— 它会连带把元素从 DOM 里摘掉，
     * 结果是把整个正文清空。</p>
     */
    private static boolean hasBlockDescendant(Element block) {
        for (Element element : block.getAllElements()) {
            if (element != block && element.is(BLOCK_SELECTOR)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启发式正文提取：页面结构千差万别，不追求完美，只求「噪声少、段落结构还在」。
     *
     * <p>关键点是<b>按块级元素取文本并用换行连接</b>，而不是 {@code body().text()} ——
     * 后者会把整页压成一行，后面的切分器只能按字符硬切，片段质量很差。</p>
     */
    private static Element findContentRoot(Element page) {
        for (String selector : CONTENT_SELECTORS) {
            Element candidate = page.selectFirst(selector);
            if (candidate != null) {
                return candidate;
            }
        }
        Element body = page.selectFirst("body");
        return body != null ? body : page;
    }

    /**
     * URL → 缓存文件名。非字母数字一律替换成下划线，因此不可能出现 {@code /} 或 {@code ..}，
     * 天然杜绝路径穿越；截断后用整串 URL 的哈希兜底，避免不同 URL 截断后撞名。
     */
    static String cacheFileName(String url) {
        String sanitized = url.replaceAll("[^A-Za-z0-9]+", "_");
        if (sanitized.length() > NAME_LIMIT) {
            sanitized = sanitized.substring(0, NAME_LIMIT);
        }
        return sanitized + "_" + Integer.toHexString(url.hashCode()) + ".txt";
    }

    /**
     * 解析缓存文件。以文件头 {@code source:} 为准还原来源，
     * 这样缓存文件改个名字、挪个位置也不会丢出处。
     */
    static CachedPage parseCache(String content, String fallbackSource) {
        if (content.startsWith(SOURCE_PREFIX)) {
            int lineEnd = content.indexOf('\n');
            if (lineEnd > SOURCE_PREFIX.length()) {
                String source = content.substring(SOURCE_PREFIX.length(), lineEnd).trim();
                String body = content.substring(lineEnd + 1).trim();
                return new CachedPage(source.isEmpty() ? fallbackSource : source, body);
            }
        }
        return new CachedPage(fallbackSource, content.trim());
    }

    /**
     * 组装文档并打上来源。用 LangChain4j 约定的 {@link Document#URL} 键，
     * 而不是自造键名 —— 以后接元数据过滤器、引用展示时能直接复用现成约定。
     */
    static Document toDocument(String url, String body) {
        return Document.from(body, Metadata.from(Document.URL, url));
    }

    private void writeCache(Path file, String url, String body) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, buildCacheContent(url, body), StandardCharsets.UTF_8);
    }

    /** 缓存文件格式：来源文件头 + 空行 + 正文。与 {@link #parseCache} 互为逆操作。 */
    static String buildCacheContent(String url, String body) {
        return SOURCE_PREFIX + url + "\n\n" + body;
    }

    private void sleepBetweenFetches() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 缓存内容：来源 + 正文 */
    record CachedPage(String source, String body) {
    }

    /**
     * 加载结果：成功拿出来正文的文档 + 没拿到的 URL。
     *
     * <p>{@code failedUrls} 不能丢 —— 增量更新要靠它区分「来源被移除了」和「这次没抓到」。</p>
     */
    public record LoadResult(List<Document> documents, List<String> failedUrls) {
    }
}
