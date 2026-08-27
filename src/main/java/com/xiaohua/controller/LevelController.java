package com.xiaohua.controller;

import com.xiaohua.common.BaseResponse;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.common.ResultUtils;
import com.xiaohua.model.vo.LevelVO;
import com.xiaohua.service.LevelService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关卡接口
 */
@RestController
@RequestMapping("/level")
@Slf4j
public class LevelController {

    @Resource
    private LevelService levelService;

    /**
     * 生成关卡（测试用，后续改为根据登录用户薪资生成）
     */
    @PostMapping("/generate")
    public BaseResponse<LevelVO> generateLevel(@RequestParam(defaultValue = "10000") int salary) {
        try {
            LevelVO vo = levelService.generateLevel(salary);
            return ResultUtils.success(vo);
        } catch (Exception e) {
            log.error("生成关卡失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage());
        }
    }
}
