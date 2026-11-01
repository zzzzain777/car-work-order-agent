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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定模型输出验证结构、来源与原文短引；不依赖真实模型。 */
class StructuredAdviceServiceTest {

    private static final String SOURCE = "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1";
    private static final String RULE = "STORE-A 的 MX-7 在工作日 10:00 至 17:00 接受试驾申请。";
    private static final String VALID_JSON = """
            {"advisorId":301,"citations":[{"sourceRef":"%s","quote":"工作日 10:00 至 17:00 接受试驾申请"}]}
            """.formatted(SOURCE);

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
        assertEquals("工作日 10:00 至 17:00 接受试驾申请", advice.citations().getFirst().quote());
        assertEquals("deepseek-flash", advice.modelId());
        assertEquals(120, advice.promptTokens());
        assertEquals(35, advice.completionTokens());
        assertEquals("advice-v1", advice.promptVersion());
        assertTrue(advice.message().contains("核对顾问 301"));
        assertTrue(systemPrompt.get().contains("JSON"));
        assertTrue(userPrompt.get().contains(SOURCE));
        assertTrue(userPrompt.get().contains(RULE));
        assertFalse(userPrompt.get().contains("顾问张三"));
        assertFalse(userPrompt.get().contains("SESSION="));
        assertFalse(userPrompt.get().contains("9001"));
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
                VALID_JSON.replace("工作日 10:00 至 17:00 接受试驾申请", "任何时间都保证有现车"),
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
    void modelFailureStopsWithManualReview() {
        StructuredAdviceService service = new StructuredAdviceService((system, user) -> {
            throw new IllegalStateException("模拟模型不可用");
        }, new ObjectMapper());

        StructuredAdviceService.Advice advice = service.suggest(ready());

        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, advice.outcome());
        assertNull(advice.advisorId());
        assertTrue(advice.message().contains("人工核对"));
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
}
