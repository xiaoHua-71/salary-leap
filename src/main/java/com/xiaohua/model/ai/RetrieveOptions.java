package com.xiaohua.model.ai;

import java.util.StringJoiner;

/**
 * 一次检索的全部可调参数。
 *
 * <p>为什么要有这个对象：这些参数原本是 {@code KnowledgeService} 里一串 {@code @Value} 字段，
 * 进程启动就定死，想对比「开 rerank / 关 rerank」只能改 yml 重启。抽成参数对象后，
 * 同一批查询可以用不同的开关组合各跑一遍，横向比出每个改造到底贡献了多少 ——
 * 这是离线评估能成立的前提。</p>
 *
 * <p>生产路径不受影响：{@code KnowledgeService.defaultOptions()} 把 {@code @Value} 组装成一份默认值，
 * {@code retrieve(query)} 用的就是它。</p>
 *
 * <p>各字段含义见 {@code knowledge/23-检索链路与配置总览.md}（与 yml 里的键一一对应）。</p>
 *
 * @param rewrite      是否开启查询改写
 * @param rewriteCount 最多扩展出几条子查询（不含原查询）
 * @param hybrid       是否开启混合检索（BM25 + 向量双路召回）
 * @param bm25Recall   BM25 这一路召回多少候选
 * @param rrfK         RRF 平滑常数，越大越削弱第一名的优势
 * @param maxCandidates 融合去重后、送进 rerank 前的候选上限
 * @param rerank       是否开启 rerank 精排
 * @param vectorRecall 向量这一路召回多少候选
 * @param topK         最终交付的片段数
 * @param maxPerSource 同一来源最多占 topK 里的几个位置；{@code <= 0} 表示不限制
 */
public record RetrieveOptions(boolean rewrite,
                              int rewriteCount,
                              boolean hybrid,
                              int bm25Recall,
                              int rrfK,
                              int maxCandidates,
                              boolean rerank,
                              int vectorRecall,
                              int topK,
                              int maxPerSource) {

    public RetrieveOptions withRewrite(boolean value) {
        return new RetrieveOptions(value, rewriteCount, hybrid, bm25Recall, rrfK, maxCandidates,
                rerank, vectorRecall, topK, maxPerSource);
    }

    public RetrieveOptions withRewriteCount(int value) {
        return new RetrieveOptions(rewrite, value, hybrid, bm25Recall, rrfK, maxCandidates,
                rerank, vectorRecall, topK, maxPerSource);
    }

    public RetrieveOptions withHybrid(boolean value) {
        return new RetrieveOptions(rewrite, rewriteCount, value, bm25Recall, rrfK, maxCandidates,
                rerank, vectorRecall, topK, maxPerSource);
    }

    public RetrieveOptions withBm25Recall(int value) {
        return new RetrieveOptions(rewrite, rewriteCount, hybrid, value, rrfK, maxCandidates,
                rerank, vectorRecall, topK, maxPerSource);
    }

    public RetrieveOptions withRrfK(int value) {
        return new RetrieveOptions(rewrite, rewriteCount, hybrid, bm25Recall, value, maxCandidates,
                rerank, vectorRecall, topK, maxPerSource);
    }

    public RetrieveOptions withMaxCandidates(int value) {
        return new RetrieveOptions(rewrite, rewriteCount, hybrid, bm25Recall, rrfK, value,
                rerank, vectorRecall, topK, maxPerSource);
    }

    public RetrieveOptions withRerank(boolean value) {
        return new RetrieveOptions(rewrite, rewriteCount, hybrid, bm25Recall, rrfK, maxCandidates,
                value, vectorRecall, topK, maxPerSource);
    }

    public RetrieveOptions withVectorRecall(int value) {
        return new RetrieveOptions(rewrite, rewriteCount, hybrid, bm25Recall, rrfK, maxCandidates,
                rerank, value, topK, maxPerSource);
    }

    public RetrieveOptions withTopK(int value) {
        return new RetrieveOptions(rewrite, rewriteCount, hybrid, bm25Recall, rrfK, maxCandidates,
                rerank, vectorRecall, value, maxPerSource);
    }

    public RetrieveOptions withMaxPerSource(int value) {
        return new RetrieveOptions(rewrite, rewriteCount, hybrid, bm25Recall, rrfK, maxCandidates,
                rerank, vectorRecall, topK, value);
    }

    /**
     * 一行可读描述，写进评估报告，让人知道这组指标是哪套参数跑出来的。
     */
    public String describe() {
        StringJoiner joiner = new StringJoiner(", ");
        joiner.add("改写=" + (rewrite ? "开(" + rewriteCount + ")" : "关"));
        joiner.add("混合=" + (hybrid ? "开(bm25 " + bm25Recall + ")" : "关"));
        joiner.add("向量召回=" + vectorRecall);
        joiner.add("rerank=" + (rerank ? "开" : "关"));
        joiner.add("topK=" + topK);
        joiner.add("每源上限=" + (maxPerSource <= 0 ? "不限" : String.valueOf(maxPerSource)));
        return joiner.toString();
    }
}
