package com.xiaohua.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 排行榜用户视图
 */
@Data
public class RankVO implements Serializable {

    /**
     * 排名（从 1 开始）
     */
    private Integer rank;

    /**
     * 用户 id
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 用户头像URL
     */
    private String avatar;

    /**
     * 当前薪资（单位：元/月）
     */
    private Integer salary;

    private static final long serialVersionUID = 1L;
}
