package com.xiaohua.controller;

import com.xiaohua.common.BaseResponse;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.common.ResultUtils;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.vo.AnswerSummaryVO;
import com.xiaohua.model.vo.UserLevelVO;
import com.xiaohua.service.UserLevelService;
import com.xiaohua.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 答题记录接口
 */
@RestController
@RequestMapping("/record")
@Slf4j
public class RecordController {

    @Resource
    private UserService userService;

    @Resource
    private UserLevelService userLevelService;

    /**
     * 获取答题统计概览
     */
    @GetMapping("/summary")
    public BaseResponse<AnswerSummaryVO> getAnswerSummary(HttpServletRequest request) {
        User loginUser;
        try {
            loginUser = userService.getLoginUser(request);
        } catch (Exception e) {
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
        try {
            AnswerSummaryVO vo = userLevelService.getAnswerSummary(loginUser.getId());
            return ResultUtils.success(vo);
        } catch (Exception e) {
            log.error("获取答题统计失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage());
        }
    }

    /**
     * 获取历史答题记录
     */
    @GetMapping("/list")
    public BaseResponse<List<UserLevelVO>> listUserLevels(HttpServletRequest request) {
        User loginUser;
        try {
            loginUser = userService.getLoginUser(request);
        } catch (Exception e) {
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
        try {
            List<UserLevelVO> voList = userLevelService.listUserLevels(loginUser.getId());
            return ResultUtils.success(voList);
        } catch (Exception e) {
            log.error("获取历史答题记录失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage());
        }
    }
}
