package com.xiaohua.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 答题统计概览视图
 */
@Data
public class AnswerSummaryVO implements Serializable {

    /**
     * 总挑战次数
     */
    private Integer totalCount;

    /**
     * 平均分数（保留 1 位小数）
     */
    private Double avgScore;

    /**
     * 当前薪资（单位：元/月）
     */
    private Integer currentSalary;

    /**
     * 薪资变化（累计，正数加薪负数减薪）
     */
    private Integer totalSalaryChange;

    private static final long serialVersionUID = 1L;
}
