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
import org.springframework.web.bind.annotation.RequestParam;
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
     * 触发异步重建索引：改了知识库内容（knowledge/*.txt、网页缓存、rag.web.urls）后调用，
     * 后台重建、立即返回。用 GET /rag/rebuild/status 轮询进度。
     *
     * <p>默认走<b>增量</b>：只对内容变了的来源重新 embedding，没有变化时秒回。
     * 传 {@code full=true} 强制全量重建（新建集合），清单缺失、清单属于别的集合、
     * 改过切分参数时也会自动走全量。</p>
     */
    @PostMapping("/rebuild")
    public BaseResponse<String> rebuild(@RequestParam(value = "full", defaultValue = "false") boolean full) {
        if (ragIndexService.triggerRebuild(full)) {
            return ResultUtils.success((full ? "已提交全量重建" : "已提交增量更新")
                    + "，请轮询 /rag/rebuild/status 查看进度");
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
