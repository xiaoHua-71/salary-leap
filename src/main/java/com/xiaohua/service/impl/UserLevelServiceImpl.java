package com.xiaohua.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.mapper.LevelMapper;
import com.xiaohua.mapper.UserLevelMapper;
import com.xiaohua.mapper.UserMapper;
import com.xiaohua.model.entity.Level;
import com.xiaohua.model.entity.User;
import com.xiaohua.model.entity.UserLevel;
import com.xiaohua.model.vo.AnswerSummaryVO;
import com.xiaohua.model.vo.UserLevelVO;
import com.xiaohua.service.UserLevelService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户关卡服务实现
 */
@Service
public class UserLevelServiceImpl extends ServiceImpl<UserLevelMapper, UserLevel> implements UserLevelService {

    @Resource
    private LevelMapper levelMapper;

    @Resource
    private UserMapper userMapper;

    @Override
    public AnswerSummaryVO getAnswerSummary(Long userId) {
        QueryWrapper<UserLevel> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", userId);
        List<UserLevel> records = this.list(queryWrapper);

        int totalCount = records.size();

        double avg = records.stream()
                .mapToInt(r -> r.getScore() == null ? 0 : r.getScore())
                .average()
                .orElse(0);
        double avgScore = Math.round(avg * 10) / 10.0;

        int totalSalaryChange = records.stream()
                .mapToInt(r -> r.getSalaryChange() == null ? 0 : r.getSalaryChange())
                .sum();

        User user = userMapper.selectById(userId);
        int currentSalary = user == null || user.getSalary() == null ? 0 : user.getSalary();

        AnswerSummaryVO vo = new AnswerSummaryVO();
        vo.setTotalCount(totalCount);
        vo.setAvgScore(avgScore);
        vo.setCurrentSalary(currentSalary);
        vo.setTotalSalaryChange(totalSalaryChange);
        return vo;
    }

    @Override
    public List<UserLevelVO> listUserLevels(Long userId) {
        QueryWrapper<UserLevel> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", userId);
        queryWrapper.orderByDesc("createTime");
        List<UserLevel> records = this.list(queryWrapper);

        if (records.isEmpty()) {
            return new ArrayList<>();
        }

        // 批量查关卡名
        List<Long> levelIds = records.stream()
                .map(UserLevel::getLevelId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> levelNameMap = levelMapper.selectBatchIds(levelIds).stream()
                .collect(Collectors.toMap(Level::getId, Level::getLevelName, (a, b) -> a));

        List<UserLevelVO> voList = new ArrayList<>(records.size());
        for (UserLevel record : records) {
            UserLevelVO vo = new UserLevelVO();
            vo.setId(record.getId());
            vo.setLevelId(record.getLevelId());
            vo.setLevelName(levelNameMap.get(record.getLevelId()));
            vo.setScore(record.getScore());
            vo.setSalaryChange(record.getSalaryChange());
            if (record.getCreateTime() != null) {
                vo.setCreateTime(record.getCreateTime().toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime());
            }
            voList.add(vo);
        }
        return voList;
    }
}
