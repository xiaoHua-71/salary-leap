package com.xiaohua.model.ai;

import lombok.Data;

import java.util.List;

/**
 * AI 生成的关卡结果
 */
@Data
public class LevelResult {

    /**
     * 关卡名称
     */
    private String levelName;

    /**
     * 需求描述
     */
    private String levelDesc;

    /**
     * 选项列表（部分正确，部分干扰）
     */
    private List<LevelOption> options;

    /**
     * 难度等级（简单/中等/困难）
     */
    private String difficulty;

    /**
     * 目标薪资
     */
    private Integer targetSalary;
}
