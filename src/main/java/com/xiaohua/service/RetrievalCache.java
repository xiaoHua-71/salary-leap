package com.xiaohua.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 检索结果缓存：相同查询（学习方向）不重复「向量化 + 向量库检索」。
 *
 * <p>向量化要调 DashScope、检索要调 Milvus，相同查询重复调用纯属浪费。
 * 用本地 Caffeine 缓存，带 TTL + 容量上限。</p>
 *
 * <p>注意：重建索引后知识库变了，缓存必须清空（由 {@code RagIndexService} 调用 {@link #clearAll()}）。</p>
 */
@Component
@Slf4j
public class RetrievalCache {

    private final Cache<String, String> cache;

    public RetrievalCache(@Value("${rag.cache.ttl-minutes:30}") long ttlMinutes,
                          @Value("${rag.cache.max-size:1000}") long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .build();
    }

    /**
     * 读缓存，未命中返回 null。
     */
    public String get(String query) {
        return cache.getIfPresent(query);
    }

    /**
     * 写缓存。只缓存非空结果——空结果（无命中）或失败不缓存，
     * 避免把「暂时性的检索失败」固化下来、掩盖故障恢复。
     */
    public void put(String query, String result) {
        if (result != null && !result.isBlank()) {
            cache.put(query, result);
        }
    }

    /**
     * 清空缓存（重建索引后调用）。
     */
    public void clearAll() {
        cache.invalidateAll();
        log.info("检索缓存已清空");
    }
}
