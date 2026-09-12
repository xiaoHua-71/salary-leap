package com.xiaohua.config;

import com.xiaohua.service.ai.LevelAiService;
import com.xiaohua.service.ai.QueryRewriteAiService;
import com.xiaohua.service.ai.ReportAiService;
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

    @Bean
    public ReportAiService reportAiService(ChatModel chatModel) {
        return AiServices.builder(ReportAiService.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public QueryRewriteAiService queryRewriteAiService(ChatModel chatModel) {
        return AiServices.builder(QueryRewriteAiService.class)
                .chatModel(chatModel)
                .build();
    }
}
