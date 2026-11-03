package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.model.ModelTextGenerator;
import com.zz.agent.policy.PolicyEvidenceGate;
import com.zz.agent.policy.PolicySourceLoader;
import com.zz.agent.policy.PolicyVectorDocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式运行的真实模型切片评测；不调用业务系统、向量库或分配接口。 */
@EnabledIfSystemProperty(named = "advice.eval.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("deepseek")
class StructuredAdviceLiveEvaluationTest {

    private static final Path INPUT = Path.of("data/eval/workflow/structured-advice-live-v2.json");
    private static final Path OUTPUT = Path.of("target/eval/structured-advice-live-v2.tsv");

    @Autowired
    private ModelTextGenerator generator;

    @Autowired
    private ObjectMapper json;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.reasoning-effort}")
    private String reasoningEffort;

    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private int maxTokens;

    @Test
    void replayFixedSimulatedCasesAndSaveEveryResult() throws Exception {
        assertFalse(apiKey.isBlank() || apiKey.startsWith("REPLACE_"), "本地 DeepSeek Key 尚未配置");
        JsonNode fixture = json.readTree(Files.readString(INPUT));
        assertTrue(fixture.path("cases").size() > 0, "固定案例不能为空");
        Map<String, PolicyEvidenceGate.Evidence> evidence = new PolicyVectorDocumentMapper()
                .map(new PolicySourceLoader().load(Path.of("data/policies"))).stream()
                .collect(java.util.stream.Collectors.toMap(
                        document -> (String) document.getMetadata().get("sourceRef"),
                        StructuredAdviceLiveEvaluationTest::toEvidence));

        StringBuilder report = new StringBuilder();
        report.append("# scope=").append(fixture.path("scope").asText()).append('\n');
        report.append("# inputSha256=").append(hash(Files.readAllBytes(INPUT))).append('\n');
        report.append("# corpusSha256=").append(corpusHash()).append('\n');
        report.append("# promptVersion=").append(StructuredAdviceService.PROMPT_VERSION).append('\n');
        report.append("# reasoningEffort=").append(reasoningEffort).append('\n');
        report.append("# maxTokensPerCall=").append(maxTokens).append('\n');
        report.append("caseId\texpectedOutcome\tactualOutcome\texpectedAdvisorId\tactualAdvisorId")
                .append("\texpectedCitationRef\tactualCitationRef\tquoteInSource")
                .append("\texpectedModelCalls\tmodelCalls")
                .append("\tmodelId\tpromptTokens\tcompletionTokens\telapsedMs\tpass\n");
        int passed = 0;
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        for (JsonNode item : fixture.path("cases")) {
            List<PolicyEvidenceGate.Evidence> selected = new ArrayList<>();
            for (JsonNode ref : item.path("policySourceRefs")) {
                PolicyEvidenceGate.Evidence row = evidence.get(ref.asText());
                if (row == null) {
                    throw new IllegalArgumentException("固定案例引用了不存在的片段：" + ref.asText());
                }
                selected.add(row);
            }
            AtomicInteger calls = new AtomicInteger();
            StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
                calls.incrementAndGet();
                ChatResponse response = generator.generate(system, user);
                var metadata = response.getMetadata();
                var usage = metadata == null ? null : metadata.getUsage();
                return new StructuredAdviceService.Completion(
                        response.getResult() == null || response.getResult().getOutput() == null
                                ? null : response.getResult().getOutput().getText(),
                        metadata == null ? null : metadata.getModel(),
                        usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens().longValue(),
                        usage == null || usage.getCompletionTokens() == null ? 0
                                : usage.getCompletionTokens().longValue());
            }, json);
            long startedAt = System.nanoTime();
            StructuredAdviceService.Advice actual = service.suggest(ready(fixture, item, selected));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            String actualRef = actual.citations().isEmpty() ? "-" : actual.citations().getFirst().sourceRef();
            boolean quoteInSource = actual.citations().size() == 1
                    && selected.stream().anyMatch(row -> row.sourceRef().equals(actualRef)
                            && row.text().contains(actual.citations().getFirst().quote()));
            String expectedRef = textOrDash(item.path("expectedCitationRef"));
            String expectedAdvisor = textOrDash(item.path("expectedAdvisorId"));
            int expectedCalls = item.path("expectedModelCalls").asInt();
            boolean pass = actual.outcome().name().equals(item.path("expectedOutcome").asText())
                    && expectedAdvisor.equals(actual.advisorId() == null ? "-" : actual.advisorId().toString())
                    && expectedRef.equals(actualRef)
                    && (actual.outcome() == StructuredAdviceService.Outcome.NEEDS_REVIEW
                            ? actual.citations().isEmpty() : quoteInSource)
                    && calls.get() == expectedCalls
                    && (expectedCalls == 0
                            ? actual.modelId() == null && actual.promptTokens() == 0
                                    && actual.completionTokens() == 0
                            : actual.modelId() != null
                                    && (actual.promptTokens() > 0 || actual.completionTokens() > 0));
            if (pass) {
                passed++;
            }
            totalPromptTokens += actual.promptTokens();
            totalCompletionTokens += actual.completionTokens();
            report.append(String.join("\t", item.path("caseId").asText(),
                    item.path("expectedOutcome").asText(), actual.outcome().name(),
                    expectedAdvisor, actual.advisorId() == null ? "-" : actual.advisorId().toString(),
                    expectedRef, actualRef, Boolean.toString(quoteInSource),
                    Integer.toString(expectedCalls), Integer.toString(calls.get()),
                    actual.modelId() == null ? "-" : actual.modelId(),
                    Long.toString(actual.promptTokens()), Long.toString(actual.completionTokens()),
                    Long.toString(elapsedMs), pass ? "PASS" : "FAIL")).append('\n');
        }
        report.append("# exactCasePass=").append(passed).append('/')
                .append(fixture.path("cases").size()).append('\n');
        report.append("# reportedPromptTokens=").append(totalPromptTokens).append('\n');
        report.append("# reportedCompletionTokens=").append(totalCompletionTokens).append('\n');
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
        System.out.printf("真实模型切片评测：%d/%d，逐例结果：%s%n",
                passed, fixture.path("cases").size(), OUTPUT);
    }

    private static AgentTaskWorkflow.Refresh ready(JsonNode fixture, JsonNode item,
                                                    List<PolicyEvidenceGate.Evidence> evidence) {
        Instant observedAt = Instant.parse(fixture.path("asOf").asText());
        long workOrderId = 1000 + fixture.path("cases").size();
        List<ReadOnlyAdviceFlow.AdvisorLoad> advisors = List.of(
                new ReadOnlyAdviceFlow.AdvisorLoad(301, "模拟顾问甲", 1, 2, false),
                new ReadOnlyAdviceFlow.AdvisorLoad(302, "模拟顾问乙", 3, 4, false));
        ReadOnlyAdviceFlow.Result result = new ReadOnlyAdviceFlow.Result(
                ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, workOrderId, "模拟只读预览",
                List.of(), evidence, advisors, observedAt, false);
        ReadOnlyAdviceFlow.Supplement supplement = new ReadOnlyAdviceFlow.Supplement(
                item.path("storeId").asText(), item.path("modelCode").asText(),
                OffsetDateTime.parse(fixture.path("appointmentAt").asText()));
        JdbcAgentTaskStore.Preview preview = new JdbcAgentTaskStore.Preview(
                ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW,
                evidence.stream().map(PolicyEvidenceGate.Evidence::sourceRef).toList(),
                advisors.stream().map(row -> new JdbcAgentTaskStore.AdvisorSnapshot(
                        row.advisorId(), row.processing(), row.total(), false)).toList(), observedAt);
        JdbcAgentTaskStore.Task task = new JdbcAgentTaskStore.Task(
                UUID.nameUUIDFromBytes(item.path("caseId").asText().getBytes(StandardCharsets.UTF_8)),
                9001, workOrderId, 1, 0, null, "2026-10-28 10:00:00", 2,
                TaskState.READY_FOR_ADMIN_REVIEW, supplement, preview, observedAt);
        return new AgentTaskWorkflow.Refresh(task, result);
    }

    private static PolicyEvidenceGate.Evidence toEvidence(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new PolicyEvidenceGate.Evidence((String) metadata.get("chunkId"),
                (String) metadata.get("policyId"), ((Number) metadata.get("version")).intValue(),
                (String) metadata.get("sourceRef"), document.getText());
    }

    private static String textOrDash(JsonNode value) {
        return value.isNull() || value.isMissingNode() ? "-" : value.asText();
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
