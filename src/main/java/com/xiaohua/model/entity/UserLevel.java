package com.xiaohua.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户关卡表
 * @TableName user_level
 */
@TableName(value ="user_level")
@Data
public class UserLevel {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 关卡ID
     */
    private Long levelId;

    /**
     * 用户选择的选项（JSON格式存储）
     */
    private String userOptions;

    /**
     * 得分（0-100分）
     */
    private Integer score;

    /**
     * 评价
     */
    private String comment;

    /**
     * 薪资变化（正数为加薪，负数为减薪）
     */
    private Integer salaryChange;

    /**
     * 公司投递建议
     */
    private String suggest;

    /**
     * 评分原因
     */
    private String reason;

    /**
     * 正确选项（JSON格式存储）
     */
    private String trueOptions;

    /**
     * 标准答案解析
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