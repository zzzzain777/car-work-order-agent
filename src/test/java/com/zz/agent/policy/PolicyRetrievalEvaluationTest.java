package com.zz.agent.policy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replays the draft policy-only cases and keeps a reviewable baseline report. */
class PolicyRetrievalEvaluationTest {

    private static final Path CASES = Path.of("data/eval/retrieval/keyword-baseline-v1.tsv");
    private static final Path OUTPUT = Path.of("target/eval/keyword-baseline-v1.tsv");
    private static final Path SNAPSHOT = Path.of("data/eval/results/keyword-baseline-v1.tsv");

    @Test
    void replayDraftCasesAndMatchTheSavedReport() throws Exception {
        List<RetrievalCase> cases = readCases();
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        PolicyKeywordSearch search = new PolicyKeywordSearch();
        StringBuilder report = new StringBuilder();
        report.append("# scope=draft-policy-retrieval-only\n");
        report.append("# casesSha256=").append(sha256(Files.readAllBytes(CASES))).append('\n');
        report.append("# corpusSha256=").append(corpusHash(policies)).append('\n');
        report.append("caseId\texpectedStatus\tactualStatus\texpectedPolicyIds\tactualPolicyIds")
                .append("\texpectedSourceRefs\tactualSourceRefs\trankedHits\tpass\n");

        int passed = 0;
        int recallPassed = 0;
        int recallTotal = 0;
        for (RetrievalCase item : cases) {
            PolicyKeywordSearch.Result actual = search.find(policies, item.storeId(), item.modelCode(),
                    item.appointmentAt(), item.question());
            Set<String> actualIds = actual.hits().stream().map(PolicyKeywordSearch.Hit::policyId)
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> actualRefs = actual.hits().stream().map(PolicyKeywordSearch.Hit::sourceRef)
                    .collect(Collectors.toCollection(TreeSet::new));
            boolean pass = actual.status() == item.expectedStatus()
                    && actualIds.equals(item.expectedPolicyIds())
                    && actualRefs.equals(item.expectedSourceRefs());
            if (pass) {
                passed++;
            }
            if (!item.expectedPolicyIds().isEmpty()) {
                recallTotal++;
                Set<String> topTwo = actual.hits().stream().limit(2)
                        .map(PolicyKeywordSearch.Hit::policyId).collect(Collectors.toSet());
                if (topTwo.containsAll(item.expectedPolicyIds())) {
                    recallPassed++;
                }
            }

            String rankedHits = actual.hits().stream()
                    .map(hit -> hit.chunkId() + ":" + hit.score())
                    .collect(Collectors.joining("|"));
            report.append(String.join("\t", item.caseId(), item.expectedStatus().name(),
                    actual.status().name(), join(item.expectedPolicyIds()), join(actualIds),
                    join(item.expectedSourceRefs()), join(actualRefs), rankedHits.isEmpty() ? "-" : rankedHits,
                    pass ? "PASS" : "FAIL")).append('\n');
        }
        report.append("# exactCasePass=").append(passed).append('/').append(cases.size()).append('\n');
        report.append("# recallAt2=").append(recallPassed).append('/').append(recallTotal).append('\n');

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
        assertEquals(cases.size(), passed, "逐例结果见 " + OUTPUT);
        assertTrue(Files.exists(SNAPSHOT), "请先人工检查 " + OUTPUT + "，再保存基线快照");
        assertEquals(Files.readString(SNAPSHOT), report.toString(), "基线快照与本次运行结果不同");
    }

    private static List<RetrievalCase> readCases() throws Exception {
        List<String> lines = Files.readAllLines(CASES, StandardCharsets.UTF_8);
        assertEquals("caseId\tstoreId\tmodelCode\tappointmentAt\tquestion\texpectedStatus"
                + "\texpectedPolicyIds\texpectedSourceRefs", lines.getFirst());
        List<RetrievalCase> cases = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            assertEquals(8, cells.length, "评测输入列数不正确：" + line);
            cases.add(new RetrievalCase(cells[0], cells[1], cells[2], OffsetDateTime.parse(cells[3]),
                    cells[4], PolicyKeywordSearch.Status.valueOf(cells[5]), values(cells[6]), values(cells[7])));
        }
        return cases;
    }

    private static Set<String> values(String cell) {
        return cell.equals("-") ? Set.of() : Set.copyOf(Arrays.asList(cell.split("\\|")));
    }

    private static String join(Set<String> values) {
        return values.isEmpty() ? "-" : String.join("|", new TreeSet<>(values));
    }

    private static String corpusHash(List<PolicySource> policies) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (PolicySource policy : policies) {
            String file = policy.sourceRef().split("#", 2)[0];
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(Path.of(file)));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private record RetrievalCase(
            String caseId,
            String storeId,
            String modelCode,
            OffsetDateTime appointmentAt,
            String question,
            PolicyKeywordSearch.Status expectedStatus,
            Set<String> expectedPolicyIds,
            Set<String> expectedSourceRefs
    ) {
    }
}
