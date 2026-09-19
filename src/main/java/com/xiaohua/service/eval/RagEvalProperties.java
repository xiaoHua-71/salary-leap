package com.xiaohua.service.eval;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 离线评估的配置（{@code rag.eval.*}）。
 *
 * <p><b>为什么用 {@code @ConfigurationProperties} 而不是 {@code @Value}</b>：
 * {@code default-variants} 是个列表，而 {@code @Value} 绑不上 YAML 列表 ——
 * 会<b>静默</b>退化成空列表（不报错，只是永远跑不到变体对比）。这个坑项目在网页抓取那里
 * 已经踩过一次（见 {@code knowledge/19} §九），列表一律走 {@code @ConfigurationProperties}，
 * 并配一个绑定回归测试守住。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.eval")
@Getter
@Setter
public class RagEvalProperties {

    /** 评估集文件（相对项目根目录）；读不到时退回 classpath 同名文件 */
    private String evalSetFile = "docs/rag-eval/eval-set.json";

    /** 报告输出目录，每次评估在其下建一个时间戳子目录 */
    private String reportDir = "docs/rag-eval/reports";

    /** 默认跑哪几个变体；id 见 {@code EvalVariant} */
    private List<String> defaultVariants = List.of("all-on");
}
