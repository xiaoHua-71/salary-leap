package com.xiaohua.controller;

import com.xiaohua.common.BaseResponse;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.common.ResultUtils;
import com.xiaohua.model.vo.RankVO;
import com.xiaohua.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排行榜接口
 */
@RestController
@RequestMapping("/rank")
@Slf4j
public class RankController {

    @Resource
    private UserService userService;

    /**
     * 获取排行榜（按薪资降序）
     */
    @GetMapping
    public BaseResponse<List<RankVO>> getRankList() {
        try {
            List<RankVO> rankList = userService.getRankList();
            return ResultUtils.success(rankList);
        } catch (Exception e) {
            log.error("获取排行榜失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage());
        }
    }
}
