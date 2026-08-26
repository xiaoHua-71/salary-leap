package com.xiaohua.config;

import com.xiaohua.service.ai.LevelAiService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j AI 服务配置
 */
@Configuration
public class AiConfig {

    @Bean
    public LevelAiService levelAiService(ChatModel chatModel) {
        return AiServices.builder(LevelAiService.class)
                .chatModel(chatModel)
                .build();
    }
}
