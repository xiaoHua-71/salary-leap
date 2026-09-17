package com.xiaohua.model.ai;

import lombok.Data;

import java.util.List;

/**
 * AI 生成的查询改写结果（多查询扩展）。
 *
 * <p>用包装 POJO 而不是直接返回 {@code List<String>}，与 {@link LevelResult} 保持一致的
 * 结构化输出风格，JSON 绑定更稳。</p>
 */
@Data
public class RewriteResult {

    /**
     * 扩展出的主题查询列表
     */
    private List<String> queries;
}
