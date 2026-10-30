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

/** 重放固定的政策检索案例，保留包括失败案例在内的可核查报告。 */
class PolicyRetrievalEvaluationTest {

    private static final Path POLICY_DIRECTORY = Path.of("data/policies");

    @Test
    void replayDraftCasesAndMatchTheSavedReport() throws Exception {
        replay(Path.of("data/eval/retrieval/keyword-baseline-v1.tsv"),
                Path.of("target/eval/keyword-baseline-v1.tsv"),
                Path.of("data/eval/results/keyword-baseline-v1.tsv"),
                "draft-policy-retrieval-only", true);
    }

    @Test
    void replayHoldoutAndKeepFailuresInTheSavedReport() throws Exception {
        replay(Path.of("data/eval/retrieval/keyword-holdout-v1.tsv"),
                Path.of("target/eval/keyword-holdout-v1.tsv"),
                Path.of("data/eval/results/keyword-holdout-v1.tsv"),
                "holdout-policy-retrieval-only", false);
    }

    private static void replay(Path casesPath, Path outputPath, Path snapshotPath,
                               String scope, boolean requireAllPass) throws Exception {
        List<RetrievalCase> cases = readCases(casesPath);
        assertTrue(!cases.isEmpty(), "评测输入不可为空：" + casesPath);
        List<PolicySource> policies = new PolicySourceLoader().load(POLICY_DIRECTORY);
        PolicyKeywordSearch search = new PolicyKeywordSearch();
        StringBuilder report = new StringBuilder();
        report.append("# scope=").append(scope).append('\n');
        report.append("# casesSha256=").append(sha256(Files.readAllBytes(casesPath))).append('\n');
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

        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, report.toString(), StandardCharsets.UTF_8);
        if (requireAllPass) {
            assertEquals(cases.size(), passed, "逐例结果见 " + outputPath);
        }
        assertTrue(Files.exists(snapshotPath), "请先核查 " + outputPath + "，再保存结果快照");
        assertEquals(Files.readString(snapshotPath), report.toString(), "结果快照与本次运行结果不同");
    }

    private static List<RetrievalCase> readCases(Path casesPath) throws Exception {
        List<String> lines = Files.readAllLines(casesPath, StandardCharsets.UTF_8);
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
