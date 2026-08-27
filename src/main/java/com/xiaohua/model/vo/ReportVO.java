package com.xiaohua.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 闯关报告视图对象（返回给前端）
 */
@Data
public class ReportVO {

    /**
     * 关卡 id
     */
    private Long levelId;

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
     * 正确选项
     */
    private List<String> trueOptions;

    /**
     * 标准答案解析
     */
    private String standardAnswer;

    /**
     * 更新后的薪资
     */
    private Integer newSalary;
}
