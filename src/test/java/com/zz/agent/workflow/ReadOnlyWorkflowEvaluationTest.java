package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.policy.PolicyEvidenceGate;
import com.zz.agent.policy.PolicySource;
import com.zz.agent.policy.PolicySourceLoader;
import com.zz.agent.policy.PolicyVectorDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用固定模拟响应回放只读流程；向量召回、模型和真实分配不在本项成绩内。 */
class ReadOnlyWorkflowEvaluationTest {

    private static final Path INPUT = Path.of("data/eval/workflow/read-only-v1.json");
    private static final Path OUTPUT = Path.of("target/eval/read-only-flow-v1.tsv");
    private static final Path SNAPSHOT = Path.of("data/eval/results/read-only-flow-v1.tsv");

    @Test
    void replayFixedReadOnlyCasesAndKeepPerCaseResults() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(Files.readString(INPUT));
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        List<Document> canonical = new PolicyVectorDocumentMapper().map(policies);
        Clock clock = Clock.fixed(Instant.parse(fixture.path("asOf").asText()), ZoneOffset.UTC);
        List<CarSystemReadClient.Advisor> advisors = new ArrayList<>();
        for (JsonNode item : fixture.path("advisors")) {
            advisors.add(new CarSystemReadClient.Advisor(item.path("id").asLong(), item.path("name").asText(),
                    item.path("userRole").asInt(), item.path("userStatus").asInt()));
        }
        List<CarSystemReadClient.Workload> workloads = new ArrayList<>();
        for (JsonNode item : fixture.path("workloads")) {
            workloads.add(new CarSystemReadClient.Workload(item.path("advisorId").asLong(),
                    item.path("processing").asInt(), item.path("total").asInt()));
        }

        StringBuilder report = new StringBuilder();
        report.append("# scope=").append(fixture.path("scope").asText()).append('\n');
        report.append("# fixtureSha256=").append(hash(Files.readAllBytes(INPUT))).append('\n');
        report.append("# corpusSha256=").append(corpusHash(policies)).append('\n');
        report.append("caseId\texpectedStage\tactualStage\texpectedPolicyOutcome\tactualPolicyOutcome")
                .append("\texpectedSourceRefs\tactualSourceRefs\texpectedAdvisorIds\tactualAdvisorIds")
                .append("\tmissingFields\torderCalls\tpolicyCalls\tadvisorCalls\tworkloadCalls")
                .append("\tassignmentEnabled\tpass\n");

        int passed = 0;
        int assignmentEnabled = 0;
        for (JsonNode item : fixture.path("cases")) {
            long orderId = item.path("orderId").asLong();
            FixtureCarSystem car = new FixtureCarSystem(
                    new CarSystemReadClient.Identity(fixture.path("admin").path("id").asLong(),
                            fixture.path("admin").path("userRole").asInt(),
                            fixture.path("admin").path("userStatus").asInt()),
                    new CarSystemReadClient.WorkOrder(orderId, 1, 0, null,
                            "2026-10-28 09:00:00"),
                    advisors, workloads, item.path("workloadBusinessCode").asInt());
            AtomicInteger policyCalls = new AtomicInteger();
            AtomicReference<String> policyOutcome = new AtomicReference<>("NOT_CALLED");
            ReadOnlyAdviceFlow.PolicyLookup lookup = (storeId, modelCode, at, question) -> {
                policyCalls.incrementAndGet();
                PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                        policies, storeId, modelCode, at, question, canonical);
                policyOutcome.set(decision.outcome().name());
                return decision;
            };
            ReadOnlyAdviceFlow flow = new ReadOnlyAdviceFlow(car, lookup, clock);
            OffsetDateTime appointment = item.path("appointmentAt").isNull() ? null
                    : OffsetDateTime.parse(item.path("appointmentAt").asText());
            ReadOnlyAdviceFlow.Result actual = flow.preview(orderId, "SESSION=offline-fixture",
                    new ReadOnlyAdviceFlow.Supplement(item.path("storeId").asText(),
                            item.path("modelCode").asText(), appointment));

