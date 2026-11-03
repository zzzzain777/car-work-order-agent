package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.policy.PolicyEvidenceGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定模型输出验证结构、来源与原文短引；不依赖真实模型。 */
class StructuredAdviceServiceTest {

    private static final String SOURCE = "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1";
    private static final String RULE = "STORE-A 的 MX-7 试驾申请时间可落在每日 10:00（含）至 17:00（不含），按 Asia/Shanghai 时区判断。门店选择不代表预约成功。示例时间：2026-11-07T14:00:00+08:00。";
    private static final String SECOND_SOURCE = "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-2";
    private static final String SECOND_RULE = "STORE-A 的 MX-7 如需改期，至少提前一天联系门店。";
    private static final String VALID_JSON = """
            {"advisorId":301,"citations":[{"sourceRef":"%s","quote":"试驾申请时间可落在每日 10:00（含）至 17:00（不含）"}]}
            """.formatted(SOURCE);
    private static final String DUPLICATE_SOURCE_JSON = """
            {"advisorId":301,"citations":[{"sourceRef":"%s","quote":"试驾申请时间可落在每日 10:00（含）至 17:00（不含）"},{"sourceRef":"%s","quote":"试驾申请时间可落在每日 10:00（含）至 17:00（不含）"}]}
            """.formatted(SOURCE, SOURCE);

    @Test
    void acceptsExactEvidenceAndBuildsSafeAdvisorSuggestion() {
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicReference<String> userPrompt = new AtomicReference<>();
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            systemPrompt.set(system);
            userPrompt.set(user);
            return new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35);
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(ready());

