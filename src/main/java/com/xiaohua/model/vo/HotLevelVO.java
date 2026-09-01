package com.xiaohua.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 人气关卡列表项视图
 */
@Data
public class HotLevelVO implements Serializable {

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
     * 难度等级（简单/中等/困难）
     */
    private String difficulty;

    /**
     * 目标薪资
     */
    private Integer targetSalary;

    /**
     * 学习方向
     */
    private String direction;

    /**
     * 作答次数（热度）
     */
    private Integer playCount;

    /**
     * 优先级（0-普通，99-推荐，999-精选，9999-置顶）
     */
    private Integer priority;

    private static final long serialVersionUID = 1L;
}
