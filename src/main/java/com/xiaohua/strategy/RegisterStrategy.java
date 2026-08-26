package com.xiaohua.strategy;

import com.xiaohua.model.dto.user.UserRegisterRequest;

public interface RegisterStrategy {

    Long register(UserRegisterRequest request);
}
