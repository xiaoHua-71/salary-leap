package com.xiaohua.constant;

import com.xiaohua.model.ai.LevelOption;
import com.xiaohua.model.ai.LevelResult;

import java.util.List;

/**
 * 内置保底题：预设题库也查不到时的最后一道防线。
 *
 * <p>代码硬编码、不依赖数据库，任何环境都能出题，保证「永远不会无题可出」。</p>
 */
public class PresetLevels {

    /**
     * 通用保底题（与方向无关）
     */
    public static final LevelResult BUILTIN = buildBuiltin();

    private static LevelResult buildBuiltin() {
        LevelResult result = new LevelResult();
        result.setLevelName("线上接口突然变慢");
        result.setLevelDesc("你负责的一个查询接口，响应时间突然从 100ms 涨到 3s，监控报警、用户开始投诉。"
                + "请从下面选出排查这个问题的合理手段（可多选）。");
        result.setOptions(List.of(
                option("查看接口耗时监控和慢查询日志", true),
                option("用 APM 工具（如 SkyWalking、Arthas）定位耗时方法", true),
                option("检查数据库连接池是否被打满", true),
                option("检查下游依赖（Redis、MQ、第三方接口）是否变慢", true),
                option("立刻重启服务器", false),
                option("把接口限流关掉再说", false),
                option("断定是网络问题，不用排查", false),
                option("加大服务器内存就能解决", false)
        ));
        result.setDifficulty("简单");
        result.setTargetSalary(10000);
        return result;
    }

    private static LevelOption option(String name, boolean trueAnswer) {
        LevelOption option = new LevelOption();
        option.setOptionName(name);
        option.setTrueAnswer(trueAnswer);
        return option;
    }

    private PresetLevels() {
    }
}
