package com.xiaohua.model.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 评估集：一批「查询 → 期望命中的文章」样本。
 *
 * <p>存成 JSON 放在项目里，因为它是<b>要反复打磨</b>的东西 —— 检索改一次就可能要加几条样本。</p>
 *
 * <p>{@link #parse(String)} 是纯函数，不碰文件系统也不依赖 Spring，所以解析与校验逻辑
 * 可以完全离线单测（用错一个字段名的代价是「评估跑了但少测了一批用例」，很难发现）。</p>
 *
 * @param version 格式版本，将来改结构时用来兼容
 * @param cases   用例列表
 */
public record EvalSet(int version, List<EvalCase> cases) {

    /** 宽松反序列化：评估集里多写个字段（比如临时加注释）不该让整次评估跑不起来 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 解析评估集 JSON 并校验。
     *
     * <p>校验失败的报错要带上用例标识 —— 评估集是人手写的，出错时得让人一眼找到那一行。</p>
     *
     * @throws IllegalArgumentException JSON 不合法、没有用例、或某条用例缺字段
     */
    public static EvalSet parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("评估集内容为空");
        }
        EvalSet parsed;
        try {
            parsed = MAPPER.readValue(json, EvalSet.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("评估集 JSON 解析失败: " + e.getOriginalMessage(), e);
        }
        if (parsed == null || parsed.cases() == null || parsed.cases().isEmpty()) {
            throw new IllegalArgumentException("评估集里没有任何用例（cases 为空）");
        }
        List<EvalCase> cases = new ArrayList<>(parsed.cases());
        for (EvalCase item : cases) {
            validate(item);
        }
        return new EvalSet(parsed.version(), List.copyOf(cases));
    }

    private static void validate(EvalCase item) {
        String where = item.id() == null || item.id().isBlank() ? "（缺少 id 的用例）" : "用例 [" + item.id() + "]";
        if (item.query() == null || item.query().isBlank()) {
            throw new IllegalArgumentException(where + " 缺少 query 字段");
        }
        if (item.expectedSources() == null || item.expectedSources().isEmpty()) {
            throw new IllegalArgumentException(where + " 的 expectedSources 为空，至少写一个期望命中的来源");
        }
        for (String expected : item.expectedSources()) {
            if (expected == null || expected.isBlank()) {
                throw new IllegalArgumentException(where + " 的 expectedSources 里有空值");
            }
        }
    }

    /**
     * 本次要跑的用例（过滤掉 {@code enabled: false} 的）。
     */
    public List<EvalCase> enabledCases() {
        return cases.stream().filter(EvalCase::isEnabled).toList();
    }
}
