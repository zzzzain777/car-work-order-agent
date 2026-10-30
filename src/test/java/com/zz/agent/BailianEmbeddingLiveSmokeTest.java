package com.zz.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** One opt-in request using simulated policy text; never runs in the normal test suite. */
@EnabledIfSystemProperty(named = "bailian.embedding.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("bailian-embedding")
class BailianEmbeddingLiveSmokeTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Value("${spring.ai.openai.embedding.api-key}")
    private String apiKey;

    @Test
    void generates1024DimensionsForSimulatedPolicyText() {
        assertFalse(apiKey.isBlank() || apiKey.startsWith("REPLACE_"),
                "先在 config/application-bailian-embedding.yml 填写百炼 API Key");
        long startedAt = System.nanoTime();
        float[] vector = embeddingModel.embed("模拟政策：MX7 试驾需提前预约");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertEquals(1024, vector.length);
        System.out.printf("Bailian embedding smoke: dimensions=%d, elapsedMs=%d%n", vector.length, elapsedMs);
    }
}
