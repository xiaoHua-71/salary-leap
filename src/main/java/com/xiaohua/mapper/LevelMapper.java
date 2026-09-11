package com.xiaohua.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.model.entity.Level;
import com.xiaohua.model.vo.HotLevelVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
* @author qq
* @description 针对表【level(关卡表)】的数据库操作Mapper
* @createDate 2026-08-24 23:03:56
* @Entity generator.domain.Level
*/
public interface LevelMapper extends BaseMapper<Level> {

    /**
     * 查询人气关卡（按作答次数降序，priority 作为次级排序权重）
     *
     * @param limit     返回数量
     * @param direction 学习方向（可空，空则不过滤）
     * @return 人气关卡列表
     */
    List<HotLevelVO> selectHotLevels(@Param("limit") int limit, @Param("direction") String direction);

    /**
     * 按学习方向随机取一道题（预设题库兜底用）。
     * 优先人工预设题（source='PRESET'），并排除该用户已答过的题。
     *
     * @param direction 学习方向标签（或 "通用"）
     * @param userId    当前用户 id（用于排除已答题目）
     * @return 关卡（查不到返回 null）
     */
    Level selectRandomByDirection(@Param("direction") String direction, @Param("userId") Long userId);
}
