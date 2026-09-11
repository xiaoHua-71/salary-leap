package com.xiaohua.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 关卡表
 * @TableName level
 */
@TableName(value ="level")
@Data
public class Level {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关卡名称
     */
    private String levelName;

    /**
     * 关卡需求描述
     */
    private String levelDesc;

    /**
     * 关卡选项（JSON格式存储）
     */
    private String options;

    /**
     * 难度等级（简单，中等，困难）
     */
    private String difficulty;

    /**
     * 目标薪资范围（用于难度匹配）
     */
    private Integer targetSalary;

    /**
     * 学习方向（前端开发、Java后端开发、软件测试等）
     */
    private String direction;

    /**
     * 关卡优先级（0-普通，99-推荐，999-精选，9999-置顶）
     */
    private Integer priority;

    /**
     * 关卡来源（AI-AI生成，PRESET-人工预设题库）
     */
    private String source;

    /**
     * 标准答案解析（预设题用；AI 题的解析在判分时生成）
     */
    private String standardAnswer;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 逻辑删除（0-未删除，1-已删除）
     */
    private Integer isDelete;
}