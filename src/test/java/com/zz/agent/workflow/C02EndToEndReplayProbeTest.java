package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.policy.PolicyEvidenceGate;
import com.zz.agent.policy.PolicyKeywordSearch;
import com.zz.agent.policy.PolicySource;
import com.zz.agent.policy.PolicySourceLoader;
import com.zz.agent.policy.PolicyVectorDocumentMapper;
import com.zz.agent.store.CustomerStoreSelectionService;
import com.zz.agent.store.GeoPoint;
import com.zz.agent.store.NearbyStoreAvailabilityService;
import com.zz.agent.store.NearbyStoreSelector;
import com.zz.agent.store.SimulatedStoreCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C02 固定案例的数据库回放：本地模拟数据、离线关键词检索与确定性模型响应。 */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class C02EndToEndReplayProbeTest {

    private static final String ADMIN = "SESSION=simulated-admin";
    private static final String CUSTOMER = "SESSION=simulated-customer";
    private static final Path CASE = Path.of("data/eval/store-selection/c02-v1.json");
    private static final Path RESULT = Path.of("target/eval/c02-replay-v1.json");

    @Test
    void customerChoiceContinuesIntoCitedAdministratorAdviceWithoutAssignment() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        JsonNode expected = json.readTree(CASE.toFile());
        MutableClock clock = new MutableClock(OffsetDateTime.parse(expected.path("asOf").asText()).toInstant());
        OffsetDateTime appointment = OffsetDateTime.parse(expected.path("request").path("appointmentAt").asText());
        FakeCarSystem car = new FakeCarSystem(expected.path("workOrder").path("id").asLong(),
                expected.path("workOrder").path("customerId").asLong());
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"), System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD")));
        JdbcAgentTaskStore tasks = new JdbcAgentTaskStore(jdbc, json);
        tasks.initializeSchema();
        AgentTaskWorkflow workflow = workflow(car, tasks, clock);

        JdbcAgentTaskStore.Task opened = workflow.open(ADMIN, car.order.id());
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(Path.of("data/stores/simulated-v1.json"), clock);
        NearbyStoreAvailabilityService nearby = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), catalog, clock);
        CustomerStoreSelectionService customer = new CustomerStoreSelectionService(
                car, tasks, nearby, catalog, clock);
        JsonNode request = expected.path("request");
        JsonNode point = request.path("customerLocation");
        var offer = customer.offer(CUSTOMER, opened.id(), opened.version(),
                new GeoPoint(point.path("latitude").asDouble(), point.path("longitude").asDouble()),
                request.path("confirmedModelCode").asText(), appointment);
        List<String> displayed = offer.options().stream().map(NearbyStoreAvailabilityService.Option::storeId).toList();
        List<String> selectable = offer.options().stream().filter(NearbyStoreAvailabilityService.Option::selectable)
                .map(NearbyStoreAvailabilityService.Option::storeId).toList();
        assertEquals(json.convertValue(expected.path("expectedInitialDisplay").path("storeIdsInOrder"),
                json.getTypeFactory().constructCollectionType(List.class, String.class)), displayed);
        assertEquals(json.convertValue(expected.path("expectedInitialDisplay").path("selectableStoreIds"),
                json.getTypeFactory().constructCollectionType(List.class, String.class)), selectable);

        JsonNode selectCase = expected.path("selectionCases").get(0);
        assertEquals("SAME_AS_OFFER", selectCase.path("availabilityAtSelection").path("snapshotId").asText());
        assertEquals("SAME_AS_OFFER", selectCase.path("expectedStoredSnapshotId").asText());
        clock.set(OffsetDateTime.parse(selectCase.path("expectedStoredSelectionAt").asText()).toInstant());
        JdbcAgentTaskStore.Task chosen = customer.choose(CUSTOMER, offer.id(),
                selectCase.path("selectedStoreId").asText()).orElseThrow();
        String offeredSnapshot = offer.options().stream().filter(option -> option.storeId().equals("STORE-C"))
                .findFirst().orElseThrow().snapshotId();
        assertEquals("STORE-C", chosen.supplement().storeId());
        assertEquals(appointment.toInstant(), chosen.supplement().appointmentAt().toInstant());
        assertEquals(offeredSnapshot, chosen.storeChoice().snapshotId());
        assertEquals(clock.instant(), chosen.storeChoice().selectedAt());
        assertEquals(chosen, customer.choose(CUSTOMER, offer.id(), "STORE-C").orElseThrow());

        AtomicInteger modelCalls = new AtomicInteger();
        StructuredAdviceService advice = new StructuredAdviceService((system, user) -> {
            modelCalls.incrementAndGet();
            assertTrue(user.contains("STORE-C"));
            String output = """
                    {"advisorId":301,"citations":[{"sourceRef":"data/policies/STORE-C-MX-7-test-drive-v1.md#rule-1",
                    "quote":"试驾申请时间可落在每日 10:00（含）至 17:00（不含）"}]}
                    """;
            return new StructuredAdviceService.Completion(output, "offline-deterministic-fixture", 0, 0);
        }, json);
        AgentAdviceOrchestrator.Recommendation recommendation =
                new AgentAdviceOrchestrator(workflow, advice)
                        .refreshAndSuggest(ADMIN, opened.id(), chosen.version()).orElseThrow();
        assertEquals(TaskState.READY_FOR_ADMIN_REVIEW, recommendation.task().state());
        assertEquals(ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, recommendation.preview().stage());
        assertEquals(StructuredAdviceService.Outcome.CANDIDATE, recommendation.advice().outcome());
        assertEquals(301L, recommendation.advice().advisorId());
        assertEquals("data/policies/STORE-C-MX-7-test-drive-v1.md#rule-1",
                recommendation.advice().citations().getFirst().sourceRef());
        assertEquals(1, modelCalls.get());
        assertFalse(recommendation.preview().assignmentEnabled());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", "C02-SELECT-C");
        result.put("runDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        result.put("caseFile", CASE.toString());
        result.put("mode", "offline: simulated carSystem, simulated store catalog, keyword policy retrieval, deterministic model");
        result.put("steps", List.of(
                Map.of("step", "admin_open", "state", opened.state().name()),
                Map.of("step", "customer_offer", "displayed", displayed, "selectable", selectable),
                Map.of("step", "customer_choose", "storeId", chosen.supplement().storeId(),
                        "selectedAt", chosen.storeChoice().selectedAt().toString(),
                        "snapshotId", chosen.storeChoice().snapshotId(),
                        "sameSnapshotAsOffer", chosen.storeChoice().snapshotId().equals(offeredSnapshot)),
                Map.of("step", "admin_refresh", "state", recommendation.task().state().name(),
                        "modelCode", chosen.supplement().modelCode(),
                        "appointmentAt", chosen.supplement().appointmentAt().toString(),
                        "policySourceRefs", recommendation.task().preview().policySourceRefs(),
                        "advisorIds", recommendation.preview().advisors().stream()
                                .map(ReadOnlyAdviceFlow.AdvisorLoad::advisorId).toList()),
                Map.of("step", "advice", "outcome", recommendation.advice().outcome().name(),
                        "advisorId", recommendation.advice().advisorId(),
                        "processing", recommendation.advice().processing(),
                        "total", recommendation.advice().total(),
                        "citation", recommendation.advice().citations().getFirst().sourceRef())));
        result.put("assignmentEnabled", false);
        result.put("assignmentPostCalls", 0);
        result.put("assignmentCallScope", "offline replay has no assignment client");
        result.put("result", "PASS");
        Files.createDirectories(RESULT.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(RESULT.toFile(), result);
    }

    private static AgentTaskWorkflow workflow(FakeCarSystem car, JdbcAgentTaskStore tasks, Clock clock)
            throws Exception {
        List<PolicySource> sources = new PolicySourceLoader().load(Path.of("data/policies"));
        List<Document> documents = new PolicyVectorDocumentMapper().map(sources);
        PolicyKeywordSearch keywords = new PolicyKeywordSearch();
        PolicyEvidenceGate gate = new PolicyEvidenceGate();
        ReadOnlyAdviceFlow.PolicyLookup lookup = (storeId, modelCode, at, question) -> {
            var found = keywords.find(sources, storeId, modelCode, at, question);
            List<String> ids = found.hits().stream().map(PolicyKeywordSearch.Hit::chunkId).toList();
            List<Document> retrieved = documents.stream()
                    .filter(document -> ids.contains(document.getMetadata().get("chunkId"))).toList();
            return gate.evaluate(sources, storeId, modelCode, at, question, retrieved);
        };
        return new AgentTaskWorkflow(car, new ReadOnlyAdviceFlow(car, lookup, clock), tasks, clock);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static final class FakeCarSystem implements CarSystemReadClient {
        private final WorkOrder order;
        private final long customerId;

        private FakeCarSystem(long orderId, long customerId) {
            this.order = new WorkOrder(orderId, 1, 0, null, "c02-v1", customerId);
            this.customerId = customerId;
        }

        @Override
        public Identity currentUser(String cookieHeader) {
            return ADMIN.equals(cookieHeader) ? new Identity(9001, 2, 0)
                    : CUSTOMER.equals(cookieHeader) ? new Identity(customerId, 0, 0) : null;
        }

        @Override
        public WorkOrder getWorkOrder(String cookieHeader, long workOrderId) {
            return workOrderId == order.id() ? order : null;
        }

        @Override
        public List<Advisor> listAdvisors(String cookieHeader) {
            return List.of(new Advisor(301, "模拟顾问甲", 1, 0), new Advisor(302, "模拟顾问乙", 1, 0));
        }

        @Override
        public List<Workload> workloads(String cookieHeader) {
            return List.of(new Workload(301, 1, 2), new Workload(302, 3, 5));
        }
    }
}
