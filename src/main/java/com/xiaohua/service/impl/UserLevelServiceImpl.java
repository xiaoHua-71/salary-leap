package com.xiaohua.service.impl;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.mapper.UserLevelMapper;
import com.xiaohua.model.entity.UserLevel;
import org.springframework.stereotype.Service;

/**
* @author qq
* @description 针对表【user_level(用户关卡表)】的数据库操作Service实现
* @createDate 2026-08-24 23:03:56
*/
@Service
public class UserLevelServiceImpl extends ServiceImpl<UserLevelMapper, UserLevel> implements IService<UserLevel> {

}




