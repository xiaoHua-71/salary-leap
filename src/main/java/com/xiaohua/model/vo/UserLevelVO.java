package com.xiaohua.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 历史答题记录视图
 */
@Data
public class UserLevelVO implements Serializable {

    /**
     * 记录 id
     */
    private Long id;

    /**
     * 关卡 id
     */
    private Long levelId;

    /**
     * 关卡名称
     */
    private String levelName;

    /**
     * 得分（0-100）
     */
    private Integer score;

    /**
     * 薪资变化（正数加薪，负数减薪）
     */
    private Integer salaryChange;

    /**
     * 作答时间
     */
    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
