package com.xiaohua.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.mapper.UserLevelMapper;
import com.xiaohua.model.entity.UserLevel;
import com.xiaohua.service.UserLevelService;
import org.springframework.stereotype.Service;

/**
 * 用户关卡服务实现
 */
@Service
public class UserLevelServiceImpl extends ServiceImpl<UserLevelMapper, UserLevel> implements UserLevelService {

}
