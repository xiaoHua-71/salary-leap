package com.xiaohua.controller;

import com.xiaohua.common.BaseResponse;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.common.ResultUtils;
import com.xiaohua.model.eval.RagEvalReport;
import com.xiaohua.model.eval.EvalVariant;
import com.xiaohua.model.vo.RagEvalStatusVO;
import com.xiaohua.service.eval.RagEvalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索离线评估接口。
 *
 * <p>用法（默认跑 {@code rag.eval.default-variants} 里配的那几个变体）：</p>
 * <pre>
 * POST /api/rag/eval                                        # 提交评估
 * POST /api/rag/eval?variants=all-on,vector-only            # 指定变体
 * POST /api/rag/eval?caseIds=mysql-btree-01                 # 只跑少数几条（调评估集时用，省时间）
 * GET  /api/rag/eval/status                                 # 轮询进度
 * GET  /api/rag/eval/report/latest                          # 拿最近一次报告
 * </pre>
 *
 * <p>和知识库重建一样是异步的：一次评估要跑几百次外部调用，同步等会把请求挂死。</p>
 */
@RestController
@RequestMapping("/rag/eval")
@Slf4j
public class RagEvalController {

    @Resource
    private RagEvalService ragEvalService;

    /**
     * 提交一次离线评估。
     *
     * @param variants 要跑的变体 id，不传则用配置的默认变体。可用值见 {@link EvalVariant}
     * @param caseIds  只跑指定的用例，不传则跑全部启用的用例
     */
    @PostMapping
    public BaseResponse<String> eval(@RequestParam(value = "variants", required = false) List<String> variants,
                                     @RequestParam(value = "caseIds", required = false) List<String> caseIds) {
        try {
            if (ragEvalService.triggerEval(variants, caseIds)) {
                return ResultUtils.success("已提交评估，请轮询 /rag/eval/status 查看进度，"
                        + "完成后报告写入 docs/rag-eval/reports/");
            }
        } catch (IllegalArgumentException e) {
            // 变体 id 写错这类入参问题，直接把可用值回给调用方
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
        return ResultUtils.error(ErrorCode.OPERATION_ERROR, "已有评估在进行中，请稍后再试");
    }

    /**
     * 查询评估进度：IDLE / RUNNING / SUCCESS / FAILED
     */
    @GetMapping("/status")
    public BaseResponse<RagEvalStatusVO> status() {
        return ResultUtils.success(ragEvalService.getStatus());
    }

    /**
     * 最近一次评估报告（含各变体指标、逐条明细与和上次的差值）。
     */
    @GetMapping("/report/latest")
    public BaseResponse<RagEvalReport> latestReport() {
        RagEvalReport report = ragEvalService.getLatestReport();
        if (report == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "还没有任何评估报告，请先 POST /rag/eval");
        }
        return ResultUtils.success(report);
    }
}
