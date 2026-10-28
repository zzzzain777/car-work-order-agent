package com.zz.agent;

import com.zz.agent.model.ModelTextGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Run explicitly after configuring a real DeepSeek key; never part of the default test suite. */
@EnabledIfSystemProperty(named = "deepseek.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("deepseek")
class DeepSeekLiveSmokeTest {

    @Autowired
    private ModelTextGenerator generator;

    @Test
    void receivesOneResponseForSimulatedText() {
        long startedAt = System.nanoTime();
        ChatResponse response = generator.generate("这是模拟测试。请只回复：收到");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertNotNull(response.getResult());
        assertFalse(response.getResult().getOutput().getText().isBlank());
        System.out.printf("DeepSeek smoke: model=%s, elapsedMs=%d, promptTokens=%s, completionTokens=%s%n",
                response.getMetadata().getModel(), elapsedMs,
                response.getMetadata().getUsage() == null ? "unknown" : response.getMetadata().getUsage().getPromptTokens(),
                response.getMetadata().getUsage() == null ? "unknown" : response.getMetadata().getUsage().getCompletionTokens());
    }
}
