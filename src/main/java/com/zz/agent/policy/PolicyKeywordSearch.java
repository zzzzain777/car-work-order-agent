package com.zz.agent.policy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An offline keyword baseline over eligible simulated policy text. */
public final class PolicyKeywordSearch {

    private static final Pattern WORDS =
            Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");

    private final PolicyMetadataFilter metadataFilter = new PolicyMetadataFilter();
    private final PolicyRuleChunker chunker = new PolicyRuleChunker();

    public enum Status {
        NO_APPLICABLE_POLICY,
        NO_KEYWORD_MATCH,
        MATCHES,
        REVIEW_REQUIRED
    }

    public record Hit(String chunkId, String policyId, int version, String sourceRef, String text, int score) {
    }

    public record Result(Status status, List<Hit> hits) {
        public Result {
            hits = List.copyOf(hits);
        }
    }

    public Result find(
            List<PolicySource> policies,
            String storeId,
            String modelCode,
            OffsetDateTime appointmentAt,
            String question
    ) {
        Set<String> keywords = keywords(Objects.requireNonNull(question, "question"));
        PolicyMetadataFilter.Result eligible = metadataFilter.find(policies, storeId, modelCode, appointmentAt);
        if (eligible.outcome() == PolicyMetadataFilter.Outcome.NO_MATCH) {
            return new Result(Status.NO_APPLICABLE_POLICY, List.of());
        }

        List<Hit> hits = new ArrayList<>();
        for (PolicySource policy : eligible.policies()) {
            for (PolicyRuleChunker.Chunk chunk : chunker.split(policy)) {
                String searchableText = chunk.text().toLowerCase(Locale.ROOT);
                int score = (int) keywords.stream()
                        .filter(searchableText::contains)
                        .count();
                if (score > 0 || eligible.outcome() == PolicyMetadataFilter.Outcome.MULTIPLE_MATCHES) {
                    hits.add(new Hit(chunk.id(), policy.policyId(), policy.version(),
                            chunk.sourceRef(), chunk.text(), score));
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(Hit::policyId)
                .thenComparing(Hit::chunkId));

        if (eligible.outcome() == PolicyMetadataFilter.Outcome.MULTIPLE_MATCHES) {
            return new Result(Status.REVIEW_REQUIRED, hits);
        }
        return new Result(hits.isEmpty() ? Status.NO_KEYWORD_MATCH : Status.MATCHES, hits);
    }

    private static Set<String> keywords(String question) {
        Set<String> words = new LinkedHashSet<>();
        Matcher matcher = WORDS.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                for (int i = 0; i + 1 < word.length(); i++) {
                    words.add(word.substring(i, i + 2));
                }
            } else {
                words.add(word);
            }
        }
        return words;
    }
}
