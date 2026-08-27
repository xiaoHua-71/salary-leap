package com.xiaohua.model.ai;

import lombok.Data;

import java.util.List;

/**
 * AI 生成的闯关报告结果
 */
@Data
public class ReportResult {

    /**
     * 作答分数（满分 100）
     */
    private Integer score;

    /**
     * 评价
     */
    private String comment;

    /**
     * 薪资调整（正数加薪，负数减薪）
     */
    private Integer salaryChange;

    /**
     * 投递公司建议
     */
    private String suggest;

    /**
     * 评分原因
     */
    private String reason;

    /**
     * 本关卡正确选项
     */
    private List<String> trueOptions;

    /**
     * 标准答案解析
     */
    private String standardAnswer;
}
