# LangChain4j 接入

## 依赖

- `langchain4j-community-dashscope-spring-boot-starter` 1.4.0-beta10（走通义千问）
- `langchain4j-reactor` 1.4.0-beta10

## 配置（重要：分模块嵌套）

dashscope starter 的配置是分模块的，`QwenChatModel` bean 带条件注解
`@ConditionalOnProperty("langchain4j.community.dashscope.chat-model.api-key")`，
必须用嵌套结构，否则 bean 不会创建（启动报「找不到 ChatModel bean」）：

```yaml
langchain4j:
  community:
    dashscope:
      chat-model:
        api-key: sk-xxx
        model-name: qwen-plus
```

## 关键 API 变化（1.4.0）

- `ChatLanguageModel` 已重命名为 `ChatModel`（`dev.langchain4j.model.chat.ChatModel`）
- `AiServices.builder(...).chatLanguageModel(...)` 改为 `.chatModel(...)`
- 网上旧教程多用旧 API，照着抄会报「找不到 ChatLanguageModel」

## AiServices 概念

只定义接口 + 注解，langchain4j 自动把模型返回的 JSON 绑定成 Java 对象：

```java
public interface LevelAiService {
    @SystemMessage(AiPrompt.GENERATE_LEVEL_SYSTEM)
    @UserMessage("当前薪资：{{salary}}")
    LevelResult generateLevel(@V("salary") int salary);
}
```

- `@SystemMessage`：系统提示词
- `@UserMessage`：用户提示词，支持 `{{变量}}` 模板
- `@V("变量名")`：绑定方法参数
- 返回类型 `LevelResult`：langchain4j 自动解析 JSON

构建方式（`AiConfig`）：

```java
@Bean
public LevelAiService levelAiService(ChatModel chatModel) {
    return AiServices.builder(LevelAiService.class)
            .chatModel(chatModel)
            .build();
}
```
