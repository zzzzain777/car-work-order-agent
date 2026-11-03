package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.policy.PolicySourceLoader;
import com.zz.agent.policy.PolicyVectorDocumentMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用固定案例比较纯规则与已保存的真实模型建议，不访问模型或业务系统。 */
class StructuredAdviceRuleBaselineEvaluationTest {

    private static final Path INPUT = Path.of("data/eval/workflow/structured-advice-live-v2.json");
    private static final Path MODEL_RESULT = Path.of("data/eval/results/structured-advice-live-v2.tsv");
    private static final Path OUTPUT = Path.of("target/eval/structured-advice-ab-v1.tsv");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?:[01]?\\d|2[0-3]):[0-5]\\d.{0,40}(?:至|到|—|–|~).{0,40}"
                    + "(?:[01]?\\d|2[0-3]):[0-5]\\d(?:（不含）)?");

    private record Advisor(long id, int processing) {
    }

    @Test
    void compareRuleBaselineWithTheSameThreeModelCases() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(INPUT.toFile());
        List<String> previous = Files.readAllLines(MODEL_RESULT, StandardCharsets.UTF_8);
        String fixtureHash = hash(Files.readAllBytes(INPUT));
        String corpusHash = corpusHash();
        assertTrue(previous.contains("# inputSha256=" + fixtureHash), "模型记录的案例输入已变化");
        assertTrue(previous.contains("# corpusSha256=" + corpusHash), "模型记录的政策语料已变化");

        List<String> modelColumns = Arrays.asList(previous.stream()
                .filter(line -> line.startsWith("caseId\t")).findFirst().orElseThrow().split("\t"));
        Map<String, String[]> modelRows = previous.stream().filter(line -> line.startsWith("A0"))
                .map(line -> line.split("\t", -1))
                .collect(Collectors.toMap(row -> row[0], Function.identity()));
        assertEquals(fixture.path("cases").size(), modelRows.size());

        Map<String, String> policyText = new PolicyVectorDocumentMapper()
                .map(new PolicySourceLoader().load(Path.of("data/policies"))).stream()
                .collect(Collectors.toMap(document -> (String) document.getMetadata().get("sourceRef"),
                        document -> document.getText()));
        List<Advisor> advisors = List.of(new Advisor(301, 1), new Advisor(302, 3));
        StringBuilder report = new StringBuilder()
                .append("# scope=structured-advice-rules-vs-model-v1\n")
                .append("# inputSha256=").append(fixtureHash).append('\n')
                .append("# corpusSha256=").append(corpusHash).append('\n')
                .append("# modelSnapshot=structured-advice-live-v2.tsv\n")
                .append("caseId\texpectedOutcome\truleOutcome\tmodelOutcome")
                .append("\texpectedAdvisorId\truleAdvisorId\tmodelAdvisorId")
                .append("\texpectedCitationRef\truleCitationRef\tmodelCitationRef")
                .append("\truleQuote\truleElapsedNs\tmodelCalls")
                .append("\tmodelPromptTokens\tmodelCompletionTokens\tmodelElapsedMs")
                .append("\trulePass\tmodelPass\tagree\n");
        int rulePassed = 0;
        int modelPassed = 0;
        int agreed = 0;
        for (JsonNode item : fixture.path("cases")) {
            String caseId = item.path("caseId").asText();
            String topRef = item.path("policySourceRefs").get(0).asText();
            String topText = policyText.get(topRef);
            if (topText == null) {
                throw new IllegalArgumentException("模拟政策片段不存在：" + topRef);
            }
            long startedAt = System.nanoTime();
            Matcher range = TIME_RANGE.matcher(topText);
            boolean hasRange = range.find();
            String ruleOutcome = hasRange ? "CANDIDATE" : "NEEDS_REVIEW";
            String ruleAdvisor = hasRange ? Long.toString(advisors.stream()
                    .min(Comparator.comparingInt(Advisor::processing).thenComparingLong(Advisor::id))
                    .orElseThrow().id()) : "-";
            String ruleRef = hasRange ? topRef : "-";
            String ruleQuote = hasRange ? range.group() : "-";
            long elapsedNs = System.nanoTime() - startedAt;

            String[] model = modelRows.get(caseId);
            if (model == null) {
                throw new IllegalArgumentException("模型结果缺少案例：" + caseId);
            }
            String modelOutcome = cell(modelColumns, model, "actualOutcome");
            String modelAdvisor = cell(modelColumns, model, "actualAdvisorId");
            String modelRef = cell(modelColumns, model, "actualCitationRef");
            String expectedAdvisor = textOrDash(item.path("expectedAdvisorId"));
            String expectedRef = textOrDash(item.path("expectedCitationRef"));
            boolean rulePass = ruleOutcome.equals(item.path("expectedOutcome").asText())
                    && ruleAdvisor.equals(expectedAdvisor) && ruleRef.equals(expectedRef);
            boolean modelPass = "PASS".equals(cell(modelColumns, model, "pass"));
            boolean agree = ruleOutcome.equals(modelOutcome) && ruleAdvisor.equals(modelAdvisor)
                    && ruleRef.equals(modelRef);
            rulePassed += rulePass ? 1 : 0;
            modelPassed += modelPass ? 1 : 0;
            agreed += agree ? 1 : 0;
            report.append(String.join("\t", caseId, item.path("expectedOutcome").asText(),
                    ruleOutcome, modelOutcome, expectedAdvisor, ruleAdvisor, modelAdvisor,
                    expectedRef, ruleRef, modelRef, ruleQuote,
                    Long.toString(elapsedNs), cell(modelColumns, model, "modelCalls"),
                    cell(modelColumns, model, "promptTokens"), cell(modelColumns, model, "completionTokens"),
                    cell(modelColumns, model, "elapsedMs"), rulePass ? "PASS" : "FAIL",
                    modelPass ? "PASS" : "FAIL", Boolean.toString(agree))).append('\n');
        }
        int total = fixture.path("cases").size();
        report.append("# ruleExactCasePass=").append(rulePassed).append('/').append(total).append('\n');
        report.append("# modelExactCasePass=").append(modelPassed).append('/').append(total).append('\n');
        report.append("# outcomeAdvisorCitationAgreement=").append(agreed).append('/').append(total).append('\n');
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
        assertEquals(total, rulePassed, "规则逐例结果见 " + OUTPUT);
        assertEquals(total, modelPassed, "模型快照存在失败案例，逐例结果见 " + OUTPUT);
        assertEquals(total, agreed, "两种方案的逐例差异见 " + OUTPUT);
    }

    private static String cell(List<String> columns, String[] row, String name) {
        return row[columns.indexOf(name)];
    }

    private static String textOrDash(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? "-" : node.asText();
    }

    private static String corpusHash() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var files = Files.list(Path.of("data/policies"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .filter(path -> !path.getFileName().toString().startsWith("_"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
