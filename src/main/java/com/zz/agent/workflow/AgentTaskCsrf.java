package com.zz.agent.workflow;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** 本机任务写入口的短时令牌；只绑定会话，不保存或输出 Cookie。 */
public final class AgentTaskCsrf {

    public record Issued(String token, Instant expiresAt) {
    }

    private static final long VALID_SECONDS = 20 * 60;
    private final Clock clock;
    private final byte[] secret = new byte[32];
    private final SecureRandom random = new SecureRandom();

    public AgentTaskCsrf(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        random.nextBytes(secret);
    }

    public Issued issue(String cookieHeader) {
        String session = session(cookieHeader);
        if (session == null) {
            throw new IllegalArgumentException("缺少有效管理员会话");
        }
        long deadline = clock.instant().getEpochSecond() + VALID_SECONDS;
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String first = Long.toString(deadline) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(session, first));
        return new Issued(first + "." + signature, Instant.ofEpochSecond(deadline));
    }

    public boolean allowed(String cookieHeader, String origin, String scheme,
                           String host, int port, String token) {
        String session = session(cookieHeader);
        if (session == null || origin == null || scheme == null || host == null || token == null
                || token.length() > 200 || !"http".equals(scheme)
                || !("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))
                || port <= 0 || !origin.equals(scheme + "://" + host + ":" + port)) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !parts[0].matches("[0-9]{1,12}")
                || !parts[1].matches("[A-Za-z0-9_-]{22}")
                || !parts[2].matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        long expires;
        try {
            expires = Long.parseLong(parts[0]);
        } catch (NumberFormatException invalid) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (expires <= now || expires > now + VALID_SECONDS) {
            return false;
        }
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        byte[] expected = sign(session, parts[0] + "." + parts[1]);
        return MessageDigest.isEqual(expected, supplied);
    }

    private byte[] sign(String session, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal((session + "\n" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成确认令牌", error);
        }
    }

    private static String session(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = null;
        for (String part : header.split(";")) {
            String text = part.strip();
            if (text.startsWith("SESSION=")) {
                if (value != null) {
                    return null;
                }
                value = text.substring("SESSION=".length());
            }
        }
        return value != null && value.matches("[A-Za-z0-9._~+/%=-]{1,512}") ? value : null;
    }
}
