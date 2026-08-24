package com.xiaohua.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户注册请求体
 *
 * @author 小花
 * @from 好好学习
 */
/**
 * 用户注册请求体
 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户密码
     */
    private String password;

    /**
     * 确认密码
     */
    private String checkPassword;

    /**
     * 用户昵称
     */
    private String nickname;
}
