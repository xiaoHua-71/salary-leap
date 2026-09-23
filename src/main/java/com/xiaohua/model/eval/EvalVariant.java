package com.xiaohua.model.eval;

import com.xiaohua.model.ai.RetrieveOptions;

import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * 检索变体：在同一套生产配置上关掉/改动某个环节，用来量出这个环节贡献了多少。
 *
 * <p>这是整个评估子系统<b>最核心的一件东西</b>。项目历史上反复出现「改完不知道有没有变好」
 * 的困境（查询改写的收益就是靠人工对着 top-3 来源一点点比出来的），
 * 有了变体就能一次跑出「全开 / 关改写 / 关混合 / 裸向量」几组指标放在同一张表里比。</p>
 *
 * <p>为什么写在 Java 枚举里而不是 yml 里：变体就是「改哪几个参数」的表达式，
 * 用 {@code UnaryOperator<RetrieveOptions>} 表达一目了然、类型安全。
 * 代价是加变体要重新编译 —— 相比 yml 里配一堆可空字段再写合并逻辑，这个更划算。</p>
 *
 * <p>跑哪几组由配置 {@code rag.eval.default-variants} 决定，请求体也能覆盖，
 * 所以不是所有变体每次都会跑（成本考虑，见文档）。</p>
 */
public enum EvalVariant {

    /** 全开，等于线上真实配置 —— 报告第一行就是当前生产水平 */
    ALL_ON("all-on", "全开（生产基线）", base -> base),

    NO_REWRITE("no-rewrite", "关查询改写", base -> base.withRewrite(false)),

    NO_HYBRID("no-hybrid", "关混合检索（只剩向量一路）", base -> base.withHybrid(false)),

    NO_RERANK("no-rerank", "关 rerank 精排", base -> base.withRerank(false)),

    /** 改造前的原始形态：只有向量召回，没有混合检索也没有精排 */
    VECTOR_ONLY("vector-only", "裸向量（关混合 + 关 rerank）",
            base -> base.withHybrid(false).withRerank(false)),

    /** 关掉来源多样性限制，看一篇文章占满 topK 时命中率是涨还是跌 */
    NO_DIVERSITY("no-diversity", "全开但不限制同一来源",
            base -> base.withMaxPerSource(0)),

    /** 多给两条，看还有多少正确答案排在 4、5 位 —— 也就是「召回的天花板」 */
    TOPK_5("topk-5", "全开但取前 5 条",
            base -> base.withTopK(5));

    private final String id;
    private final String label;
    private final UnaryOperator<RetrieveOptions> override;

    EvalVariant(String id, String label, UnaryOperator<RetrieveOptions> override) {
        this.id = id;
        this.label = label;
        this.override = override;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 在生产配置的基础上应用本变体的开关。
     */
    public RetrieveOptions apply(RetrieveOptions base) {
        return override.apply(base);
    }

    /**
     * 按 id 找变体，id 对不上时抛异常并列出全部可用 id —— 评估入口的入参来自请求体，报错要能自解释。
     */
    public static EvalVariant byId(String id) {
        return Arrays.stream(values())
                .filter(variant -> variant.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知的评估变体 [" + id + "]，可用：" + allIds()));
    }

    /**
     * 解析一组 id（比如配置里的 {@code default-variants}），空列表时回退到「只跑全开基线」。
     */
    public static List<EvalVariant> parseAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of(ALL_ON);
        }
        return ids.stream().map(EvalVariant::byId).collect(Collectors.toList());
    }

    public static String allIds() {
        return Arrays.stream(values()).map(EvalVariant::getId).collect(Collectors.joining(", "));
    }
}
