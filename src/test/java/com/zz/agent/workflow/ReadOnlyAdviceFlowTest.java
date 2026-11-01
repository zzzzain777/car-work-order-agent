package com.zz.agent.workflow;

import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.policy.PolicyEvidenceGate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定案例验证读取顺序、停止条件和顾问工作量合并。 */
class ReadOnlyAdviceFlowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-07T14:00:00+08:00");
    private static final String COOKIE = "SESSION=simulated-session";

    @Test
    void missingExactTimeAsksForInputBeforePolicyAndAdvisorCalls() {
        FakeCarSystem car = new FakeCarSystem();
        CounterPolicy policy = new CounterPolicy();

        ReadOnlyAdviceFlow.Result result = flow(car, policy).preview(1001, COOKIE,
                new ReadOnlyAdviceFlow.Supplement("STORE-A", "MX-7", null));

        assertEquals(ReadOnlyAdviceFlow.Stage.NEEDS_INPUT, result.stage());
        assertEquals(List.of("appointmentAt"), result.missingFields());
        assertEquals(0, policy.calls);
        assertEquals(0, car.advisorCalls);
        assertEquals(0, car.workloadCalls);
        assertFalse(result.assignmentEnabled());
    }

    @Test
    void completeEvidenceStopsAtReadOnlyAdminReview() {
        FakeCarSystem car = new FakeCarSystem();
        car.advisors = List.of(
                new CarSystemReadClient.Advisor(301, "模拟顾问甲", 1, 0),
                new CarSystemReadClient.Advisor(302, "模拟顾问乙", 1, 0),
                new CarSystemReadClient.Advisor(303, "封禁顾问", 1, 1));
        car.workloads = List.of(new CarSystemReadClient.Workload(302, 3, 5));
        CounterPolicy policy = new CounterPolicy();

        ReadOnlyAdviceFlow.Result result = flow(car, policy).preview(1001, COOKIE, complete());

        assertEquals(ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, result.stage());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                result.policyEvidence().getFirst().sourceRef());
        assertEquals(List.of(301L, 302L), result.advisors().stream().map(ReadOnlyAdviceFlow.AdvisorLoad::advisorId).toList());
        assertEquals(0, result.advisors().getFirst().processing());
        assertEquals(1, policy.calls);
        assertEquals(1, car.advisorCalls);
        assertEquals(1, car.workloadCalls);
        assertFalse(result.assignmentEnabled());
        assertTrue(result.message().contains("门店归属"));
        assertTrue(result.message().contains("优先核对顾问 301"));
    }

    @Test
    void ordinaryUserAndAssignedOrderStopBeforeEvidenceCalls() {
        FakeCarSystem car = new FakeCarSystem();
        CounterPolicy policy = new CounterPolicy();
        car.identity = new CarSystemReadClient.Identity(9001, 0, 0);

        assertEquals(ReadOnlyAdviceFlow.Stage.UNAUTHORIZED,
                flow(car, policy).preview(1001, COOKIE, complete()).stage());
        assertEquals(0, car.orderCalls);

        car.identity = new CarSystemReadClient.Identity(9001, 2, 0);
        car.order = new CarSystemReadClient.WorkOrder(1001, 1, 1, 301L, "2026-11-01 09:00:00");
        assertEquals(ReadOnlyAdviceFlow.Stage.NOT_APPLICABLE,
                flow(car, policy).preview(1001, COOKIE, complete()).stage());
        assertEquals(0, policy.calls);
    }

    @Test
    void policyConflictStopsBeforeAdvisorCalls() {
        FakeCarSystem car = new FakeCarSystem();
        CounterPolicy policy = new CounterPolicy();
        policy.decision = new PolicyEvidenceGate.Decision(PolicyEvidenceGate.Outcome.REVIEW_REQUIRED, List.of());

        ReadOnlyAdviceFlow.Result result = flow(car, policy).preview(1001, COOKIE, complete());

        assertEquals(ReadOnlyAdviceFlow.Stage.NEEDS_REVIEW, result.stage());
        assertEquals(0, car.advisorCalls);
        assertFalse(result.assignmentEnabled());
    }

    @Test
    void advisorToolFailureNeverTurnsIntoZeroWorkload() {
        FakeCarSystem car = new FakeCarSystem();
        car.failWorkloads = true;
        CounterPolicy policy = new CounterPolicy();

        ReadOnlyAdviceFlow.Result result = flow(car, policy).preview(1001, COOKIE, complete());

        assertEquals(ReadOnlyAdviceFlow.Stage.NEEDS_REVIEW, result.stage());
        assertTrue(result.advisors().isEmpty());
        assertTrue(result.message().contains("工作量"));
    }

    @Test
    void revokedSessionDuringAdvisorReadReturnsNoEvidence() {
        FakeCarSystem car = new FakeCarSystem();
        car.failWorkloads = true;
        car.workloadFailureKind = CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED;

        ReadOnlyAdviceFlow.Result result = flow(car, new CounterPolicy()).preview(1001, COOKIE, complete());

        assertEquals(ReadOnlyAdviceFlow.Stage.UNAUTHORIZED, result.stage());
        assertTrue(result.policyEvidence().isEmpty());
        assertTrue(result.advisors().isEmpty());
    }

    @Test
    void pastAppointmentRequiresNewTimeWithoutCallingPolicy() {
        FakeCarSystem car = new FakeCarSystem();
        CounterPolicy policy = new CounterPolicy();
        ReadOnlyAdviceFlow.Supplement past = new ReadOnlyAdviceFlow.Supplement(
                "STORE-A", "MX-7", OffsetDateTime.parse("2026-10-31T14:00:00+08:00"));

        ReadOnlyAdviceFlow.Result result = flow(car, policy).preview(1001, COOKIE, past);

        assertEquals(ReadOnlyAdviceFlow.Stage.NEEDS_INPUT, result.stage());
        assertEquals(List.of("appointmentAt"), result.missingFields());
        assertEquals(0, policy.calls);
    }

    @Test
    void duplicateAdvisorRowsCannotProduceAConfirmationPreview() {
        FakeCarSystem car = new FakeCarSystem();
        car.advisors = List.of(new CarSystemReadClient.Advisor(301, "模拟顾问甲", 1, 0),
                new CarSystemReadClient.Advisor(301, "重复顾问", 1, 0));

        ReadOnlyAdviceFlow.Result result = flow(car, new CounterPolicy()).preview(1001, COOKIE, complete());

        assertEquals(ReadOnlyAdviceFlow.Stage.NEEDS_REVIEW, result.stage());
        assertTrue(result.advisors().isEmpty());
    }

    private static ReadOnlyAdviceFlow flow(FakeCarSystem car, CounterPolicy policy) {
        return new ReadOnlyAdviceFlow(car, policy, CLOCK);
    }

    private static ReadOnlyAdviceFlow.Supplement complete() {
        return new ReadOnlyAdviceFlow.Supplement("STORE-A", "MX-7", TIME);
    }

    private static final class CounterPolicy implements ReadOnlyAdviceFlow.PolicyLookup {
        int calls;
        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate.Decision(
                PolicyEvidenceGate.Outcome.CANDIDATES,
                List.of(new PolicyEvidenceGate.Evidence("chunk-1", "POL-STORE-A-MX-7-TEST", 1,
                        "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1", "模拟政策原文")));

        @Override
        public PolicyEvidenceGate.Decision search(String storeId, String modelCode,
                                                  OffsetDateTime appointmentAt, String question) throws IOException {
            calls++;
            return decision;
        }
    }

    private static final class FakeCarSystem implements CarSystemReadClient {
        Identity identity = new Identity(9001, 2, 0);
        WorkOrder order = new WorkOrder(1001, 1, 0, null, "2026-11-01 09:00:00");
        List<Advisor> advisors = List.of(new Advisor(301, "模拟顾问甲", 1, 0));
        List<Workload> workloads = List.of(new Workload(301, 1, 2));
        int orderCalls;
        int advisorCalls;
        int workloadCalls;
        boolean failWorkloads;
        ReadException.Kind workloadFailureKind = ReadException.Kind.BUSINESS_FAILURE;

        @Override
        public Identity currentUser(String cookieHeader) {
            return identity;
        }

        @Override
        public WorkOrder getWorkOrder(String cookieHeader, long workOrderId) {
            orderCalls++;
            return order;
        }

        @Override
        public List<Advisor> listAdvisors(String cookieHeader) {
            advisorCalls++;
            return advisors;
        }

        @Override
        public List<Workload> workloads(String cookieHeader) {
            workloadCalls++;
            if (failWorkloads) {
                throw new ReadException(workloadFailureKind, 50000, "模拟工作量接口失败");
            }
            return workloads;
        }
    }
}