        assertEquals(StructuredAdviceService.Outcome.CANDIDATE, advice.outcome());
        assertEquals(301L, advice.advisorId());
        assertEquals(1, advice.processing());
        assertEquals(SOURCE, advice.citations().getFirst().sourceRef());
        assertEquals("试驾申请时间可落在每日 10:00（含）至 17:00（不含）", advice.citations().getFirst().quote());
        assertEquals("deepseek-flash", advice.modelId());
        assertEquals(120, advice.promptTokens());
        assertEquals(35, advice.completionTokens());
        assertEquals("advice-v3", advice.promptVersion());
        assertTrue(advice.message().contains("核对顾问 301"));
        assertTrue(systemPrompt.get().contains("JSON"));
        assertTrue(userPrompt.get().contains(SOURCE));
        assertTrue(userPrompt.get().contains(RULE));
        assertFalse(userPrompt.get().contains("顾问张三"));
        assertFalse(userPrompt.get().contains("SESSION="));
        assertFalse(userPrompt.get().contains("9001"));
    }

    @Test
    void appointmentAtExcludedUpperBoundaryStopsBeforeCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            calls.incrementAndGet();
            return new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35);
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(readyAt("2026-11-07T17:00:00+08:00"));

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void appointmentAtIncludedLowerBoundaryCanUseThePolicy() {
        StructuredAdviceService service = new StructuredAdviceService((system, user) ->
                new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35),
                new ObjectMapper());

        assertEquals(StructuredAdviceService.Outcome.CANDIDATE,
                service.suggest(readyAt("2026-11-07T10:00:00+08:00")).outcome());
        assertEquals(StructuredAdviceService.Outcome.CANDIDATE,
                service.suggest(readyAt("2026-11-07T08:00:00Z")).outcome());
    }

    @Test
    void appointmentOutsideTheWindowStopsBeforeCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            calls.incrementAndGet();
            return new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35);
        }, new ObjectMapper());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW,
                service.suggest(readyAt("2026-11-07T09:59:59+08:00")).outcome());
        assertEquals(StructuredAdviceService.Outcome.CANDIDATE,
                service.suggest(readyAt("2026-11-07T16:59:59+08:00")).outcome());
        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW,
                service.suggest(readyAt("2026-11-07T09:00:00Z")).outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void policyRiskCanOnlyBlockOrAnnotateAValidatedCandidate() {
        StructuredAdviceService service = new StructuredAdviceService((system, user) ->
                new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35),
                new ObjectMapper());
        StructuredAdviceService.Advice candidate = service.suggest(ready());
        PolicyRiskReviewService.Review denied = new PolicyRiskReviewService.Review(
                PolicyRiskReviewService.Verdict.DENY, SOURCE,
                "每日 10:00（含）至 17:00（不含）", "模拟政策禁止本次申请",
                PolicyRiskReviewService.PROMPT_VERSION, "deepseek-flash", 80, 20);

        StructuredAdviceService.Advice blocked = candidate.withPolicyRisk(denied);

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, blocked.outcome());
        assertNull(blocked.advisorId());
        assertTrue(blocked.citations().isEmpty());
        assertEquals(200, blocked.promptTokens());
        assertEquals(55, blocked.completionTokens());
        assertEquals(PolicyRiskReviewService.Verdict.DENY, blocked.policyRisk().verdict());

        PolicyRiskReviewService.Review allowed = new PolicyRiskReviewService.Review(
                PolicyRiskReviewService.Verdict.ALLOW, SOURCE,
                "每日 10:00（含）至 17:00（不含）", "只读风险提示",
                PolicyRiskReviewService.PROMPT_VERSION, "deepseek-flash", 80, 20);
        assertEquals(StructuredAdviceService.Outcome.CANDIDATE,
                candidate.withPolicyRisk(allowed).outcome());
        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW,
                candidate.withPolicyRisk(new PolicyRiskReviewService.Review(
                        PolicyRiskReviewService.Verdict.ALLOW, "other#rule-1", allowed.quote(),
                        allowed.message(), allowed.promptVersion(), allowed.modelId(), 80, 20)).outcome());
    }

    @Test
    void onlyTopRankedTimeRuleCanSupportTheSuggestion() {
        AtomicReference<String> userPrompt = new AtomicReference<>();
        String secondRuleCitation = """
                {"advisorId":301,"citations":[{"sourceRef":"%s","quote":"如需改期，至少提前一天联系门店"}]}
                """.formatted(SECOND_SOURCE);
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            userPrompt.set(user);
            return new StructuredAdviceService.Completion(secondRuleCitation, "deepseek-flash", 120, 35);
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(readyWithTwoPolicyRules());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome());
        assertFalse(userPrompt.get().contains(SECOND_SOURCE));
        assertFalse(userPrompt.get().contains(SECOND_RULE));
        assertTrue(userPrompt.get().contains(SOURCE));
    }

    @Test
    void acceptsTimeCitationWhenAnotherPolicyRuleWasAlsoRetrieved() {
        StructuredAdviceService service = new StructuredAdviceService((system, user) ->
                new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35),
                new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(readyWithTwoPolicyRules());

        assertEquals(StructuredAdviceService.Outcome.CANDIDATE, advice.outcome());
        assertEquals(List.of(new StructuredAdviceService.Citation(SOURCE,
                "试驾申请时间可落在每日 10:00（含）至 17:00（不含）")), advice.citations());
    }

    @Test
    void quoteFromTheTopRuleStillNeedsTheApplicationTimeRange() {
        String unrelatedQuote = """
                {"advisorId":301,"citations":[{"sourceRef":"%s","quote":"门店选择不代表预约成功"}]}
                """.formatted(SOURCE);
        StructuredAdviceService service = new StructuredAdviceService((system, user) ->
                new StructuredAdviceService.Completion(unrelatedQuote, "deepseek-flash", 120, 35),
                new ObjectMapper());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, service.suggest(ready()).outcome());
    }

    @Test
    void wrongTopRuleWithoutApplicationTimeRangeStopsBeforeCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            calls.incrementAndGet();
            return new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35);
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(readyWithWrongTopPolicy());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome());
        assertTrue(advice.message().contains("申请时间范围"));
        assertEquals(0, calls.get());
        assertEquals(0, advice.promptTokens());
        assertEquals(0, advice.completionTokens());
    }

    @Test
    void aSingleTimestampWithTimezoneIsNotAnApplicationTimeRange() {
        String timestampQuote = """
                {"advisorId":301,"citations":[{"sourceRef":"%s","quote":"2026-11-07T14:00:00+08:00"}]}
                """.formatted(SOURCE);
        StructuredAdviceService service = new StructuredAdviceService((system, user) ->
                new StructuredAdviceService.Completion(timestampQuote, "deepseek-flash", 120, 35),
                new ObjectMapper());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, service.suggest(ready()).outcome());
    }

    @Test
    void nonReadyOrMismatchedPersistedPreviewNeverCallsModel() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            calls.incrementAndGet();
            return new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 0, 0);
        }, new ObjectMapper());

        AgentTaskWorkflow.Refresh ready = ready();
        ReadOnlyAdviceFlow.Result review = new ReadOnlyAdviceFlow.Result(ReadOnlyAdviceFlow.Stage.NEEDS_REVIEW,
                1001, "政策冲突", List.of(), ready.result().policyEvidence(), List.of(),
                Instant.parse("2026-11-01T02:00:00Z"), false);
        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW,
                service.suggest(new AgentTaskWorkflow.Refresh(ready.task(), review)).outcome());
        JdbcAgentTaskStore.Task wrongVersion = new JdbcAgentTaskStore.Task(ready.task().id(), 9001, 1001,
                1, 0, null, "2026-11-01 10:00:00", 3, TaskState.NEEDS_INPUT,
                ready.task().supplement(), null, Instant.parse("2026-11-01T02:00:00Z"));
        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW,
                service.suggest(new AgentTaskWorkflow.Refresh(wrongVersion, ready.result())).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void fabricatedAdvisorSourceOrQuoteIsRejected() {
        List<String> invalid = List.of(
                VALID_JSON.replace("\"advisorId\":301", "\"advisorId\":999"),
                VALID_JSON.replace(SOURCE, "data/policies/OTHER.md#rule-1"),
                VALID_JSON.replace("试驾申请时间可落在每日 10:00（含）至 17:00（不含）", "任何时间都保证有现车"),
                VALID_JSON.replace("}]}", "}],\"assignmentEnabled\":true}"),
                "```json\n" + VALID_JSON + "\n```",
                VALID_JSON + "\n实际可直接分配");
        for (String output : invalid) {
            StructuredAdviceService service = new StructuredAdviceService(
                    (system, user) -> new StructuredAdviceService.Completion(output, "deepseek-flash", 120, 35),
                    new ObjectMapper());

            StructuredAdviceService.Advice advice = service.suggest(ready());

            assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome(), output);
            assertNull(advice.advisorId());
            assertTrue(advice.citations().isEmpty());
        }
    }

    @Test
    void retriesOneMalformedCitationAndCountsBothModelCalls() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAdviceService service = new StructuredAdviceService((system, user) ->
                calls.incrementAndGet() == 1
                        ? new StructuredAdviceService.Completion(DUPLICATE_SOURCE_JSON, "deepseek-flash", 100, 20)
                        : new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 150, 30),
                new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(ready());

        assertEquals(StructuredAdviceService.Outcome.CANDIDATE, advice.outcome());
        assertEquals(2, calls.get());
        assertEquals(250, advice.promptTokens());
        assertEquals(50, advice.completionTokens());
        assertEquals(1, advice.citations().size());
    }

    @Test
    void stopsAfterOneRetryWhenCitationsRemainMalformed() {
        AtomicInteger calls = new AtomicInteger();
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            calls.incrementAndGet();
            return new StructuredAdviceService.Completion(DUPLICATE_SOURCE_JSON, "deepseek-flash", 100, 20);
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(ready());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome());
        assertEquals(2, calls.get());
        assertEquals(200, advice.promptTokens());
        assertEquals(40, advice.completionTokens());
        assertTrue(advice.citations().isEmpty());
    }

    @Test
    void modelFailureStopsWithManualReview() {
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            throw new IllegalStateException("模拟模型不可用");
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(ready());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome());
        assertNull(advice.advisorId());
        assertTrue(advice.message().contains("人工核对"));
    }

    @Test
    void keepsLargeAdvisorSnapshotButOnlySendsFirstFiftyCandidatesToModel() throws Exception {
        AtomicReference<String> userPrompt = new AtomicReference<>();
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            userPrompt.set(user);
            return new StructuredAdviceService.Completion(VALID_JSON, "deepseek-flash", 120, 35);
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(readyWithAdvisors(51));

        assertEquals(StructuredAdviceService.Outcome.CANDIDATE, advice.outcome());
        var sent = new ObjectMapper().readTree(userPrompt.get()).path("advisors");
        assertEquals(50, sent.size());
        assertEquals(301, sent.get(0).path("advisorId").asInt());
        assertEquals(350, sent.get(49).path("advisorId").asInt());
    }

    @Test
    void rejectsAdvisorOutsideTheShortlistEvenWhenPresentInFullSnapshot() {
        String excludedAdvisor = VALID_JSON.replace("\"advisorId\":301", "\"advisorId\":351");
        StructuredAdviceService service = new StructuredAdviceService((system, user) ->
                new StructuredAdviceService.Completion(excludedAdvisor, "deepseek-flash", 120, 35),
                new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(readyWithAdvisors(51));

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome());
        assertNull(advice.advisorId());
    }

    private static AgentTaskWorkflow.Refresh ready() {
        Instant at = Instant.parse("2026-11-01T02:00:00Z");
        ReadOnlyAdviceFlow.Result result = new ReadOnlyAdviceFlow.Result(
                ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, 1001, "只读预览",
                List.of(), List.of(new PolicyEvidenceGate.Evidence("chunk-1", "POL-A", 1, SOURCE, RULE)),
                List.of(new ReadOnlyAdviceFlow.AdvisorLoad(301, "顾问张三", 1, 3, false)), at, false);
        JdbcAgentTaskStore.Task task = new JdbcAgentTaskStore.Task(UUID.randomUUID(), 9001, 1001,
                1, 0, null, "2026-11-01 10:00:00", 2, TaskState.READY_FOR_ADMIN_REVIEW,
                new ReadOnlyAdviceFlow.Supplement("STORE-A", "MX-7",
                        OffsetDateTime.parse("2026-11-07T14:00:00+08:00")),
                new JdbcAgentTaskStore.Preview(ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW,
                        List.of(SOURCE), List.of(new JdbcAgentTaskStore.AdvisorSnapshot(301, 1, 3, false)), at), at);
        return new AgentTaskWorkflow.Refresh(task, result);
    }

    private static AgentTaskWorkflow.Refresh readyAt(String appointmentAt) {
        AgentTaskWorkflow.Refresh base = ready();
        JdbcAgentTaskStore.Task old = base.task();
        JdbcAgentTaskStore.Task task = new JdbcAgentTaskStore.Task(old.id(), old.adminId(), old.workOrderId(),
                old.orderType(), old.orderStatus(), old.orderAdvisorId(), old.orderUpdatedAt(), old.version(),
                old.state(), new ReadOnlyAdviceFlow.Supplement(old.supplement().storeId(),
                        old.supplement().modelCode(), OffsetDateTime.parse(appointmentAt)),
                old.preview(), old.updatedAt());
        return new AgentTaskWorkflow.Refresh(task, base.result());
    }

    private static AgentTaskWorkflow.Refresh readyWithAdvisors(int count) {
        AgentTaskWorkflow.Refresh base = ready();
        List<ReadOnlyAdviceFlow.AdvisorLoad> advisors = IntStream.range(0, count)
                .mapToObj(index -> new ReadOnlyAdviceFlow.AdvisorLoad(301 + index,
                        "模拟顾问", 0, 0, false)).toList();
        ReadOnlyAdviceFlow.Result result = new ReadOnlyAdviceFlow.Result(
                base.result().stage(), base.result().workOrderId(), base.result().message(),
                base.result().missingFields(), base.result().policyEvidence(), advisors,
                base.result().observedAt(), false);
        JdbcAgentTaskStore.Preview preview = new JdbcAgentTaskStore.Preview(
                base.task().preview().stage(), base.task().preview().policySourceRefs(),
                advisors.stream().map(advisor -> new JdbcAgentTaskStore.AdvisorSnapshot(
                        advisor.advisorId(), advisor.processing(), advisor.total(),
                        advisor.storeAffiliationVerified())).toList(), base.result().observedAt());
        JdbcAgentTaskStore.Task original = base.task();
        JdbcAgentTaskStore.Task task = new JdbcAgentTaskStore.Task(original.id(), original.adminId(),
                original.workOrderId(), original.orderType(), original.orderStatus(),
                original.orderAdvisorId(), original.orderUpdatedAt(), original.version(),
                original.state(), original.supplement(), preview, original.updatedAt());
        return new AgentTaskWorkflow.Refresh(task, result);
    }

    private static AgentTaskWorkflow.Refresh readyWithTwoPolicyRules() {
        AgentTaskWorkflow.Refresh base = ready();
        List<PolicyEvidenceGate.Evidence> evidence = List.of(base.result().policyEvidence().getFirst(),
                new PolicyEvidenceGate.Evidence("chunk-2", "POL-A", 1, SECOND_SOURCE, SECOND_RULE));
        ReadOnlyAdviceFlow.Result result = new ReadOnlyAdviceFlow.Result(base.result().stage(),
                base.result().workOrderId(), base.result().message(), base.result().missingFields(),
                evidence, base.result().advisors(), base.result().observedAt(), false);
        JdbcAgentTaskStore.Task old = base.task();
        JdbcAgentTaskStore.Preview preview = new JdbcAgentTaskStore.Preview(old.preview().stage(),
                List.of(SOURCE, SECOND_SOURCE), old.preview().advisors(), old.preview().observedAt());
        JdbcAgentTaskStore.Task task = new JdbcAgentTaskStore.Task(old.id(), old.adminId(), old.workOrderId(),
                old.orderType(), old.orderStatus(), old.orderAdvisorId(), old.orderUpdatedAt(), old.version(),
                old.state(), old.supplement(), preview, old.updatedAt());
        return new AgentTaskWorkflow.Refresh(task, result);
    }

    private static AgentTaskWorkflow.Refresh readyWithWrongTopPolicy() {
        AgentTaskWorkflow.Refresh base = readyWithTwoPolicyRules();
        List<PolicyEvidenceGate.Evidence> evidence = List.of(
                base.result().policyEvidence().get(1), base.result().policyEvidence().getFirst());
        ReadOnlyAdviceFlow.Result result = new ReadOnlyAdviceFlow.Result(base.result().stage(),
                base.result().workOrderId(), base.result().message(), base.result().missingFields(),
                evidence, base.result().advisors(), base.result().observedAt(), false);
        JdbcAgentTaskStore.Task old = base.task();
        JdbcAgentTaskStore.Preview preview = new JdbcAgentTaskStore.Preview(old.preview().stage(),
                List.of(SECOND_SOURCE, SOURCE), old.preview().advisors(), old.preview().observedAt());
        JdbcAgentTaskStore.Task task = new JdbcAgentTaskStore.Task(old.id(), old.adminId(), old.workOrderId(),
                old.orderType(), old.orderStatus(), old.orderAdvisorId(), old.orderUpdatedAt(), old.version(),
                old.state(), old.supplement(), preview, old.updatedAt());
        return new AgentTaskWorkflow.Refresh(task, result);
    }
}
