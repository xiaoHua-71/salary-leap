package com.xiaohua.service;

import com.xiaohua.model.ai.RewriteResult;
import com.xiaohua.service.ai.QueryRewriteAiService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询改写（多查询扩展）：把「学习方向」标签扩展成若干条具体的技术主题查询。
 *
 * <p>为什么要它：检索的输入是极短的标签（如「Java后端开发」），既表达不出这个方向
 * 该覆盖的知识面，向量检索对这种短标签的区分度也很差 —— 实测「Java后端开发」的
 * top-20 分数全挤在 0.82~0.84，知识库里明明有 JVM/集合/并发等文章却排不上来。
 * 扩展成「Java 集合与泛型」「JVM 内存与垃圾回收」这类具体主题后，每条各自召回再融合，
 * 就能把这些文章捞出来。</p>
 *
 * <p><b>原查询永远排在第一位</b>，且任何失败路径都返回「只有原查询」这一个列表 ——
 * 也就是改造前的行为。所以开启改写只会持平或更好，不会更差。</p>
 */
@Component
@Slf4j
public class QueryRewriter {

    @Resource
    private QueryRewriteAiService queryRewriteAiService;

    /** 是否开启查询改写 */
    @Value("${rag.rewrite.enabled:true}")
    private boolean enabled = true;

    /** 最多扩展出几条子查询（不含原查询） */
    @Value("${rag.rewrite.count:4}")
    private int count = 4;

    /**
     * 改写查询。
     *
     * @param query 原始查询（学习方向标签）
     * @return 待召回的查询列表，**第一条一定是原查询**；关闭或失败时只有原查询
     */
    public List<String> rewrite(String query) {
        Set<String> queries = new LinkedHashSet<>();
        queries.add(query);
        if (!enabled || count <= 0) {
            return new ArrayList<>(queries);
        }
        try {
            RewriteResult result = queryRewriteAiService.rewrite(query, count);
            if (result == null || result.getQueries() == null) {
                log.warn("查询改写返回空结果，退回原查询");
                return new ArrayList<>(queries);
            }
            for (String candidate : result.getQueries()) {
                if (queries.size() > count) {
                    break;
                }
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                // LinkedHashSet 顺带完成去重（含与原查询重复的情况）
                queries.add(candidate.trim());
            }
            if (queries.size() > 1) {
                log.info("查询改写：{} → {} 条", query, queries.size());
            }
        } catch (Exception e) {
            // 降级：改写挂了不影响检索，退回原查询即可
            log.warn("查询改写失败，退回原查询: {}", e.getMessage());
        }
        return new ArrayList<>(queries);
    }
}
