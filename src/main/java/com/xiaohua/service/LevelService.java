package com.xiaohua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.model.dto.level.LevelSubmitRequest;
import com.xiaohua.model.entity.Level;
import com.xiaohua.model.vo.HotLevelVO;
import com.xiaohua.model.vo.LevelVO;
import com.xiaohua.model.vo.ReportVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 关卡服务
 */
public interface LevelService extends IService<Level> {

    /**
     * 根据薪资和学习方向生成关卡并入库（方向用于检索知识库，RAG 出题）。
     * AI 出题失败时降级到预设题库（按方向查，三级兜底）。
     *
     * @param salary    当前薪资
     * @param direction 学习方向（可空，为空时默认全栈开发）
     * @param userId    当前用户 id（用于预设题库排除已答题目，可空）
     * @return 关卡视图（选项不含答案）
     */
    LevelVO generateLevel(int salary, String direction, Long userId);

    /**
     * 提交作答并生成报告，更新用户薪资
     *
     * @param submitRequest 作答请求
     * @param request       HTTP 请求（获取登录用户）
     * @return 报告视图
     */
    ReportVO submitLevel(LevelSubmitRequest submitRequest, HttpServletRequest request);

    /**
     * 查询人气关卡列表（按作答次数降序）
     *
     * @param limit     返回数量
     * @param direction 学习方向（可空）
     * @return 人气关卡列表
     */
    List<HotLevelVO> listHotLevels(int limit, String direction);

    /**
     * 获取关卡详情（选项不含答案）
     *
     * @param levelId 关卡 id
     * @return 关卡视图
     */
    LevelVO getLevelDetail(Long levelId);
}
