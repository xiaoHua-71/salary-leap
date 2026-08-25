package com.xiaohua.strategy.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.exception.BusinessException;
import com.xiaohua.mapper.UserMapper;
import com.xiaohua.model.dto.user.UserRegisterRequest;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.enums.UserRoleEnum;
import com.xiaohua.service.impl.UserServiceImpl;
import com.xiaohua.strategy.RegisterStrategy;
import com.xiaohua.utils.AvatarUtils;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PasswordRegisterStrategy implements RegisterStrategy {

    private static final String SPECIAL_CHAR_PATTERN = "[`~!@#$%^&*()+=|{}':;',\\\\[\\\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";

    @Resource
    private UserMapper userMapper;

    @Override
    public String register(UserRegisterRequest request) {
        String username = request.getUsername();
        String password = request.getPassword();
        String checkPassword = request.getCheckPassword();
        String nickname = request.getNickname();

        if (StringUtils.isAnyBlank(username, password, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (username.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名过短");
        }
        if (password.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (Pattern.compile(SPECIAL_CHAR_PATTERN).matcher(username).find()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名不能包含特殊字符");
        }
        if (!password.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        long count = userMapper.selectCount(new QueryWrapper<User>().eq("username", username));
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(DigestUtil.md5Hex(UserServiceImpl.SALT + password));
        user.setNickname(StringUtils.isBlank(nickname) ? username : nickname);
        user.setUserRole(UserRoleEnum.USER.getValue());
        user.setSalary(10000);
        if (userMapper.insert(user) <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户注册失败");
        }
        user.setAvatar(AvatarUtils.getDefaultAvatarByUserId(user.getId()));
        userMapper.updateById(user);
        return user.getId();
    }
}
