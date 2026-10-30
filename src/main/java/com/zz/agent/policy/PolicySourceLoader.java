package com.zz.agent.policy;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PolicySourceLoader {

    private static final Pattern FRONT_MATTER =
            Pattern.compile("\\A---\\R(.*?)\\R---(?:\\R|\\z)", Pattern.DOTALL);

    public List<PolicySource> load(Path directory) throws IOException {
        Objects.requireNonNull(directory, "政策目录不能为空");
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .filter(path -> !path.getFileName().toString().startsWith("_"))
                    .sorted()
                    .toList();
        }

        List<PolicySource> sources = new ArrayList<>(files.size());
        Set<PolicyKey> seenVersions = new HashSet<>();
        for (Path file : files) {
            PolicySource source = parse(file);
            if (!seenVersions.add(new PolicyKey(source.policyId(), source.version()))) {
                throw new IllegalArgumentException(
                        "政策编号与版本重复：" + source.policyId() + "@" + source.version());
            }
            sources.add(source);
        }
        return List.copyOf(sources);
    }

    private PolicySource parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher frontMatter = FRONT_MATTER.matcher(text);
        if (!frontMatter.find()) {
            throw new IllegalArgumentException("政策文件缺少 YAML 元数据：" + file);
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(new SafeConstructor(options)).load(frontMatter.group(1));
        if (!(parsed instanceof Map<?, ?> metadata)) {
            throw new IllegalArgumentException("政策元数据必须是键值映射：" + file);
        }

        if (!Boolean.TRUE.equals(metadata.get("isSimulated"))) {
            throw new IllegalArgumentException("政策必须明确标记为模拟资料：" + file);
        }
        int version = requiredVersion(metadata, file);
        OffsetDateTime effectiveFrom = OffsetDateTime.parse(requiredString(metadata, "effectiveFrom", file));
        OffsetDateTime effectiveTo = OffsetDateTime.parse(requiredString(metadata, "effectiveTo", file));
        if (!effectiveFrom.toInstant().isBefore(effectiveTo.toInstant())) {
            throw new IllegalArgumentException("政策生效时间范围为空或前后颠倒：" + file);
        }

        String body = text.substring(frontMatter.end());
        if (body.isBlank()) {
            throw new IllegalArgumentException("政策正文为空：" + file);
        }
        String sourceRef = requiredString(metadata, "sourceRef", file);
        String expectedRef = "data/policies/" + file.getFileName();
        if (!sourceRef.equals(expectedRef) && !sourceRef.startsWith(expectedRef + "#")) {
            throw new IllegalArgumentException("政策来源引用指向其他文件：" + file);
        }

        return new PolicySource(
                requiredString(metadata, "policyId", file),
                version,
                requiredString(metadata, "title", file),
                true,
                requiredString(metadata, "storeId", file),
                requiredString(metadata, "modelCode", file),
                requiredString(metadata, "ruleType", file),
                effectiveFrom,
                effectiveTo,
                requiredString(metadata, "status", file),
                sourceRef,
                body
        );
    }

    private static int requiredVersion(Map<?, ?> metadata, Path file) {
        Object value = metadata.get("version");
        if (!(value instanceof Integer version) || version < 1) {
            throw new IllegalArgumentException("政策版本必须是正整数：" + file);
        }
        return version;
    }

    private static String requiredString(Map<?, ?> metadata, String key, Path file) {
        Object value = metadata.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("政策缺少字段 " + key + "：" + file);
        }
        return text;
    }

    private record PolicyKey(String policyId, int version) {
    }
}
