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
}
