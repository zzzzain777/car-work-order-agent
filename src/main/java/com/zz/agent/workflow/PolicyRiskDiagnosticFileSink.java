package com.zz.agent.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 显式开启时把模拟政策的原始模型交互写到 Git 忽略的本机文件。 */
public final class PolicyRiskDiagnosticFileSink
        implements Consumer<PolicyRiskReviewService.ModelExchange> {

    private final ObjectMapper json;
    private final Path file;

    public PolicyRiskDiagnosticFileSink(ObjectMapper json, Path file) {
        this.json = Objects.requireNonNull(json, "JSON 编解码器不能为空");
        this.file = Objects.requireNonNull(file, "诊断文件路径不能为空");
    }

    @Override
    public synchronized void accept(PolicyRiskReviewService.ModelExchange exchange) {
        Objects.requireNonNull(exchange, "模型交互不能为空");
        try {
            String line = json.writeValueAsString(Map.of(
                    "recordedAt", Instant.now().toString(), "exchange", exchange));
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("政策风险诊断编码失败", error);
        } catch (IOException error) {
            throw new UncheckedIOException("政策风险诊断写入失败", error);
        }
    }
}
