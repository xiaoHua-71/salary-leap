package com.xiaohua.service;

import com.baomidou.mybatisplus.extension.service.IService;


import java.util.List;

import com.xiaohua.model.dto.user.UserLoginRequest;
import com.xiaohua.model.dto.user.UserRegisterRequest;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户服务
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     *
     * @param userRegisterRequest 用户注册请求体
     * @return 新用户 id
     */
    String userRegister(UserRegisterRequest userRegisterRequest);

    /**
     * 用户登录
     *
     * @param userLoginRequest 用户登录请求体
     * @param request          请求对象
     * @return 脱敏后的用户信息
     */
    UserVO userLogin(UserLoginRequest userLoginRequest, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request 请求对象
     * @return 用户信息
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 获取脱敏的已登录用户信息
     *
     * @return 脱敏用户信息
     */
    UserVO getLoginUserVO(User user);

    /**
     * 用户注销
     *
     * @param request 请求对象
     * @return 是否成功
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 更新用户薪资
     *
     * @param userId       用户ID
     * @param salaryChange 薪资变化
     * @return 是否成功
     */
    boolean updateUserSalary(String userId, int salaryChange);

    /**
     * 判断用户是否为管理员
     *
     * @param user 用户信息
     * @return 是否为管理员
     */
    boolean isAdmin(User user);

    /**
     * 校验管理员权限
     *
     * @param request 请求对象
     * @throws RuntimeException 如果不是管理员则抛出异常
     */
    void checkAdminAuth(HttpServletRequest request);

}

