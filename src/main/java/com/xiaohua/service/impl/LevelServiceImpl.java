package com.xiaohua.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohua.common.ErrorCode;
import com.xiaohua.exception.BusinessException;
import com.xiaohua.mapper.LevelMapper;
import com.xiaohua.model.ai.LevelOption;
import com.xiaohua.model.ai.LevelResult;
import com.xiaohua.model.entity.Level;
import com.xiaohua.model.vo.LevelVO;
import com.xiaohua.service.LevelService;
import com.xiaohua.service.ai.LevelAiService;
import jakarta.annotation.Resource;
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
}
