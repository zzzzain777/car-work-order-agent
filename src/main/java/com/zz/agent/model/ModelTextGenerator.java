package com.zz.agent.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Internal model entry point; callers retain the response metadata for later audit. */
@Component
@Profile("deepseek")
public final class ModelTextGenerator {

    private final ChatClient chatClient;

    public ModelTextGenerator(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public ChatResponse generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return Objects.requireNonNull(chatClient.prompt().user(prompt).call().chatResponse(),
                "model returned no response");
    }
}
