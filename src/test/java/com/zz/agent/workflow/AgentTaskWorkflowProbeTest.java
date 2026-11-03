package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.policy.PolicyEvidenceGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实任务表配合模拟业务接口，验证状态连接与并发保护。 */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class AgentTaskWorkflowProbeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC);
    private static final String COOKIE = "SESSION=simulated-session";
    private static final ReadOnlyAdviceFlow.Supplement COMPLETE = new ReadOnlyAdviceFlow.Supplement(
            "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"));

    @Test
    void administratorCanOpenSupplementAndPersistReadOnlyPreview() {
        FakeCarSystem car = new FakeCarSystem();
        PolicyStub policies = new PolicyStub();
        JdbcAgentTaskStore store = store();
        AgentTaskWorkflow workflow = workflow(car, policies, store);

        JdbcAgentTaskStore.Task opened = workflow.open(COOKIE, 1001);
        assertEquals(TaskState.NEEDS_INPUT, opened.state());
        assertEquals(0, opened.version());
        assertEquals(9001, opened.adminId());

        JdbcAgentTaskStore.Task supplemented = workflow.supplement(COOKIE, opened.id(), 0, COMPLETE).orElseThrow();
        assertEquals(1, supplemented.version());
        AgentTaskWorkflow.Refresh refreshed = workflow.refresh(COOKIE, opened.id(), 1).orElseThrow();
        assertEquals(TaskState.READY_FOR_ADMIN_REVIEW, refreshed.task().state());
        assertEquals(ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, refreshed.result().stage());
        assertFalse(refreshed.result().assignmentEnabled());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                workflow.find(COOKIE, opened.id()).orElseThrow().preview().policySourceRefs().getFirst());
        assertEquals(1, policies.calls);
    }

    @Test
    void ordinaryUserOrRevokedSessionCannotReadOrModifyAnExistingTask() {
        FakeCarSystem car = new FakeCarSystem();
        AgentTaskWorkflow workflow = workflow(car, new PolicyStub(), store());
        JdbcAgentTaskStore.Task task = workflow.open(COOKIE, 1001);

        car.identity = new CarSystemReadClient.Identity(9002, 0, 0);
        assertThrows(SecurityException.class, () -> workflow.open(COOKIE, 1002));
        assertThrows(SecurityException.class, () -> workflow.find(COOKIE, task.id()));
        assertThrows(SecurityException.class, () -> workflow.supplement(COOKIE, task.id(), 0, COMPLETE));
        assertThrows(SecurityException.class, () -> workflow.refresh(COOKIE, task.id(), 0));
        assertEquals(0, car.orderCallsAfterRevocation);

        car.identity = new CarSystemReadClient.Identity(9002, 2, 0);
        assertTrue(workflow.find(COOKIE, task.id()).isEmpty());
        assertTrue(workflow.supplement(COOKIE, task.id(), 0, COMPLETE).isEmpty());
    }

    @Test
    void changedWorkOrderSnapshotBecomesStaleBeforePolicyLookup() {
        FakeCarSystem car = new FakeCarSystem();
        PolicyStub policies = new PolicyStub();
        AgentTaskWorkflow workflow = workflow(car, policies, store());
        JdbcAgentTaskStore.Task task = workflow.open(COOKIE, 1001);
        workflow.supplement(COOKIE, task.id(), 0, COMPLETE).orElseThrow();
        car.order = new CarSystemReadClient.WorkOrder(1001, 1, 0, null, "2026-11-01 10:01:00");

        AgentTaskWorkflow.Refresh refreshed = workflow.refresh(COOKIE, task.id(), 1).orElseThrow();
        assertEquals(TaskState.STALE_PROPOSAL, refreshed.task().state());
        assertEquals(ReadOnlyAdviceFlow.Stage.NOT_APPLICABLE, refreshed.result().stage());
        assertEquals(0, policies.calls);
    }

    @Test
    void concurrentSupplementPreventsOldPreviewFromOverwritingNewTaskVersion() {
        FakeCarSystem car = new FakeCarSystem();
        JdbcAgentTaskStore store = store();
        JdbcAgentTaskStore.Task task = workflow(car, new PolicyStub(), store).open(COOKIE, 1001);
        store.updateSupplement(task.id(), 9001, 0, COMPLETE).orElseThrow();
        ReadOnlyAdviceFlow.PolicyLookup racingPolicy = (storeId, modelCode, time, question) -> {
            store.updateSupplement(task.id(), 9001, 1,
                    new ReadOnlyAdviceFlow.Supplement("STORE-C", "MX-7", time)).orElseThrow();
            return PolicyStub.DECISION;
        };
        AgentTaskWorkflow workflow = new AgentTaskWorkflow(car,
                new ReadOnlyAdviceFlow(car, racingPolicy, CLOCK), store, CLOCK);

        Optional<AgentTaskWorkflow.Refresh> stale = workflow.refresh(COOKIE, task.id(), 1);
        assertTrue(stale.isEmpty());
        JdbcAgentTaskStore.Task current = workflow.find(COOKIE, task.id()).orElseThrow();
        assertEquals(2, current.version());
        assertEquals("STORE-C", current.supplement().storeId());
        assertEquals(null, current.preview());
    }

    @Test
    void completeTaskRefreshProducesValidatedModelCandidate() {
        FakeCarSystem car = new FakeCarSystem();
        AgentTaskWorkflow workflow = workflow(car, new PolicyStub(), store());
        JdbcAgentTaskStore.Task task = workflow.open(COOKIE, 1001);
        workflow.supplement(COOKIE, task.id(), 0, COMPLETE).orElseThrow();
        AtomicInteger modelCalls = new AtomicInteger();
        StructuredAdviceService advice = new StructuredAdviceService((system, user) -> {
            modelCalls.incrementAndGet();
            return new StructuredAdviceService.Completion("""
                    {"advisorId":301,"citations":[{"sourceRef":"data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                    "quote":"试驾申请时间可落在每日 10:00（含）至 17:00（不含）"}]}
                    """, "deepseek-flash", 20, 10);
        }, new ObjectMapper());
        AgentAdviceOrchestrator orchestrator = new AgentAdviceOrchestrator(workflow, advice);

        AgentAdviceOrchestrator.Recommendation result = orchestrator.refreshAndSuggest(COOKIE, task.id(), 1)
                .orElseThrow();

        assertEquals(TaskState.READY_FOR_ADMIN_REVIEW, result.task().state());
        assertEquals(StructuredAdviceService.Outcome.CANDIDATE, result.advice().outcome());
        assertEquals(301L, result.advice().advisorId());
        assertEquals(1, modelCalls.get());
    }

    @Test
    void missingInputStopsBeforeModelCall() {
        FakeCarSystem car = new FakeCarSystem();
        AgentTaskWorkflow workflow = workflow(car, new PolicyStub(), store());
        JdbcAgentTaskStore.Task task = workflow.open(COOKIE, 1001);
        AtomicInteger modelCalls = new AtomicInteger();
        StructuredAdviceService advice = new StructuredAdviceService((system, user) -> {
            modelCalls.incrementAndGet();
            return new StructuredAdviceService.Completion("{}", "deepseek-flash", 0, 0);
        }, new ObjectMapper());

        AgentAdviceOrchestrator.Recommendation result =
                new AgentAdviceOrchestrator(workflow, advice).refreshAndSuggest(COOKIE, task.id(), 0).orElseThrow();

        assertEquals(TaskState.NEEDS_INPUT, result.task().state());
        assertEquals(StructuredAdviceService.Outcome.NEEDS_REVIEW, result.advice().outcome());
        assertEquals(0, modelCalls.get());
    }

    private static AgentTaskWorkflow workflow(FakeCarSystem car, PolicyStub policies, JdbcAgentTaskStore store) {
        return new AgentTaskWorkflow(car, new ReadOnlyAdviceFlow(car, policies, CLOCK), store, CLOCK);
    }

    private static JdbcAgentTaskStore store() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"),
                System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD")));
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc, new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        return store;
    }

    private static final class PolicyStub implements ReadOnlyAdviceFlow.PolicyLookup {
        static final PolicyEvidenceGate.Decision DECISION = new PolicyEvidenceGate.Decision(
                PolicyEvidenceGate.Outcome.CANDIDATES,
                List.of(new PolicyEvidenceGate.Evidence("chunk-1", "POL-A", 1,
                        "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                        "模拟政策原文：试驾申请时间可落在每日 10:00（含）至 17:00（不含），按 Asia/Shanghai 时区判断。")));
        int calls;

        @Override
        public PolicyEvidenceGate.Decision search(String storeId, String modelCode,
                                                  OffsetDateTime appointmentAt, String question) {
            calls++;
            return DECISION;
        }
    }

    private static final class FakeCarSystem implements CarSystemReadClient {
        Identity identity = new Identity(9001, 2, 0);
        WorkOrder order = new WorkOrder(1001, 1, 0, null, "2026-11-01 10:00:00");
        int orderCallsAfterRevocation;

        @Override
        public Identity currentUser(String cookieHeader) {
            return identity;
        }

        @Override
        public WorkOrder getWorkOrder(String cookieHeader, long workOrderId) {
            if (identity.userRole() != 2) {
                orderCallsAfterRevocation++;
            }
            return order;
        }

        @Override
        public List<Advisor> listAdvisors(String cookieHeader) {
            return List.of(new Advisor(301, "模拟顾问甲", 1, 0));
        }

        @Override
        public List<Workload> workloads(String cookieHeader) {
            return List.of(new Workload(301, 1, 2));
        }
    }
}
