package com.xiaohua.model.eval;

/**
 * 一组用例的整体指标（一个变体算一份）。
 *
 * <p>字段含义与「回答什么问题」的对照表见 {@code knowledge/25-评估集与指标.md}，
 * 这里只强调两条容易踩的：</p>
 * <ul>
 *   <li>{@link #diversity()} <b>不能单独看</b>：少返回几条就能把多样度刷上去，
 *       所以必须和 {@link #avgReturned()} 同屏对比，只在返回数相同的变体之间比多样度。</li>
 *   <li>{@link #emptyRate()} 高说明链路整体失灵（比如 DashScope 挂了），
 *       这时别急着分析命中率 —— 数字没有意义。</li>
 * </ul>
 *
 * @param total       参与统计的用例数
 * @param failed      抛出异常的用例数（这些用例按未命中计入命中率）
 * @param hitRate     命中率：至少命中一个期望来源的用例占比
 * @param mrr         平均倒数排名：命中排得越靠前越高，未命中记 0
 * @param recall      召回率：所有期望来源里被覆盖的比例（多个期望来源时才有意义）
 * @param precision   准确率：返回的片段里命中期望来源的比例
 * @param diversity   来源多样度：不同来源数 / 返回片段数，量化「同一篇文章占几个格子」
 * @param avgReturned 平均返回片段数
 * @param emptyRate   空结果率
 * @param p50Millis   耗时中位数
 * @param p95Millis   耗时 P95
 */
public record EvalMetrics(int total,
                          int failed,
                          double hitRate,
                          double mrr,
                          double recall,
                          double precision,
                          double diversity,
                          double avgReturned,
                          double emptyRate,
                          long p50Millis,
                          long p95Millis) {
}
