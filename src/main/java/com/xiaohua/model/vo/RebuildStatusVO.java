package com.xiaohua.model.vo;

import lombok.Data;

/**
 * 知识库重建索引的状态
 */
@Data
public class RebuildStatusVO {

    /**
     * 状态：IDLE（空闲）/ RUNNING（重建中）/ SUCCESS（成功）/ FAILED（失败）
     */
    private String state;

    /**
     * 说明信息（失败时为错误原因）
     */
    private String message;

    /**
     * 本次重建的集合名
     */
    private String collectionName;

    /**
     * 开始时间（epoch 毫秒），IDLE 时为 null
     */
    private Long startTime;

    /**
     * 耗时（毫秒），未结束时为 null
     */
    private Long costMillis;
}
