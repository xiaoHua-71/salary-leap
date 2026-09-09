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
import com.xiaohua.model.vo.HotLevelVO;
import com.xiaohua.model.vo.LevelVO;
import com.xiaohua.model.vo.ReportVO;
import com.xiaohua.service.KnowledgeService;
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

    @Resource
    private KnowledgeService knowledgeService;

    @Override
    public LevelVO generateLevel(int salary, String direction) {
        String directionText = StrUtil.isBlank(direction) ? "Java后端开发" : direction;

        // RAG：先按方向从知识库检索相关知识，再让模型照着知识出题
        String knowledge = knowledgeService.retrieve(directionText);

        LevelResult result = levelAiService.generateLevel(salary, directionText, knowledge);
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
        level.setDirection(directionText);
        this.save(level);

        return buildLevelVO(level.getId(), level.getLevelName(), level.getLevelDesc(),
                result.getOptions(), level.getDifficulty(), level.getTargetSalary());
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

        // 薪资变化由分数确定性计算，避免与文字评价脱钩
        int salaryChange = calcSalaryChange(report.getScore(), salary);
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

    @Override
    public List<HotLevelVO> listHotLevels(int limit, String direction) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return baseMapper.selectHotLevels(safeLimit, direction);
    }

    @Override
    public LevelVO getLevelDetail(Long levelId) {
        Level level = this.getById(levelId);
        if (level == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "关卡不存在");
        }
        List<LevelOption> levelOptions;
        try {
            levelOptions = objectMapper.readValue(level.getOptions(), new TypeReference<List<LevelOption>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "关卡选项解析失败");
        }
        return buildLevelVO(level.getId(), level.getLevelName(), level.getLevelDesc(),
                levelOptions, level.getDifficulty(), level.getTargetSalary());
    }

    private int calcSalaryChange(int score, int currentSalary) {
        if (score >= 90) {
            return Math.round(currentSalary * 0.10f);
        }
        if (score >= 75) {
            return Math.round(currentSalary * 0.05f);
        }
        if (score >= 60) {
            return Math.round(currentSalary * 0.02f);
        }
        if (score >= 40) {
            return -Math.round(currentSalary * 0.05f);
        }
        return -Math.round(currentSalary * 0.10f);
    }

    private LevelVO buildLevelVO(Long id, String levelName, String levelDesc,
                                 List<LevelOption> options, String difficulty, Integer targetSalary) {
        LevelVO vo = new LevelVO();
        vo.setId(id);
        vo.setLevelName(levelName);
        vo.setLevelDesc(levelDesc);
        List<String> optionNames = options.stream()
                .map(LevelOption::getOptionName)
                .collect(Collectors.toList());
        vo.setOptions(optionNames);
        vo.setDifficulty(difficulty);
        vo.setTargetSalary(targetSalary);
        return vo;
    }
}
