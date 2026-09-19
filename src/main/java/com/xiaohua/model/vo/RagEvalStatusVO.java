package com.xiaohua.model.vo;

import lombok.Data;

/**
 * 离线评估的进度状态。
 *
 * <p>评估要跑几百次网络调用、通常几分钟，所以做成异步 + 轮询，和知识库重建同一套模式
 * （见 {@link RebuildStatusVO}）。进度里带上「第几个变体 / 第几条用例」是因为
 * 干等几分钟却不知道卡在哪，比等还难受。</p>
 */
@Data
public class RagEvalStatusVO {

    /**
     * 状态：IDLE（空闲）/ RUNNING（评估中）/ SUCCESS（成功）/ FAILED（失败）
     */
    private String state;

    /**
     * 说明信息（失败时为错误原因）
     */
    private String message;

    /** 本次运行 ID（报告目录名） */
    private String runId;

    /** 本次要跑的变体总数 */
    private Integer totalVariants;

    /** 已跑完的变体数 */
    private Integer doneVariants;

    /** 当前正在跑的变体 id */
    private String currentVariantId;

    /** 单个变体里要跑的用例数 */
    private Integer totalCases;

    /** 当前变体里已跑完的用例数 */
    private Integer doneCases;

    /** 开始时间（epoch 毫秒），IDLE 时为 null */
    private Long startTime;

    /** 耗时（毫秒），未结束时为 null */
    private Long costMillis;

    /** 报告目录（绝对路径），结束时才有 */
    private String reportPath;
}
