package com.xiaohua.controller;

import com.xiaohua.common.BaseResponse;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.common.ResultUtils;
import com.xiaohua.model.dto.user.SendCodeRequest;
import com.xiaohua.model.dto.user.UserLoginRequest;
import com.xiaohua.model.dto.user.UserRegisterRequest;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.vo.UserVO;
import com.xiaohua.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户接口
 */
@RestController
@RequestMapping("/user")
@Slf4j
@Tag(name = "用户接口")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户注册
     *
     * @param userRegisterRequest 用户注册请求体
     * @return 新用户 id
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        try {
            Long userId = userService.userRegister(userRegisterRequest);
            return ResultUtils.success(userId);
        } catch (Exception e) {
            log.error("用户注册失败", e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
    }

    /**
     * 发送邮箱注册验证码
     *
     * @param sendCodeRequest 发送验证码请求体
     * @return 是否发送成功
     */
    @PostMapping("/sendCode")
    @Operation(summary = "发送邮箱注册验证码")
    public BaseResponse<Boolean> sendRegisterCode(@RequestBody SendCodeRequest sendCodeRequest) {
        try {
            userService.sendRegisterCode(sendCodeRequest.getEmail());
            return ResultUtils.success(true);
        } catch (Exception e) {
            log.error("发送验证码失败", e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest 用户登录请求体
     * @param request          请求对象
     * @return 脱敏后的用户信息
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public BaseResponse<UserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        try {
            UserVO userVO = userService.userLogin(userLoginRequest, request);
            return ResultUtils.success(userVO);
        } catch (Exception e) {
            log.error("用户登录失败", e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
    }

    /**
     * 获取当前登录用户
     *
     * @param request 请求对象
     * @return 当前用户信息
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前登录用户")
    public BaseResponse<UserVO> getCurrentUser(HttpServletRequest request) {
        try {
            User user = userService.getLoginUser(request);
            UserVO userVO = userService.getLoginUserVO(user);
            return ResultUtils.success(userVO);
        } catch (Exception e) {
            log.error("获取当前用户失败", e);
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, e.getMessage());
        }
    }

    /**
     * 用户注销
     *
     * @param request 请求对象
     * @return 是否成功
     */
    @PostMapping("/logout")
    @Operation(summary = "用户注销")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        try {
            boolean result = userService.userLogout(request);
            return ResultUtils.success(result);
        } catch (Exception e) {
            log.error("用户注销失败", e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
    }
}
