package com.xiaohua.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 关卡视图对象（返回给前端，选项不含答案）
 */
@Data
public class LevelVO {

    /**
     * 关卡 id
     */
    private Long id;

    /**
     * 关卡名称
     */
    private String levelName;

    /**
     * 需求描述
     */
    private String levelDesc;

    /**
     * 选项名称列表（不含答案）
     */
    private List<String> options;

    /**
     * 难度等级
     */
    private String difficulty;

    /**
     * 目标薪资
     */
    private Integer targetSalary;

    /**
     * 关卡来源（AI-AI生成，PRESET-预设题库兜底）
     */
    private String source;
}
