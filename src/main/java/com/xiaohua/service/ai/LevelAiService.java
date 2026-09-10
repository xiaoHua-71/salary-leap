package com.xiaohua.service.ai;

import com.xiaohua.constant.AiPrompt;
import com.xiaohua.model.ai.LevelResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 关卡生成 AI 服务
 */
public interface LevelAiService {

    @SystemMessage(AiPrompt.GENERATE_LEVEL_SYSTEM)
    @UserMessage("""
            当前薪资：{{salary}}
            学习方向：{{direction}}

            ## 请优先参考下面的知识出题，不要超出这些知识的范围编造内容；若括号内提示「未检索到相关知识」，则改用你的通用知识出题
            {{knowledge}}
            """)
    LevelResult generateLevel(@V("salary") int salary,
                              @V("direction") String direction,
                              @V("knowledge") String knowledge);
}
