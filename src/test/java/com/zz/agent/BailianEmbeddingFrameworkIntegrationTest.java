package com.zz.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks the opt-in Bailian profile without sending a request to the paid service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("bailian-embedding")
class BailianEmbeddingFrameworkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<RecordedRequest> LAST_REQUEST = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    @Autowired
    private EmbeddingModel embeddingModel;

    @DynamicPropertySource
    static void fakeBailian(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.embedding.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/compatible-mode/v1");
        registry.add("spring.ai.openai.embedding.api-key", () -> "test-only-key");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void sendsFreeEmbeddingModelToBailianCompatibleEndpointAndReads1024Dimensions() throws Exception {
        float[] vector = embeddingModel.embed("模拟政策：MX7 试驾需提前预约");

        assertEquals(1024, vector.length);
        RecordedRequest request = LAST_REQUEST.get();
        assertEquals("POST", request.method());
        assertEquals("/compatible-mode/v1/embeddings", request.path());
        assertEquals("Bearer test-only-key", request.authorization());
        JsonNode body = JSON.readTree(request.body());
        assertEquals("qwen3.7-text-embedding-flash", body.path("model").asText());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LAST_REQUEST.set(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body));
                String numbers = String.join(",", Collections.nCopies(1024, "0.0"));
                byte[] response = ("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                        + "\"embedding\":[" + numbers + "]}],\"model\":\"qwen3.7-text-embedding-flash\","
                        + "\"usage\":{\"prompt_tokens\":8,\"total_tokens\":8}}")
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
