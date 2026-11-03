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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

    private static final Path INPUT = Path.of(System.getProperty("advice.ab.input",
            "data/eval/workflow/structured-advice-live-v2.json"));
    private static final Path MODEL_RESULT = Path.of(System.getProperty("advice.ab.model-result",
            "data/eval/results/structured-advice-live-v2.tsv"));
    private static final Path OUTPUT = Path.of(System.getProperty("advice.ab.output",
            "target/eval/structured-advice-ab-v1.tsv"));
    private static final Pattern DAILY_WINDOW = Pattern.compile(
            "每日\\s*((?:[01]\\d|2[0-3]):[0-5]\\d)（含）\\s*至\\s*((?:[01]\\d|2[0-3]):[0-5]\\d)（不含）");
    private static final ZoneId POLICY_ZONE = ZoneId.of("Asia/Shanghai");

    private record Advisor(long id, int processing) {
    }

    private record RuleDecision(String outcome, String advisorId, String citationRef, String quote) {
    }

    @Test
    void freezeBlindBaselineBeforeModelRun() throws Exception {
        Path input = Path.of("data/eval/workflow/structured-advice-blind-v1.json");
        Path output = Path.of("target/eval/structured-advice-blind-baseline-v1.tsv");
        JsonNode fixture = new ObjectMapper().readTree(input.toFile());
        Map<String, String> policies = policyText();
        List<Advisor> advisors = List.of(new Advisor(301, 1), new Advisor(302, 3));
        StringBuilder report = new StringBuilder("# scope=structured-advice-blind-baseline-v1\n")
                .append("# inputSha256=").append(hash(Files.readAllBytes(input))).append('\n')
                .append("# corpusSha256=").append(corpusHash()).append('\n')
                .append("caseId\texpectedOutcome\truleOutcome\texpectedAdvisorId\truleAdvisorId")
                .append("\texpectedCitationRef\truleCitationRef\truleQuote\tpass\n");
        int passed = 0;
        for (JsonNode item : fixture.path("cases")) {
            RuleDecision rule = evaluateRule(fixture, item, policies, advisors);
            String expectedAdvisor = textOrDash(item.path("expectedAdvisorId"));
            String expectedRef = textOrDash(item.path("expectedCitationRef"));
            boolean pass = rule.outcome().equals(item.path("expectedOutcome").asText())
                    && rule.advisorId().equals(expectedAdvisor) && rule.citationRef().equals(expectedRef);
            passed += pass ? 1 : 0;
            report.append(String.join("\t", item.path("caseId").asText(),
                    item.path("expectedOutcome").asText(), rule.outcome(), expectedAdvisor, rule.advisorId(),
                    expectedRef, rule.citationRef(), rule.quote(), pass ? "PASS" : "FAIL")).append('\n');
        }
        report.append("# ruleExactCasePass=").append(passed).append('/')
                .append(fixture.path("cases").size()).append('\n');
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        assertEquals(fixture.path("cases").size(), passed, "规则基线逐例结果见 " + output);
    }

    @Test
    void compareRuleBaselineWithFixedModelCases() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(INPUT.toFile());
        List<String> previous = Files.readAllLines(MODEL_RESULT, StandardCharsets.UTF_8);
        String fixtureHash = hash(Files.readAllBytes(INPUT));
        String corpusHash = corpusHash();
        assertTrue(previous.contains("# inputSha256=" + fixtureHash), "模型记录的案例输入已变化");
        assertTrue(previous.contains("# corpusSha256=" + corpusHash), "模型记录的政策语料已变化");

        List<String> modelColumns = Arrays.asList(previous.stream()
                .filter(line -> line.startsWith("caseId\t")).findFirst().orElseThrow().split("\t"));
        Map<String, String[]> modelRows = previous.stream()
                .filter(line -> !line.startsWith("#") && !line.startsWith("caseId\t") && line.contains("\t"))
                .map(line -> line.split("\t", -1))
                .collect(Collectors.toMap(row -> row[0], Function.identity()));
        assertEquals(fixture.path("cases").size(), modelRows.size());

        Map<String, String> policyText = policyText();
        List<Advisor> advisors = List.of(new Advisor(301, 1), new Advisor(302, 3));
        StringBuilder report = new StringBuilder()
                .append("# scope=structured-advice-rules-vs-model-v1\n")
                .append("# inputSha256=").append(fixtureHash).append('\n')
                .append("# corpusSha256=").append(corpusHash).append('\n')
                .append("# modelSnapshot=").append(MODEL_RESULT.getFileName()).append('\n')
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
            long startedAt = System.nanoTime();
            RuleDecision rule = evaluateRule(fixture, item, policyText, advisors);
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
            boolean rulePass = rule.outcome().equals(item.path("expectedOutcome").asText())
                    && rule.advisorId().equals(expectedAdvisor) && rule.citationRef().equals(expectedRef);
            boolean modelPass = "PASS".equals(cell(modelColumns, model, "pass"));
            boolean agree = rule.outcome().equals(modelOutcome) && rule.advisorId().equals(modelAdvisor)
                    && rule.citationRef().equals(modelRef);
            rulePassed += rulePass ? 1 : 0;
            modelPassed += modelPass ? 1 : 0;
            agreed += agree ? 1 : 0;
            report.append(String.join("\t", caseId, item.path("expectedOutcome").asText(),
                    rule.outcome(), modelOutcome, expectedAdvisor, rule.advisorId(), modelAdvisor,
                    expectedRef, rule.citationRef(), modelRef, rule.quote(),
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

    private static RuleDecision evaluateRule(JsonNode fixture, JsonNode item,
                                             Map<String, String> policies, List<Advisor> advisors) {
        String topRef = item.path("policySourceRefs").get(0).asText();
        String topText = policies.get(topRef);
        if (topText == null) {
            throw new IllegalArgumentException("模拟政策片段不存在：" + topRef);
        }
        Matcher window = DAILY_WINDOW.matcher(topText);
        String appointmentAt = item.hasNonNull("appointmentAt")
                ? item.path("appointmentAt").asText() : fixture.path("appointmentAt").asText();
        boolean hasWindow = topText.contains("Asia/Shanghai") && window.find();
        String quote = hasWindow ? window.group() : "-";
        LocalTime start = hasWindow ? LocalTime.parse(window.group(1)) : null;
        LocalTime end = hasWindow ? LocalTime.parse(window.group(2)) : null;
        LocalTime requested = OffsetDateTime.parse(appointmentAt).atZoneSameInstant(POLICY_ZONE).toLocalTime();
        boolean allowed = hasWindow && !window.find() && end.isAfter(start)
                && !requested.isBefore(start) && requested.isBefore(end);
        if (!allowed) {
            return new RuleDecision("NEEDS_REVIEW", "-", "-", "-");
        }
        long advisorId = advisors.stream()
                .min(Comparator.comparingInt(Advisor::processing).thenComparingLong(Advisor::id))
                .orElseThrow().id();
        return new RuleDecision("CANDIDATE", Long.toString(advisorId), topRef, quote);
    }

    private static Map<String, String> policyText() throws Exception {
        return new PolicyVectorDocumentMapper()
                .map(new PolicySourceLoader().load(Path.of("data/policies"))).stream()
                .collect(Collectors.toMap(document -> (String) document.getMetadata().get("sourceRef"),
                        document -> document.getText()));
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
