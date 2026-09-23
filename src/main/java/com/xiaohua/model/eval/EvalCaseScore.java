package com.xiaohua.model.eval;

import java.util.List;

/**
 * 一条用例算完分的结果 —— 也就是报告里的**一行明细**。
 *
 * <p>核心是 {@link #notInIndex()} 和 {@link #missed()} 这一对：它们回答的是项目踩过两次的
 * 那个问题 ——「检索效果不好」到底是<b>库里没有这篇文章</b>，还是<b>有但没捞到</b>。
 * 前者只能补数据，后者才轮到调检索。分不清这两件事，就会一直在检索技巧上白费力气。</p>
 *
 * @param caseId          用例标识
 * @param query           查询文本
 * @param expectedSources 期望命中的来源
 * @param returnedSources 实际返回片段的来源（按名次）
 * @param hitExpected     被命中的期望来源
 * @param notInIndex      期望但<b>根本不在索引里</b>的来源 → 数据的锅，得补数据
 * @param missed          在索引里但<b>没进 topK</b> 的来源 → 检索的锅，才轮到调检索
 * @param hitRank         第一个命中片段的名次（1 开始）；未命中为 0
 * @param hitCount        返回片段里命中期望来源的条数
 * @param returnedCount   实际返回的片段数
 * @param distinctSources 实际返回的**不同来源**数
 * @param costMillis      本条耗时（毫秒）
 * @param error           失败原因；正常为 null
 */
public record EvalCaseScore(String caseId,
                            String query,
                            List<String> expectedSources,
                            List<String> returnedSources,
                            List<String> hitExpected,
                            List<String> notInIndex,
                            List<String> missed,
                            int hitRank,
                            int hitCount,
                            int returnedCount,
                            int distinctSources,
                            long costMillis,
                            String error) {

    /** 是否命中至少一个期望来源 */
    public boolean hit() {
        return hitRank > 0;
    }
}
