package com.xiaohua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.model.entity.UserLevel;
import com.xiaohua.model.vo.AnswerSummaryVO;
import com.xiaohua.model.vo.UserLevelVO;

import java.util.List;

/**
* @author qq
* @description 针对表【user_level(用户关卡表)】的数据库操作Service
* @createDate 2026-08-24 23:03:56
*/
public interface UserLevelService extends IService<UserLevel> {

    /**
     * 获取用户答题统计概览
     *
     * @param userId 用户 id
     * @return 统计概览
     */
    AnswerSummaryVO getAnswerSummary(Long userId);

    /**
     * 获取用户历史答题记录（按作答时间倒序）
     *
     * @param userId 用户 id
     * @return 历史记录列表
     */
    List<UserLevelVO> listUserLevels(Long userId);
}
