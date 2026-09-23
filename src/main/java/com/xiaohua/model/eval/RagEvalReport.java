package com.xiaohua.model.eval;

import java.util.List;

/**
 * 一次离线评估的完整报告。
 *
 * <p>落盘成 {@code report.json}（机器可读，下次跑时读它算差值）+ {@code report.md}（人读）。</p>
 *
 * @param runId          本次运行 ID（时间戳目录名），也是下次比对的锚点
 * @param startTime      开始时间（epoch 毫秒）
 * @param costMillis     总耗时
 * @param collectionName 开始时的索引集合名。它和中途重建后的集合名不一致，
 *                       就说明这批指标前后不可比（见 {@code RagEvalService}）
 * @param collectionChanged 评估期间索引被重建过
 * @param cacheDisabled  是否绕开了检索缓存。恒为 true —— 写在报告里是为了让读的人
 *                       确认这批数字没被缓存污染（变体之间如果互相命中缓存，指标全是错的）
 * @param evalSetFile    用的哪份评估集
 * @param caseCount      参与评估的用例数
 * @param previousRunId  上一次运行的 ID；没有可比的上一份时为 null
 * @param variants       各变体的结果
 */
public record RagEvalReport(String runId,
                            long startTime,
                            long costMillis,
                            String collectionName,
                            boolean collectionChanged,
                            boolean cacheDisabled,
                            String evalSetFile,
                            int caseCount,
                            String previousRunId,
                            List<VariantReport> variants) {
}
