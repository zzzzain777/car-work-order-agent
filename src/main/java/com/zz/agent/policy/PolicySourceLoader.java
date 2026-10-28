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
        Objects.requireNonNull(directory, "directory");
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
                        "Duplicate policy version: " + source.policyId() + "@" + source.version());
            }
            sources.add(source);
        }
        return List.copyOf(sources);
    }

    private PolicySource parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher frontMatter = FRONT_MATTER.matcher(text);
        if (!frontMatter.find()) {
            throw new IllegalArgumentException("Policy is missing YAML front matter: " + file);
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(new SafeConstructor(options)).load(frontMatter.group(1));
        if (!(parsed instanceof Map<?, ?> metadata)) {
            throw new IllegalArgumentException("Policy metadata must be a mapping: " + file);
        }

        if (!Boolean.TRUE.equals(metadata.get("isSimulated"))) {
            throw new IllegalArgumentException("Policy must be explicitly marked simulated: " + file);
        }
        int version = requiredVersion(metadata, file);
        OffsetDateTime effectiveFrom = OffsetDateTime.parse(requiredString(metadata, "effectiveFrom", file));
        OffsetDateTime effectiveTo = OffsetDateTime.parse(requiredString(metadata, "effectiveTo", file));
        if (!effectiveFrom.toInstant().isBefore(effectiveTo.toInstant())) {
            throw new IllegalArgumentException("Policy effective period is empty or reversed: " + file);
        }

        String body = text.substring(frontMatter.end());
        if (body.isBlank()) {
            throw new IllegalArgumentException("Policy body is empty: " + file);
        }
        String sourceRef = requiredString(metadata, "sourceRef", file);
        String expectedRef = "data/policies/" + file.getFileName();
        if (!sourceRef.equals(expectedRef) && !sourceRef.startsWith(expectedRef + "#")) {
            throw new IllegalArgumentException("Policy sourceRef points to another file: " + file);
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
            throw new IllegalArgumentException("Policy version must be a positive integer: " + file);
        }
        return version;
    }

    private static String requiredString(Map<?, ?> metadata, String key, Path file) {
        Object value = metadata.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Policy is missing " + key + ": " + file);
        }
        return text;
    }

    private record PolicyKey(String policyId, int version) {
    }
}
