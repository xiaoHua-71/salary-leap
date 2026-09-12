package com.xiaohua.service.ai;

import com.xiaohua.constant.AiPrompt;
import com.xiaohua.model.ai.RewriteResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 查询改写 AI 服务：把「学习方向」标签扩展成若干条具体的技术主题查询。
 *
 * <p>调用方是 {@code QueryRewriter}，它负责兜底 —— 这里只管问模型，
 * 失败的处理（退回原查询）在调用方做。</p>
 */
public interface QueryRewriteAiService {

    @SystemMessage(AiPrompt.REWRITE_QUERY_SYSTEM)
    @UserMessage("""
            学习方向：{{direction}}

            请给出 {{count}} 条主题查询。
            """)
    RewriteResult rewrite(@V("direction") String direction, @V("count") int count);
}
