package com.xiaohua.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户更新请求（仅更新当前登录用户自己，不含密码）
 */
@Data
public class UserUpdateRequest implements Serializable {

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 用户头像URL
     */
    private String avatar;

    /**
     * 学习方向标签（出题方向，Java后端开发、前端开发、Go开发、Agent开发或自定义）
     */
    private String direction;

    private static final long serialVersionUID = 1L;
}
