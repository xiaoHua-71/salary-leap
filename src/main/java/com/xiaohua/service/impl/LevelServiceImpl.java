package com.xiaohua.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.constant.AiPrompt;
import com.xiaohua.constant.PresetLevels;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关卡服务实现
 */
@Service
@Slf4j
public class LevelServiceImpl extends ServiceImpl<LevelMapper, Level> implements LevelService {

    /** 关卡来源：AI 生成 */
    private static final String SOURCE_AI = "AI";
    /** 关卡来源：人工预设题库 */
    private static final String SOURCE_PRESET = "PRESET";
    /** 通用兜底方向 */
    private static final String DIRECTION_GENERAL = "通用";

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
    public LevelVO generateLevel(int salary, String direction, Long userId) {
        String directionText = StrUtil.isBlank(direction) ? "全栈开发" : direction;

        LevelResult result;
        try {
            // RAG：先按方向从知识库检索相关知识，再让模型照着知识出题
            String knowledge = knowledgeService.retrieve(directionText);
            if (StrUtil.isBlank(knowledge)) {
                knowledge = AiPrompt.NO_KNOWLEDGE_FALLBACK;
            }
            result = levelAiService.generateLevel(salary, directionText, knowledge);
            if (result == null || StrUtil.isBlank(result.getLevelName())
                    || result.getOptions() == null || result.getOptions().isEmpty()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成关卡失败");
            }
        } catch (Exception e) {
            // 兜底降级：AI 出题失败 → 预设题库（三级兜底链）
            log.warn("AI 出题失败，降级到预设题库: {}", e.getMessage(), e);
            return fallbackLevel(directionText, userId);
        }

        Level level = new Level();
        level.setLevelName(result.getLevelName());
        level.setLevelDesc(result.getLevelDesc());
        level.setOptions(toJson(result.getOptions()));
        level.setDifficulty(result.getDifficulty());
        level.setTargetSalary(result.getTargetSalary());
        level.setDirection(directionText);
        level.setSource(SOURCE_AI);
        this.save(level);

        return buildLevelVO(level.getId(), level.getLevelName(), level.getLevelDesc(),
                result.getOptions(), level.getDifficulty(), level.getTargetSalary(), SOURCE_AI);
    }

    /**
     * 三级兜底链：① 按方向取预设题 → ② 取「通用」题 → ③ 内置保底题（落库后返回，保证可提交）
     */
    private LevelVO fallbackLevel(String direction, Long userId) {
        Level level = baseMapper.selectRandomByDirection(direction, userId);
        if (level == null) {
            level = baseMapper.selectRandomByDirection(DIRECTION_GENERAL, userId);
        }
        if (level != null) {
            log.info("命中预设题 [{}]（方向 {}）", level.getLevelName(), level.getDirection());
            return buildLevelVO(level.getId(), level.getLevelName(), level.getLevelDesc(),
                    parseOptions(level.getOptions()), level.getDifficulty(),
                    level.getTargetSalary(), level.getSource());
        }

        // ③ 预设题库也空了：用代码内置题，落库进「通用」池，保证有 id 可提交作答
        log.warn("预设题库无可用题，使用内置保底题");
        LevelResult builtin = PresetLevels.BUILTIN;
        Level builtinLevel = new Level();
        builtinLevel.setLevelName(builtin.getLevelName());
        builtinLevel.setLevelDesc(builtin.getLevelDesc());
        builtinLevel.setOptions(toJson(builtin.getOptions()));
        builtinLevel.setDifficulty(builtin.getDifficulty());
        builtinLevel.setTargetSalary(builtin.getTargetSalary());
        builtinLevel.setDirection(DIRECTION_GENERAL);
        builtinLevel.setSource(SOURCE_PRESET);
        builtinLevel.setPriority(0);
        this.save(builtinLevel);
        return buildLevelVO(builtinLevel.getId(), builtinLevel.getLevelName(), builtinLevel.getLevelDesc(),
                builtin.getOptions(), builtinLevel.getDifficulty(), builtinLevel.getTargetSalary(), SOURCE_PRESET);
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
        List<LevelOption> levelOptions = parseOptions(level.getOptions());
        List<String> trueOptions = levelOptions.stream()
                .filter(opt -> Boolean.TRUE.equals(opt.getTrueAnswer()))
                .map(LevelOption::getOptionName)
                .collect(Collectors.toList());

        User user = userService.getLoginUser(request);
        int salary = user.getSalary() == null ? 0 : user.getSalary();

        String userOptionsJson = toJson(userOptions);
        String trueOptionsJson = toJson(trueOptions);

        ReportResult report;
        try {
            report = reportAiService.generateReport(
                    level.getLevelName(), level.getLevelDesc(), userOptionsJson, trueOptionsJson, salary);
            if (report == null || report.getScore() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成报告失败");
            }
        } catch (Exception e) {
            // 兜底降级：AI 判分失败 → 本地判分（直接对比选项，不走 AI）
            log.warn("AI 判分失败，降级到本地判分: {}", e.getMessage(), e);
            report = localScore(trueOptions, userOptions, level);
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

    /**
     * 本地判分：直接对比用户选项和正确选项算分，不调用 AI（AI 故障时的兜底）。
     */
    private ReportResult localScore(List<String> trueOptions, List<String> userOptions, Level level) {
        Set<String> trueSet = new HashSet<>(trueOptions);
        Set<String> userSet = new HashSet<>(userOptions);
        long correct = userSet.stream().filter(trueSet::contains).count();
        long wrong = userSet.stream().filter(o -> !trueSet.contains(o)).count();
        long missed = trueSet.stream().filter(o -> !userSet.contains(o)).count();
        int totalTrue = trueSet.size();

        int score = 0;
        if (totalTrue > 0) {
            double raw = 100.0 * (correct - wrong) / totalTrue;
            score = (int) Math.max(0, Math.min(100, Math.round(raw)));
        }

        ReportResult report = new ReportResult();
        report.setScore(score);
        report.setComment(score >= 60
                ? "本地判分：表现不错，继续保持！"
                : "本地判分：还需努力，回顾一下相关知识点吧。");
        report.setReason(String.format("本地判分：共 %d 个正确选项，你选对 %d 个、选错 %d 个、漏选 %d 个。",
                totalTrue, correct, wrong, missed));
        report.setSuggest("（AI 服务暂不可用，本次为本地判分，投递建议略）");
        report.setTrueOptions(trueOptions);
        report.setStandardAnswer(StrUtil.isNotBlank(level.getStandardAnswer())
                ? level.getStandardAnswer()
                : "本题正确选项为：" + String.join("、", trueOptions) + "。");
        return report;
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
        return buildLevelVO(level.getId(), level.getLevelName(), level.getLevelDesc(),
                parseOptions(level.getOptions()), level.getDifficulty(),
                level.getTargetSalary(), level.getSource());
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

    private List<LevelOption> parseOptions(String optionsJson) {
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<LevelOption>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "关卡选项解析失败");
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "选项序列化失败");
        }
    }

    private LevelVO buildLevelVO(Long id, String levelName, String levelDesc,
                                 List<LevelOption> options, String difficulty,
                                 Integer targetSalary, String source) {
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
        vo.setSource(source);
        return vo;
    }
}
