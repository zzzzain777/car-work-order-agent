package com.zz.agent.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按 Markdown 二级规则标题切分政策。 */
public final class PolicyRuleChunker {

    private static final Pattern RULE_HEADING = Pattern.compile("(?m)^##[ \\t]+(.+?)[ \\t]*\\r?$");

    public record Chunk(String id, String sourceRef, String text) {
    }

    public List<Chunk> split(PolicySource policy) {
        Objects.requireNonNull(policy, "政策不能为空");
        String body = policy.body();
        Matcher matcher = RULE_HEADING.matcher(body);
        List<Heading> headings = new ArrayList<>();
        while (matcher.find()) {
            headings.add(new Heading(matcher.start(), matcher.group(1).strip()));
        }

        String idPrefix = policy.policyId() + "@" + policy.version();
        if (headings.isEmpty()) {
            return List.of(new Chunk(idPrefix + "#chunk-1", policy.sourceRef(), body.strip()));
        }

        String commonConditions = body.substring(0, headings.getFirst().start()).strip();
        String fileRef = policy.sourceRef().split("#", 2)[0];
        List<Chunk> chunks = new ArrayList<>(headings.size());
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int end = i + 1 < headings.size() ? headings.get(i + 1).start() : body.length();
            String rule = body.substring(heading.start(), end).strip();
            String text = commonConditions.isEmpty() ? rule : commonConditions + "\n\n" + rule;
            String sourceRef = heading.name().matches("[A-Za-z0-9-]+")
                    ? fileRef + "#" + heading.name().toLowerCase(Locale.ROOT)
                    : fileRef;
            chunks.add(new Chunk(idPrefix + "#chunk-" + (i + 1), sourceRef, text));
        }
        return List.copyOf(chunks);
    }

    private record Heading(int start, String name) {
    }
}
