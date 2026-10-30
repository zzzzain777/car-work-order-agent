package com.zz.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zz.agent.model.ModelTextGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 通过本地 HTTP 服务检查 DeepSeek 接线，不使用真实密钥。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("deepseek")
class DeepSeekFrameworkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<RecordedRequest> LAST_REQUEST = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    @Autowired
    private ModelTextGenerator generator;

    @DynamicPropertySource
    static void fakeDeepSeek(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("spring.ai.openai.api-key", () -> "test-only-key");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void sendsDeepSeekModelAndReceivesResponseThroughChatClient() throws Exception {
        ChatResponse response = generator.generate("只回答 OK");

        assertEquals("OK", response.getResult().getOutput().getText());
        assertEquals(3, response.getMetadata().getUsage().getTotalTokens());
        RecordedRequest request = LAST_REQUEST.get();
        assertEquals("POST", request.method());
        assertEquals("/chat/completions", request.path());
        assertEquals("Bearer test-only-key", request.authorization());
        JsonNode body = JSON.readTree(request.body());
        assertEquals("deepseek-flash", body.path("model").asText());
        assertEquals("只回答 OK", body.path("messages").get(0).path("content").asText());
    }

    @Test
    void rejectsEmptyPromptBeforeAnyModelCall() {
        LAST_REQUEST.set(null);
        assertThrows(IllegalArgumentException.class, () -> generator.generate("  "));
        assertNull(LAST_REQUEST.get());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_REQUEST.set(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body));
                byte[] response = ("{\"id\":\"test-completion\",\"object\":\"chat.completion\","
                        + "\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private record RecordedRequest(String method, String path, String authorization, String body) {
    }
}
