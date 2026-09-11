package com.xiaohua.controller;

import com.xiaohua.common.BaseResponse;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.common.ResultUtils;
import com.xiaohua.model.dto.level.LevelSubmitRequest;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.vo.HotLevelVO;
import com.xiaohua.model.vo.LevelVO;
import com.xiaohua.model.vo.ReportVO;
import com.xiaohua.service.LevelService;
import com.xiaohua.service.UserService;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 关卡接口
 */
@RestController
@RequestMapping("/level")
@Slf4j
public class LevelController {

    @Resource
    private LevelService levelService;

    @Resource
    private UserService userService;

    /**
     * 生成关卡。方向/薪资优先取登录用户的标签和薪资，未登录则回退到请求参数。
     * AI 出题失败时自动降级到预设题库。
     *
     * @param salary    当前薪资（未登录时使用）
     * @param direction 学习方向（未登录时使用，用于 RAG 检索知识库）
     */
    @PostMapping("/generate")
    public BaseResponse<LevelVO> generateLevel(@RequestParam(defaultValue = "10000") int salary,
                                               @RequestParam(required = false) String direction,
                                               HttpServletRequest request) {
        try {
            User loginUser = userService.getLoginUserOrNull(request);
            if (loginUser != null) {
                if (loginUser.getSalary() != null) {
                    salary = loginUser.getSalary();
                }
                if (StrUtil.isNotBlank(loginUser.getDirection())) {
                    direction = loginUser.getDirection();
                }
            }
            Long userId = loginUser == null ? null : loginUser.getId();
            LevelVO vo = levelService.generateLevel(salary, direction, userId);
            return ResultUtils.success(vo);
        } catch (Exception e) {
            log.error("生成关卡失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage());
        }
    }

    /**
     * 提交作答，生成报告并更新用户薪资
     */
    @PostMapping("/submit")
    public BaseResponse<ReportVO> submitLevel(@RequestBody LevelSubmitRequest submitRequest, HttpServletRequest request) {
        try {
            ReportVO vo = levelService.submitLevel(submitRequest, request);
            return ResultUtils.success(vo);
        } catch (Exception e) {
            log.error("提交作答失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage());
        }
    }

    /**
     * 人气关卡列表（按作答次数降序，无需登录）
     */
    @GetMapping("/hot")
    public BaseResponse<List<HotLevelVO>> listHotLevels(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String direction) {
        List<HotLevelVO> list = levelService.listHotLevels(limit, direction);
        return ResultUtils.success(list);
    }

    /**
     * 关卡详情（选项不含答案，无需登录）
     */
    @GetMapping("/{id}")
    public BaseResponse<LevelVO> getLevelDetail(@PathVariable Long id) {
        LevelVO vo = levelService.getLevelDetail(id);
        return ResultUtils.success(vo);
    }
}
