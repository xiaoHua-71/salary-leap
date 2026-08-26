package com.xiaohua.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.model.entity.Level;
import com.xiaohua.model.vo.LevelVO;

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
}
