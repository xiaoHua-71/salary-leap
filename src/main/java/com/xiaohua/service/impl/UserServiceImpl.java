
package com.xiaohua.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.xiaohua.common.ErrorCode;
import com.xiaohua.constant.CacheKey;
import com.xiaohua.exception.BusinessException;
import com.xiaohua.mapper.UserMapper;
import com.xiaohua.model.dto.user.UserLoginRequest;
import com.xiaohua.model.dto.user.UserRegisterRequest;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.enums.UserRoleEnum;
import com.xiaohua.model.vo.RankVO;
import com.xiaohua.model.vo.UserVO;
import com.xiaohua.service.UserService;
import com.xiaohua.strategy.RegisterStrategyFactory;
import com.xiaohua.utils.AvatarUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 用户登录态键
     */
    public static final String USER_LOGIN_STATE = "user_login";

    @Resource
    private RegisterStrategyFactory registerStrategyFactory;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JavaMailSender javaMailSender;

    @Override
    public Long userRegister(UserRegisterRequest userRegisterRequest) {
        String registerType = StrUtil.isBlank(userRegisterRequest.getRegisterType()) ? "password" : userRegisterRequest.getRegisterType();
        return registerStrategyFactory.getStrategy(registerType).register(userRegisterRequest);
    }

    @Override
    public void sendRegisterCode(String email) {
        if (StrUtil.isBlank(email) || !email.contains("@")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        String code = String.valueOf(RandomUtil.randomInt(100000, 999999));
        stringRedisTemplate.opsForValue().set(CacheKey.EMAIL_CODE.key(email), code, 5, TimeUnit.MINUTES);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("salary-leap 注册验证码");
            message.setText("您的注册验证码为：" + code + "，5分钟内有效。");
            javaMailSender.send(message);
        } catch (MailException e) {
            // dev 环境未配置 SMTP 时降级为日志输出，生产环境请配置 spring.mail
            log.warn("邮件发送失败(可能是未配置SMTP)，验证码已写入Redis: email={}, code={}", email, code);
        }
    }

    @Override
    public UserVO userLogin(UserLoginRequest userLoginRequest, HttpServletRequest request) {
        String username = userLoginRequest.getUsername();
        String password = userLoginRequest.getPassword();

        // 校验
        if (StrUtil.hasBlank(username, password)) {
            throw new RuntimeException("参数为空");
        }
        if (username.length() < 4) {
            throw new RuntimeException("用户名错误");
        }
        if (password.length() < 8) {
            throw new RuntimeException("密码错误");
        }

        // 查询用户是否存在
        User user = this.baseMapper.selectOne(new QueryWrapper<User>().eq("username", username));

        // 用户不存在或密码不匹配
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            log.info("user login failed, username cannot match password");
            throw new RuntimeException("用户不存在或密码错误");
        }

        // 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        return this.getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new RuntimeException("未登录");
        }

        // 从数据库查询（追求性能的话可以注释，直接走缓存）
        Long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new RuntimeException("未登录");
        }
        return currentUser;
    }

    @Override
    public User getLoginUserOrNull(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            return null;
        }
        return this.getById(currentUser.getId());
    }

    @Override
    public UserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        // 如果用户没有头像，设置默认头像
        if (StrUtil.isBlank(userVO.getAvatar())) {
            userVO.setAvatar(AvatarUtils.getDefaultAvatarByUserId(user.getId()));
        }

        return userVO;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        if (request.getSession().getAttribute(USER_LOGIN_STATE) == null) {
            throw new RuntimeException("未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public boolean updateUserSalary(Long userId, int salaryChange) {
        if (userId == null) {
            return false;
        }

        User user = this.getById(userId);
        if (user == null) {
            return false;
        }

        int newSalary = user.getSalary() + salaryChange;
        // 薪资不能为负数
        if (newSalary < 0) {
            newSalary = 0;
        }

        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId);
        updateWrapper.set("salary", newSalary);

        return this.update(updateWrapper);
    }

    @Override
    public List<RankVO> getRankList() {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("salary");
        List<User> userList = this.list(queryWrapper);

        List<RankVO> rankList = new ArrayList<>(userList.size());
        for (int i = 0; i < userList.size(); i++) {
            User user = userList.get(i);
            RankVO rankVO = new RankVO();
            rankVO.setRank(i + 1);
            rankVO.setId(user.getId());
            rankVO.setUsername(user.getUsername());
            rankVO.setNickname(user.getNickname());
            rankVO.setAvatar(user.getAvatar());
            rankVO.setSalary(user.getSalary());
            rankList.add(rankVO);
        }
        return rankList;
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    @Override
    public void checkAdminAuth(HttpServletRequest request) {
        User user = getLoginUser(request);
        if (!isAdmin(user)) {
            throw new RuntimeException("无权限访问，仅限管理员");
        }
    }
}

