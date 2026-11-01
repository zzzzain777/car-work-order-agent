package com.zz.agent.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只用本机模拟响应验证下游契约，不需要管理员账号。 */
class HttpCarSystemReadClientTest {

    private HttpServer server;
    private HttpCarSystemReadClient client;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/user/current", exchange -> {
            record(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Cookie"));
            respond(exchange, """
                    {"code":0,"data":{"id":9001,"userRole":2,"userStatus":0,"phone":"不应保留"}}
                    """);
        });
        server.createContext("/api/workOrder/get", exchange -> {
            record(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Cookie"));
            respond(exchange, """
                    {"code":0,"data":{"id":1001,"type":1,"status":0,"advisorId":null,
                     "title":"模拟试驾","content":"模拟客户描述","updateTime":"2026-10-28 09:00:00",
                     "customerName":"不应保留"}}
                    """);
        });
        server.createContext("/api/user/list/advisor", exchange -> {
            record(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Cookie"));
            respond(exchange, """
                    {"code":0,"data":[{"id":301,"username":"模拟顾问甲","userRole":1,"userStatus":0,
                     "email":"不应保留"}]}
                    """);
        });
        server.createContext("/api/workOrder/workload", exchange -> {
            record(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Cookie"));
            respond(exchange, "{" + "\"code\":0,\"data\":[{\"advisorId\":301,\"processing\":1,\"total\":3}]}");
        });
        server.start();
        client = new HttpCarSystemReadClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsOnlyFourGetEndpointsAndForwardsOnlySessionCookie() {
        String cookies = "theme=dark; SESSION=test-session-123; other=value";

        assertEquals(9001L, client.currentUser(cookies).id());
        assertEquals(1001L, client.getWorkOrder(cookies, 1001).id());
        assertEquals(301L, client.listAdvisors(cookies).getFirst().id());
        assertEquals(1, client.workloads(cookies).getFirst().processing());
        assertEquals(4, requests.size());
        assertTrue(requests.stream().allMatch(value -> value.contains("Cookie=SESSION=test-session-123")));
        assertTrue(requests.stream().allMatch(value -> value.startsWith("GET")));
        assertTrue(requests.stream().anyMatch(value -> value.startsWith("GET id=1001")));
    }

    @Test
    void rejectsMissingSessionBeforeAnyNetworkCall() {
        assertThrows(CarSystemReadClient.ReadException.class, () -> client.currentUser("theme=dark"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void rejectsBusinessErrorEvenWhenHttpStatusIs200() {
        server.removeContext("/api/workOrder/workload");
        server.createContext("/api/workOrder/workload", exchange -> respond(exchange,
                "{\"code\":50000,\"data\":null,\"message\":\"内部错误\"}"));

        CarSystemReadClient.ReadException error = assertThrows(CarSystemReadClient.ReadException.class,
                () -> client.workloads("SESSION=test-session-123"));
        assertEquals(CarSystemReadClient.ReadException.Kind.BUSINESS_FAILURE, error.kind());
        assertEquals(50000, error.businessCode());
    }

    @Test
    void treatsForbiddenAccountAsInvalidSession() {
        server.removeContext("/api/user/current");
        server.createContext("/api/user/current", exchange -> respond(exchange,
                "{\"code\":40301,\"data\":null,\"message\":\"已封禁\"}"));

        CarSystemReadClient.ReadException error = assertThrows(CarSystemReadClient.ReadException.class,
                () -> client.currentUser("SESSION=test-session-123"));
        assertEquals(CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED, error.kind());
    }

    private void record(String method, String cookie) {
        requests.add(method + " Cookie=" + cookie);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
