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
    @UserMessage("当前薪资：{{salary}}")
    LevelResult generateLevel(@V("salary") int salary);
}