            List<String> actualRefs = actual.policyEvidence().stream()
                    .map(PolicyEvidenceGate.Evidence::sourceRef).toList();
            List<Long> actualAdvisorIds = actual.advisors().stream()
                    .map(ReadOnlyAdviceFlow.AdvisorLoad::advisorId).toList();
            boolean pass = item.path("expectedStage").asText().equals(actual.stage().name())
                    && item.path("expectedPolicyOutcome").asText().equals(policyOutcome.get())
                    && Set.copyOf(strings(item.path("expectedSourceRefs"))).equals(Set.copyOf(actualRefs))
                    && actualRefs.size() == Set.copyOf(actualRefs).size()
                    && longs(item.path("expectedAdvisorIds")).equals(actualAdvisorIds)
                    && strings(item.path("expectedMissingFields")).equals(actual.missingFields())
                    && car.orderCalls == 1
                    && policyCalls.get() == item.path("expectedPolicyCalls").asInt()
                    && car.advisorCalls == item.path("expectedAdvisorCalls").asInt()
                    && car.workloadCalls == item.path("expectedWorkloadCalls").asInt()
                    && !actual.assignmentEnabled();
            if (pass) {
                passed++;
            }
            if (actual.assignmentEnabled()) {
                assignmentEnabled++;
            }
            report.append(String.join("\t", item.path("caseId").asText(),
                    item.path("expectedStage").asText(), actual.stage().name(),
                    item.path("expectedPolicyOutcome").asText(), policyOutcome.get(),
                    join(strings(item.path("expectedSourceRefs"))), join(actualRefs),
                    join(longs(item.path("expectedAdvisorIds"))), join(actualAdvisorIds),
                    join(actual.missingFields()), Integer.toString(car.orderCalls),
                    Integer.toString(policyCalls.get()), Integer.toString(car.advisorCalls),
                    Integer.toString(car.workloadCalls), Boolean.toString(actual.assignmentEnabled()),
                    pass ? "PASS" : "FAIL")).append('\n');
        }
        report.append("# exactCasePass=").append(passed).append('/')
                .append(fixture.path("cases").size()).append('\n');
        report.append("# assignmentEnabled=").append(assignmentEnabled).append('/')
                .append(fixture.path("cases").size()).append('\n');
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
        assertEquals(fixture.path("cases").size(), passed, "逐例差异见 " + OUTPUT);
        assertTrue(Files.exists(SNAPSHOT), "先人工核对 " + OUTPUT + " 再保存结果快照");
        assertEquals(Files.readString(SNAPSHOT), report.toString(), "本次结果与保存的快照不同");
    }

    private static List<String> strings(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static List<Long> longs(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private static String join(List<?> values) {
        return values.isEmpty() ? "-" : String.join("|", values.stream()
                .map(Object::toString).collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
    }

    private static String corpusHash(List<PolicySource> policies) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : policies.stream().map(source -> source.sourceRef().split("#", 2)[0])
                .distinct().sorted().toList()) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(Path.of(name)));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class FixtureCarSystem implements CarSystemReadClient {
        private final Identity identity;
        private final WorkOrder order;
        private final List<Advisor> advisors;
        private final List<Workload> workloads;
        private final int workloadBusinessCode;
        private int orderCalls;
        private int advisorCalls;
        private int workloadCalls;

        private FixtureCarSystem(Identity identity, WorkOrder order, List<Advisor> advisors,
                                 List<Workload> workloads, int workloadBusinessCode) {
            this.identity = identity;
            this.order = order;
            this.advisors = advisors;
            this.workloads = workloads;
            this.workloadBusinessCode = workloadBusinessCode;
        }

        @Override
        public Identity currentUser(String cookieHeader) {
            return identity;
        }

        @Override
        public WorkOrder getWorkOrder(String cookieHeader, long workOrderId) {
            orderCalls++;
            assertEquals(order.id(), workOrderId);
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
            if (workloadBusinessCode != 0) {
                throw new ReadException(ReadException.Kind.BUSINESS_FAILURE,
                        workloadBusinessCode, "模拟工作量接口返回业务失败");
            }
            return workloads;
        }
    }
}
