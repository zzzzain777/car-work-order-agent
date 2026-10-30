package com.zz.agent;

import com.zz.agent.model.ModelTextGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 配好真实 DeepSeek 密钥后手动运行；普通测试集不会运行。 */
@EnabledIfSystemProperty(named = "deepseek.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("deepseek")
class DeepSeekLiveSmokeTest {

    @Autowired
    private ModelTextGenerator generator;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Test
    void receivesOneResponseForSimulatedText() {
        assertFalse(apiKey.isBlank() || apiKey.startsWith("REPLACE_"),
                "先在 config/application-deepseek.yml 填写真实 Key");
        long startedAt = System.nanoTime();
        ChatResponse response = generator.generate("这是模拟测试。请只回复：收到");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertNotNull(response.getResult());
        assertFalse(response.getResult().getOutput().getText().isBlank());
        System.out.printf("DeepSeek 联通测试：模型=%s，耗时毫秒=%d，输入令牌=%s，输出令牌=%s%n",
                response.getMetadata().getModel(), elapsedMs,
                response.getMetadata().getUsage() == null ? "未知" : response.getMetadata().getUsage().getPromptTokens(),
                response.getMetadata().getUsage() == null ? "未知" : response.getMetadata().getUsage().getCompletionTokens());
    }
}
