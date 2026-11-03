package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本机诊断只保存模型交互，不记录登录态或客户身份。 */
class PolicyRiskDiagnosticFileSinkTest {

    @TempDir
    Path directory;

    @Test
    void appendsExactModelExchangeAsJsonLine() throws Exception {
        Path file = directory.resolve("risk.jsonl");
        var sink = new PolicyRiskDiagnosticFileSink(new ObjectMapper(), file);
        var exchange = new PolicyRiskReviewService.ModelExchange(PolicyRiskReviewService.PROMPT_VERSION,
                "系统提示", "{\"storeId\":\"STORE-A\"}",
                "{\"verdict\":\"DENY\"}", "fake", 10, 5);

        sink.accept(exchange);
        sink.accept(exchange);

        var lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        var saved = new ObjectMapper().readTree(lines.getFirst());
        assertEquals(exchange.systemPrompt(), saved.path("exchange").path("systemPrompt").asText());
        assertEquals(exchange.userPrompt(), saved.path("exchange").path("userPrompt").asText());
        assertEquals(exchange.rawOutput(), saved.path("exchange").path("rawOutput").asText());
        assertTrue(saved.hasNonNull("recordedAt"));
    }
}
