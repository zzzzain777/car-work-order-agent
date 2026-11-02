package com.zz.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 只对固定的本机业务系统发送 GET；不会保存 Cookie 或发送业务写请求。 */
public final class HttpCarSystemReadClient implements CarSystemReadClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public HttpCarSystemReadClient(URI baseUri) {
        this.baseUri = requireLocalBaseUri(baseUri);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, java.net.SocketAddress address, IOException error) {
                        // 本地直连失败由 HTTP 调用本身报告，不记录 Cookie。
                    }
                })
                .build();
    }

    @Override
    public Identity currentUser(String cookieHeader) {
        JsonNode data = read("/api/user/current", cookieHeader);
        return new Identity(positiveLong(data, "id"), requiredInt(data, "userRole"),
                requiredInt(data, "userStatus"));
    }

    @Override
    public WorkOrder getWorkOrder(String cookieHeader, long workOrderId) {
        if (workOrderId <= 0) {
            throw new IllegalArgumentException("工单编号必须大于零");
        }
        JsonNode data = read("/api/workOrder/get?id=" + workOrderId, cookieHeader);
        JsonNode advisor = data.path("advisorId");
        Long advisorId = advisor.isMissingNode() || advisor.isNull() ? null : positiveLong(data, "advisorId");
        JsonNode customer = data.path("customerId");
        Long customerId = customer.isMissingNode() || customer.isNull() ? null : positiveLong(data, "customerId");
        return new WorkOrder(positiveLong(data, "id"), requiredInt(data, "type"),
                requiredInt(data, "status"), advisorId, textOrNull(data, "updateTime"), customerId);
    }

    @Override
    public List<Advisor> listAdvisors(String cookieHeader) {
        JsonNode data = read("/api/user/list/advisor", cookieHeader);
        if (!data.isArray()) {
            throw badResponse();
        }
        List<Advisor> result = new ArrayList<>();
        for (JsonNode item : data) {
            result.add(new Advisor(positiveLong(item, "id"), textOrNull(item, "username"),
                    requiredInt(item, "userRole"), requiredInt(item, "userStatus")));
        }
        return List.copyOf(result);
    }

    @Override
    public List<Workload> workloads(String cookieHeader) {
        JsonNode data = read("/api/workOrder/workload", cookieHeader);
        if (!data.isArray()) {
            throw badResponse();
        }
        List<Workload> result = new ArrayList<>();
        for (JsonNode item : data) {
            int processing = requiredInt(item, "processing");
            int total = requiredInt(item, "total");
            if (processing < 0 || total < processing) {
                throw badResponse();
            }
            result.add(new Workload(positiveLong(item, "advisorId"), processing, total));
        }
        return List.copyOf(result);
    }

    private JsonNode read(String path, String cookieHeader) {
        String session = sessionCookie(cookieHeader);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", session)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ReadException(ReadException.Kind.TRANSPORT_FAILURE, 0, "业务系统读取被中断");
        } catch (IOException error) {
            throw new ReadException(ReadException.Kind.TRANSPORT_FAILURE, 0, "业务系统读取失败");
        }
        if (response.statusCode() != 200) {
            throw new ReadException(ReadException.Kind.HTTP_FAILURE, 0, "业务系统返回非成功的 HTTP 状态");
        }
        try {
            JsonNode root = json.readTree(response.body());
            if (root == null || !root.path("code").canConvertToInt()) {
                throw badResponse();
            }
            int code = root.path("code").intValue();
            if (code != 0) {
                ReadException.Kind kind = (code >= 40100 && code <= 40199) || code == 40301
                        ? ReadException.Kind.AUTH_REQUIRED : ReadException.Kind.BUSINESS_FAILURE;
                throw new ReadException(kind, code, "业务系统拒绝了只读请求");
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw badResponse();
            }
            return data;
        } catch (IOException error) {
            throw badResponse();
        }
    }

    private static URI requireLocalBaseUri(URI uri) {
        Objects.requireNonNull(uri, "业务系统地址不能为空");
        String host = uri.getHost();
        if (!"http".equals(uri.getScheme()) || uri.getPort() <= 0
                || !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("只允许配置本机 HTTP 业务系统地址");
        }
        return uri;
    }

    private static String sessionCookie(String header) {
        if (header == null || header.isBlank()) {
            throw new ReadException(ReadException.Kind.AUTH_REQUIRED, 40100, "缺少登录会话");
        }
        String value = null;
        for (String part : header.split(";")) {
            String token = part.strip();
            if (token.startsWith("SESSION=")) {
                if (value != null) {
                    throw new ReadException(ReadException.Kind.AUTH_REQUIRED, 40100, "登录会话重复");
                }
                value = token.substring("SESSION=".length());
            }
        }
        if (value == null || !value.matches("[A-Za-z0-9._~+/%=-]{1,512}")) {
            throw new ReadException(ReadException.Kind.AUTH_REQUIRED, 40100, "缺少有效登录会话");
        }
        return "SESSION=" + value;
    }

    private static long positiveLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw badResponse();
        }
        return value.longValue();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw badResponse();
        }
        return value.intValue();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static ReadException badResponse() {
        return new ReadException(ReadException.Kind.BAD_RESPONSE, 0, "业务系统只读响应格式不正确");
    }
}
