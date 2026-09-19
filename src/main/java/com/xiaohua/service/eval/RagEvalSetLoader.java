package com.xiaohua.service.eval;

import com.xiaohua.model.eval.EvalSet;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 评估集加载器：从<b>文件系统</b>读评估集，读不到再退回 classpath。
 *
 * <p>为什么不直接放 classpath：评估集是要<b>反复打磨</b>的东西（检索改一次就可能加几条样本），
 * 放 resources 下每次改都得重新编译打包。放项目目录里改完立刻生效，
 * 而且能进 git 让样本的演进有历史。</p>
 *
 * <p>classpath 那份只是兜底（比如部署到没有源码的机器上跑冒烟）。</p>
 */
@Component
@Slf4j
public class RagEvalSetLoader {

    /** classpath 兜底位置 */
    private static final String CLASSPATH_FALLBACK = "rag-eval/eval-set.json";

    @Resource
    private RagEvalProperties properties;

    /** 当前配置的评估集文件路径（写进报告，方便回溯这份指标是哪份样本跑出来的） */
    public String getEvalSetFile() {
        return properties.getEvalSetFile();
    }

    /**
     * 读取并解析评估集。
     *
     * @throws IllegalStateException 两处都找不到文件，或内容不合法
     */
    public EvalSet load() {
        String content = readFromFileSystem();
        String origin = properties.getEvalSetFile();
        if (content == null) {
            content = readFromClasspath();
            origin = "classpath:" + CLASSPATH_FALLBACK;
        }
        if (content == null) {
            throw new IllegalStateException("找不到评估集文件：" + properties.getEvalSetFile()
                    + "（classpath " + CLASSPATH_FALLBACK + " 也没有）；"
                    + "请先按 knowledge/25-评估集与指标.md 建一份");
        }
        try {
            EvalSet evalSet = EvalSet.parse(content);
            log.info("评估集加载完成：{} 条用例（启用 {} 条），来源 {}",
                    evalSet.cases().size(), evalSet.enabledCases().size(), origin);
            return evalSet;
        } catch (IllegalArgumentException e) {
            // 把文件位置带上：评估集是人手写的，出错时必须能一眼定位到改哪个文件
            throw new IllegalStateException("评估集 " + origin + " 内容不合法：" + e.getMessage(), e);
        }
    }

    private String readFromFileSystem() {
        Path path = Paths.get(properties.getEvalSetFile());
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取评估集文件失败 [{}]: {}", properties.getEvalSetFile(), e.getMessage());
            return null;
        }
    }

    private String readFromClasspath() {
        ClassPathResource resource = new ClassPathResource(CLASSPATH_FALLBACK);
        if (!resource.exists()) {
            return null;
        }
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 classpath 评估集失败: {}", e.getMessage());
            return null;
        }
    }
}
