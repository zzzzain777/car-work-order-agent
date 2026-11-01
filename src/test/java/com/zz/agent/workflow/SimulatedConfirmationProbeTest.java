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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 隔离数据库和模拟业务端验证人工确认；绝不调用真实分配接口。 */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class SimulatedConfirmationProbeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC);
    private static final String COOKIE = "SESSION=simulation";
    private static final String SOURCE = "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1";

    @Test
    void confirmationPersistsOneAttemptAndDuplicateDoesNotAssignAgain() {
        Fixture fixture = fixture();
        fixture.behavior = (cookie, order, advisor) -> {
            fixture.car.order = new CarSystemReadClient.WorkOrder(order, 1, 1, advisor,
                    "2026-11-01 10:02:00");
            return SimulatedAssignmentPort.Result.ACCEPTED;
        };
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();

        SimulatedConfirmationFlow.Decision first = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));
        SimulatedConfirmationFlow.Decision duplicate = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));

        assertEquals(TaskState.SUCCEEDED, first.state());
        assertEquals(first.attemptId(), duplicate.attemptId());
        assertEquals(TaskState.SUCCEEDED, duplicate.state());
        assertEquals(1, fixture.calls.get());
        assertEquals(TaskState.SUCCEEDED, fixture.tasks.find(proposal.taskId(), 9001).orElseThrow().state());
    }

    @Test
    void changedOrderInvalidatesProposalBeforeAnyAssignment() {
        Fixture fixture = fixture();
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();
        fixture.car.order = new CarSystemReadClient.WorkOrder(1001, 1, 0, null,
                "2026-11-01 10:01:00");

        SimulatedConfirmationFlow.Decision decision = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));

        assertEquals(TaskState.STALE_PROPOSAL, decision.state());
        assertEquals(0, fixture.calls.get());
        assertEquals(TaskState.STALE_PROPOSAL, fixture.tasks.find(proposal.taskId(), 9001).orElseThrow().state());
    }

    @Test
    void changedPolicyTextInvalidatesProposalEvenWhenSourceReferenceIsTheSame() {
        Fixture fixture = fixture();
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();
        fixture.policyText = "模拟政策原文：本时段不再接受试驾申请。";

        SimulatedConfirmationFlow.Decision decision = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));

        assertEquals(TaskState.STALE_PROPOSAL, decision.state());
        assertEquals(0, fixture.calls.get());
    }

    @Test
    void timeoutStaysUnknownAfterReadOnlyObservationAndNeverRetries() {
        Fixture fixture = fixture();
        fixture.behavior = (cookie, order, advisor) -> {
            throw new IllegalStateException("模拟超时");
        };
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();

        SimulatedConfirmationFlow.Decision first = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));
        SimulatedConfirmationFlow.Decision repeated = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));

        assertEquals(TaskState.OUTCOME_UNKNOWN, first.state());
        assertEquals(TaskState.OUTCOME_UNKNOWN, repeated.state());
        assertEquals("UNCHANGED", first.observation());
        assertEquals(1, fixture.calls.get());
        assertEquals(first.attemptId(), repeated.attemptId());
    }

    @Test
    void timeoutWithTargetStateObservedStillStaysUnknown() {
        Fixture fixture = fixture();
        fixture.behavior = (cookie, order, advisor) -> {
            fixture.car.order = new CarSystemReadClient.WorkOrder(order, 1, 1, advisor,
                    "2026-11-01 10:02:00");
            throw new IllegalStateException("模拟响应丢失");
        };
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();

        SimulatedConfirmationFlow.Decision decision = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));

        assertEquals(TaskState.OUTCOME_UNKNOWN, decision.state());
        assertEquals("TARGET_ASSIGNED", decision.observation());
        assertEquals(1, fixture.calls.get());
    }

    @Test
    void explicitRejectionWithUnchangedOrderIsDefiniteFailure() {
        Fixture fixture = fixture();
        fixture.behavior = (cookie, order, advisor) -> SimulatedAssignmentPort.Result.REJECTED;
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();

        SimulatedConfirmationFlow.Decision decision = fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true));

        assertEquals(TaskState.DEFINITELY_FAILED, decision.state());
        assertEquals("UNCHANGED", decision.observation());
        assertEquals(1, fixture.calls.get());
    }

    @Test
    void interruptedAttemptCanBeConservativelyRecoveredWithoutAnotherCall() {
        Fixture fixture = fixture();
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();
        JdbcConfirmationStore.Attempt claimed = fixture.confirmations.claim(proposal, 9001).orElseThrow();

        assertEquals(1, fixture.confirmations.recoverInterrupted(Instant.now().plusSeconds(3600)));
        assertEquals(TaskState.OUTCOME_UNKNOWN, fixture.confirmations.findAttempt(
                proposal.taskId(), 9001, proposal.id()).orElseThrow().state());
        assertEquals("INTERRUPTED", fixture.confirmations.findAttempt(
                proposal.taskId(), 9001, proposal.id()).orElseThrow().observation());
        assertEquals(claimed.id(), fixture.flow().confirm(COOKIE, proposal.taskId(),
                new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true)).attemptId());
        assertEquals(0, fixture.calls.get());
    }

    @Test
    void concurrentConfirmationSeesTheSamePersistedAttempt() throws InterruptedException {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fixture.behavior = (cookie, order, advisor) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("模拟端等待超时");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            fixture.car.order = new CarSystemReadClient.WorkOrder(order, 1, 1, advisor,
                    "2026-11-01 10:02:00");
            return SimulatedAssignmentPort.Result.ACCEPTED;
        };
        JdbcConfirmationStore.Proposal proposal = fixture.proposal();
        ConfirmationCommand command = new ConfirmationCommand(proposal.id().toString(), proposal.taskVersion(), true);
        AtomicReference<SimulatedConfirmationFlow.Decision> first = new AtomicReference<>();
        Thread worker = new Thread(() -> first.set(fixture.flow().confirm(COOKIE, proposal.taskId(), command)));
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SimulatedConfirmationFlow.Decision second = fixture.flow().confirm(COOKIE, proposal.taskId(), command);
            assertEquals(TaskState.EXECUTING, second.state());
            assertEquals(1, fixture.calls.get());
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertEquals(TaskState.SUCCEEDED, first.get().state());
        assertEquals(1, fixture.calls.get());
    }

    @Test
    void rejectionExpiredProposalAndOtherAdministratorCannotAssign() {
        Fixture fixture = fixture();
        JdbcConfirmationStore.Proposal rejected = fixture.proposal();
        assertEquals(TaskState.REJECTED, fixture.flow().confirm(COOKIE, rejected.taskId(),
                new ConfirmationCommand(rejected.id().toString(), rejected.taskVersion(), false)).state());
        assertEquals(TaskState.REJECTED, fixture.flow().confirm(COOKIE, rejected.taskId(),
                new ConfirmationCommand(rejected.id().toString(), rejected.taskVersion(), false)).state());
        assertEquals(0, fixture.calls.get());

        JdbcConfirmationStore.Proposal expired = fixture.proposal();
        fixture.car.identity = new CarSystemReadClient.Identity(9002, 2, 0);
        assertEquals(TaskState.STALE_PROPOSAL, fixture.flow().confirm(COOKIE, expired.taskId(),
                new ConfirmationCommand(expired.id().toString(), expired.taskVersion(), true)).state());
        fixture.car.identity = new CarSystemReadClient.Identity(9002, 0, 0);
        assertThrows(SecurityException.class, () -> fixture.flow().confirm(COOKIE, expired.taskId(),
                new ConfirmationCommand(expired.id().toString(), expired.taskVersion(), true)));
        fixture.car.identity = new CarSystemReadClient.Identity(9001, 2, 0);
        Clock later = Clock.fixed(CLOCK.instant().plusSeconds(601), ZoneOffset.UTC);
        assertEquals(TaskState.STALE_PROPOSAL, fixture.flow(later).confirm(COOKIE, expired.taskId(),
                new ConfirmationCommand(expired.id().toString(), expired.taskVersion(), true)).state());
        assertEquals(TaskState.STALE_PROPOSAL,
                fixture.tasks.find(expired.taskId(), 9001).orElseThrow().state());
        assertEquals(0, fixture.calls.get());
    }

    private static Fixture fixture() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"),
                System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD")));
        JdbcAgentTaskStore tasks = new JdbcAgentTaskStore(jdbc, new ObjectMapper().findAndRegisterModules());
        tasks.initializeSchema();
        JdbcConfirmationStore confirmations = new JdbcConfirmationStore(jdbc);
        confirmations.initializeSchema();
        return new Fixture(tasks, confirmations, new FakeCarSystem());
    }

    private static final class Fixture {
        final JdbcAgentTaskStore tasks;
        final JdbcConfirmationStore confirmations;
        final FakeCarSystem car;
        final AtomicInteger calls = new AtomicInteger();
        String policyText = "模拟政策原文：工作日可以提交试驾申请。";
        SimulatedAssignmentPort behavior = (cookie, order, advisor) -> SimulatedAssignmentPort.Result.ACCEPTED;

        Fixture(JdbcAgentTaskStore tasks, JdbcConfirmationStore confirmations, FakeCarSystem car) {
            this.tasks = tasks;
            this.confirmations = confirmations;
            this.car = car;
        }

        SimulatedConfirmationFlow flow() {
            return flow(CLOCK);
        }

        SimulatedConfirmationFlow flow(Clock clock) {
            return new SimulatedConfirmationFlow(car, new ReadOnlyAdviceFlow(car,
                    (store, model, time, question) -> new PolicyEvidenceGate.Decision(
                            PolicyEvidenceGate.Outcome.CANDIDATES,
                            List.of(new PolicyEvidenceGate.Evidence("chunk-1", "POL-A", 1, SOURCE,
                                    policyText))), clock),
                    tasks, confirmations, (cookie, order, advisor) -> {
                        calls.incrementAndGet();
                        return behavior.assign(cookie, order, advisor);
                    }, clock);
        }

        JdbcConfirmationStore.Proposal proposal() {
            JdbcAgentTaskStore.Task opened = tasks.create(9001, car.order);
            ReadOnlyAdviceFlow.Supplement supplement = new ReadOnlyAdviceFlow.Supplement(
                    "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"));
            tasks.updateSupplement(opened.id(), 9001, 0, supplement).orElseThrow();
            ReadOnlyAdviceFlow.Result preview = new ReadOnlyAdviceFlow.Result(
                    ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, 1001, "模拟预览", List.of(),
                    List.of(new PolicyEvidenceGate.Evidence("chunk-1", "POL-A", 1, SOURCE,
                            "模拟政策原文：工作日可以提交试驾申请。")),
                    List.of(new ReadOnlyAdviceFlow.AdvisorLoad(301, "模拟顾问", 1, 2, false)),
                    CLOCK.instant(), false);
            JdbcAgentTaskStore.Task ready = tasks.recordPreview(opened.id(), 9001, 1, preview).orElseThrow();
            StructuredAdviceService.Advice advice = new StructuredAdviceService.Advice(
                    StructuredAdviceService.Outcome.CANDIDATE, 301L, 1, 2,
                    List.of(new StructuredAdviceService.Citation(SOURCE, "工作日可以提交试驾申请")),
                    "模拟建议", "advice-v1", "deepseek-flash", 20, 10);
            return flow().publish(COOKIE, new AgentAdviceOrchestrator.Recommendation(ready, preview, advice))
                    .orElseThrow();
        }
    }

    private static final class FakeCarSystem implements CarSystemReadClient {
        Identity identity = new Identity(9001, 2, 0);
        WorkOrder order = new WorkOrder(1001, 1, 0, null, "2026-11-01 10:00:00");

        @Override public Identity currentUser(String cookieHeader) { return identity; }
        @Override public WorkOrder getWorkOrder(String cookieHeader, long workOrderId) { return order; }
        @Override public List<Advisor> listAdvisors(String cookieHeader) {
            return List.of(new Advisor(301, "模拟顾问", 1, 0));
        }
        @Override public List<Workload> workloads(String cookieHeader) {
            return List.of(new Workload(301, 1, 2));
        }
    }
}
