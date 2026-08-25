package com.xiaohua.strategy;

import com.xiaohua.model.dto.user.UserRegisterRequest;

public interface RegisterStrategy {

    String register(UserRegisterRequest request);
}
