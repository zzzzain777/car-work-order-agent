package com.zz.agent.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 模型调用入口；调用方保留响应元数据供后续审计。 */
@Component
@Profile("deepseek")
public final class ModelTextGenerator {

    private final ChatClient chatClient;

    public ModelTextGenerator(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public ChatResponse generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        return Objects.requireNonNull(chatClient.prompt().user(prompt).call().chatResponse(),
                "模型没有返回响应");
    }
}
