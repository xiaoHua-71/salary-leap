package com.xiaohua.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.exception.BusinessException;
import com.xiaohua.mapper.LevelMapper;
import com.xiaohua.model.ai.LevelOption;
import com.xiaohua.model.ai.LevelResult;
import com.xiaohua.model.ai.ReportResult;
import com.xiaohua.model.dto.level.LevelSubmitRequest;
import com.xiaohua.model.entity.Level;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.entity.UserLevel;
import com.xiaohua.model.vo.LevelVO;
import com.xiaohua.model.vo.ReportVO;
import com.xiaohua.service.LevelService;
import com.xiaohua.service.UserLevelService;
import com.xiaohua.service.UserService;
import com.xiaohua.service.ai.LevelAiService;
import com.xiaohua.service.ai.ReportAiService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 关卡服务实现
 */
@Service
public class LevelServiceImpl extends ServiceImpl<LevelMapper, Level> implements LevelService {

    @Resource
    private LevelAiService levelAiService;

    @Resource
    private ReportAiService reportAiService;

    @Resource
    private UserLevelService userLevelService;

    @Resource
    private UserService userService;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public LevelVO generateLevel(int salary) {
        LevelResult result = levelAiService.generateLevel(salary);
        if (result == null || StrUtil.isBlank(result.getLevelName())
                || result.getOptions() == null || result.getOptions().isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成关卡失败");
        }

        String optionsJson;
        try {
            optionsJson = objectMapper.writeValueAsString(result.getOptions());
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "选项序列化失败");
        }

        Level level = new Level();
        level.setLevelName(result.getLevelName());
        level.setLevelDesc(result.getLevelDesc());
        level.setOptions(optionsJson);
        level.setDifficulty(result.getDifficulty());
        level.setTargetSalary(result.getTargetSalary());
        this.save(level);

        LevelVO vo = new LevelVO();
        vo.setId(level.getId());
        vo.setLevelName(level.getLevelName());
        vo.setLevelDesc(level.getLevelDesc());
        List<String> optionNames = result.getOptions().stream()
                .map(LevelOption::getOptionName)
                .collect(Collectors.toList());
        vo.setOptions(optionNames);
        vo.setDifficulty(level.getDifficulty());
        vo.setTargetSalary(level.getTargetSalary());
        return vo;
    }

    @Override
    public ReportVO submitLevel(LevelSubmitRequest submitRequest, HttpServletRequest request) {
        Long levelId = submitRequest.getLevelId();
        List<String> userOptions = submitRequest.getUserOptions();
        if (levelId == null || userOptions == null || userOptions.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "关卡 id 和作答选项不能为空");
        }

        Level level = this.getById(levelId);
        if (level == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "关卡不存在");
        }

        // 反序列化关卡选项，提取正确选项
        List<LevelOption> levelOptions;
        try {
            levelOptions = objectMapper.readValue(level.getOptions(), new TypeReference<List<LevelOption>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "关卡选项解析失败");
        }
        List<String> trueOptions = levelOptions.stream()
                .filter(opt -> Boolean.TRUE.equals(opt.getTrueAnswer()))
                .map(LevelOption::getOptionName)
                .collect(Collectors.toList());

        // 取当前登录用户及其薪资
        User user = userService.getLoginUser(request);
        int salary = user.getSalary() == null ? 0 : user.getSalary();

        // 调 AI 生成报告
        String userOptionsJson;
        String trueOptionsJson;
        try {
            userOptionsJson = objectMapper.writeValueAsString(userOptions);
            trueOptionsJson = objectMapper.writeValueAsString(trueOptions);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "选项序列化失败");
        }
        ReportResult report = reportAiService.generateReport(
                level.getLevelName(), level.getLevelDesc(), userOptionsJson, trueOptionsJson, salary);
        if (report == null || report.getScore() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成报告失败");
        }

        // 更新用户薪资（不低于 0）
        int salaryChange = report.getSalaryChange() == null ? 0 : report.getSalaryChange();
        int newSalary = Math.max(0, salary + salaryChange);
        userService.updateUserSalary(user.getId(), salaryChange);

        // 保存作答记录
        UserLevel userLevel = new UserLevel();
        userLevel.setUserId(user.getId());
        userLevel.setLevelId(levelId);
        userLevel.setUserOptions(userOptionsJson);
        userLevel.setScore(report.getScore());
        userLevel.setComment(report.getComment());
        userLevel.setSalaryChange(salaryChange);
        userLevel.setSuggest(report.getSuggest());
        userLevel.setReason(report.getReason());
        userLevel.setTrueOptions(trueOptionsJson);
        userLevel.setStandardAnswer(report.getStandardAnswer());
        userLevelService.save(userLevel);

        ReportVO vo = new ReportVO();
        vo.setLevelId(levelId);
        vo.setScore(report.getScore());
        vo.setComment(report.getComment());
        vo.setSalaryChange(salaryChange);
        vo.setSuggest(report.getSuggest());
        vo.setReason(report.getReason());
        vo.setTrueOptions(trueOptions);
        vo.setStandardAnswer(report.getStandardAnswer());
        vo.setNewSalary(newSalary);
        return vo;
    }
}
