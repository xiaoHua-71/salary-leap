package com.xiaohua.controller;

import com.xiaohua.common.BaseResponse;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.common.ResultUtils;
import com.xiaohua.model.vo.RebuildStatusVO;
import com.xiaohua.service.RagIndexService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库索引接口
 */
@RestController
@RequestMapping("/rag")
@Slf4j
public class RagIndexController {

    @Resource
    private RagIndexService ragIndexService;

    /**
     * 触发异步重建索引：改了 knowledge/*.txt 后调用，后台重建、立即返回。
     * 用 GET /rag/rebuild/status 轮询进度。
     */
    @PostMapping("/rebuild")
    public BaseResponse<String> rebuild() {
        if (ragIndexService.triggerRebuild()) {
            return ResultUtils.success("已提交重建，请轮询 /rag/rebuild/status 查看进度");
        }
        return ResultUtils.error(ErrorCode.OPERATION_ERROR, "已有重建任务在进行中，请稍后再试");
    }

    /**
     * 查询重建状态：IDLE / RUNNING / SUCCESS / FAILED
     */
    @GetMapping("/rebuild/status")
    public BaseResponse<RebuildStatusVO> rebuildStatus() {
        return ResultUtils.success(ragIndexService.getRebuildStatus());
    }
}
