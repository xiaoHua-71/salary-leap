package com.xiaohua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.model.dto.level.LevelSubmitRequest;
import com.xiaohua.model.entity.Level;
import com.xiaohua.model.vo.LevelVO;
import com.xiaohua.model.vo.ReportVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 关卡服务
 */
public interface LevelService extends IService<Level> {

    /**
     * 根据薪资生成关卡并入库
     *
     * @param salary 当前薪资
     * @return 关卡视图（选项不含答案）
     */
    LevelVO generateLevel(int salary);

    /**
     * 提交作答并生成报告，更新用户薪资
     *
     * @param submitRequest 作答请求
     * @param request       HTTP 请求（获取登录用户）
     * @return 报告视图
     */
    ReportVO submitLevel(LevelSubmitRequest submitRequest, HttpServletRequest request);
}
