package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.policy.PolicyEvidenceGate;
import com.zz.agent.store.NearbyStoreAvailabilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用隔离的真实 PostgreSQL 验证任务持久化、权限范围和并发版本。 */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class JdbcAgentTaskStoreProbeTest {

    @Test
    void taskSurvivesRepositoryRecreationAndIsInvisibleToAnotherAdmin() {
        JdbcTemplate jdbc = jdbc();
        JdbcAgentTaskStore first = new JdbcAgentTaskStore(jdbc, new ObjectMapper().findAndRegisterModules());
        first.initializeSchema();
        JdbcAgentTaskStore.Task created = first.create(41,
                new CarSystemReadClient.WorkOrder(7001, 1, 0, null, "2026-11-01 10:00:00"));

        JdbcAgentTaskStore second = new JdbcAgentTaskStore(jdbc, new ObjectMapper().findAndRegisterModules());
        JdbcAgentTaskStore.Task restored = second.find(created.id(), 41).orElseThrow();
        assertEquals(7001, restored.workOrderId());
        assertEquals("2026-11-01 10:00:00", restored.orderUpdatedAt());
        assertEquals(0, restored.version());
        assertEquals(TaskState.NEEDS_INPUT, restored.state());
        assertTrue(second.find(created.id(), 42).isEmpty());
        assertTrue(second.updateSupplement(created.id(), 42, 0,
                new ReadOnlyAdviceFlow.Supplement("STORE-A", "MX-7",
                        OffsetDateTime.parse("2026-11-07T14:00:00+08:00"))).isEmpty());
    }

    @Test
    void staleVersionCannotOverwriteAndSupplementClearsSavedPreview() {
        JdbcTemplate jdbc = jdbc();
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc, new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        JdbcAgentTaskStore.Task created = store.create(41,
                new CarSystemReadClient.WorkOrder(7002, 1, 0, null, "2026-11-01 10:01:00"));
        ReadOnlyAdviceFlow.Supplement supplement = new ReadOnlyAdviceFlow.Supplement("STORE-A", "MX-7",
                OffsetDateTime.parse("2026-11-07T14:00:00+08:00"));

        JdbcAgentTaskStore.Task updated = store.updateSupplement(created.id(), 41, 0, supplement).orElseThrow();
        assertEquals(1, updated.version());
        assertTrue(store.updateSupplement(created.id(), 41, 0, supplement).isEmpty());
        assertEquals("STORE-A", updated.supplement().storeId());

        ReadOnlyAdviceFlow.Result preview = new ReadOnlyAdviceFlow.Result(
                ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, 7002, "顾问姓名不应入库",
                List.of(), List.of(new PolicyEvidenceGate.Evidence("chunk-1", "POL-A", 1,
                "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1", "政策全文不应入库")),
                List.of(new ReadOnlyAdviceFlow.AdvisorLoad(91, "顾问张三", 2, 6, false)),
                Instant.parse("2026-11-01T02:00:00Z"), false);
        JdbcAgentTaskStore.Task withPreview = store.recordPreview(created.id(), 41, 1, preview).orElseThrow();
        assertEquals(TaskState.READY_FOR_ADMIN_REVIEW, withPreview.state());
        assertEquals(2, withPreview.version());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                withPreview.preview().policySourceRefs().getFirst());
        assertEquals(91, withPreview.preview().advisors().getFirst().advisorId());
        assertTrue(store.recordPreview(created.id(), 41, 1, preview).isEmpty());

        String stored = jdbc.queryForObject("SELECT preview_json::text FROM agent_task WHERE task_id = ?",
                String.class, created.id());
        assertNotNull(stored);
        assertFalse(stored.contains("顾问张三"));
        assertFalse(stored.contains("顾问姓名不应入库"));
        assertFalse(stored.contains("政策全文不应入库"));

        JdbcAgentTaskStore.Task invalidated = store.updateSupplement(created.id(), 41, 2,
                new ReadOnlyAdviceFlow.Supplement("STORE-C", "MX-7", supplement.appointmentAt())).orElseThrow();
        assertEquals(TaskState.NEEDS_INPUT, invalidated.state());
        assertEquals(3, invalidated.version());
        assertEquals("STORE-C", invalidated.supplement().storeId());
        assertEquals(null, invalidated.preview());
        assertEquals(null, jdbc.queryForObject("SELECT preview_json::text FROM agent_task WHERE task_id = ?",
                String.class, created.id()));
    }

    @Test
    void rejectsExecutableOrUnauthorizedPreview() {
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc(), new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        JdbcAgentTaskStore.Task task = store.create(41,
                new CarSystemReadClient.WorkOrder(7003, 1, 0, null, null));

        assertThrows(IllegalArgumentException.class, () -> store.recordPreview(task.id(), 41, 0,
                new ReadOnlyAdviceFlow.Result(ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, 7003,
                        "", List.of(), List.of(), List.of(), Instant.now(), true)));
        assertThrows(IllegalArgumentException.class, () -> store.recordPreview(task.id(), 41, 0,
                new ReadOnlyAdviceFlow.Result(ReadOnlyAdviceFlow.Stage.UNAUTHORIZED, 7003,
                        "", List.of(), List.of(), List.of(), Instant.now(), false)));
        assertThrows(IllegalArgumentException.class, () -> store.recordPreview(task.id(), 41, 0,
                new ReadOnlyAdviceFlow.Result(ReadOnlyAdviceFlow.Stage.NEEDS_INPUT, 9999,
                        "", List.of(), List.of(), List.of(), Instant.now(), false)));
        assertEquals(0, store.find(task.id(), 41).orElseThrow().version());
    }

    @Test
    void rejectsEvidenceReferenceOutsideLocalPolicyCorpus() {
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc(), new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        JdbcAgentTaskStore.Task task = store.create(41,
                new CarSystemReadClient.WorkOrder(7004, 1, 0, null, null));
        ReadOnlyAdviceFlow.Result preview = new ReadOnlyAdviceFlow.Result(
                ReadOnlyAdviceFlow.Stage.NEEDS_REVIEW, 7004, "", List.of(),
                List.of(new PolicyEvidenceGate.Evidence("chunk", "POL-A", 1,
                        "客户电话 13800000000", "")), List.of(), Instant.now(), false);

        assertThrows(IllegalArgumentException.class,
                () -> store.recordPreview(task.id(), 41, 0, preview));
        assertEquals(0, store.find(task.id(), 41).orElseThrow().version());
    }

    @Test
    void readyStateRequiresEvidenceAndAdvisorLoad() {
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc(), new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        JdbcAgentTaskStore.Task task = store.create(41,
                new CarSystemReadClient.WorkOrder(7005, 1, 0, null, null));
        ReadOnlyAdviceFlow.Result emptyReady = new ReadOnlyAdviceFlow.Result(
                ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, 7005, "",
                List.of(), List.of(), List.of(), Instant.now(), false);

        assertThrows(IllegalArgumentException.class,
                () -> store.recordPreview(task.id(), 41, 0, emptyReady));
        assertEquals(TaskState.NEEDS_INPUT, store.find(task.id(), 41).orElseThrow().state());
    }

    @Test
    void oversizedBlankSupplementIsRejectedBeforeDatabaseWrite() {
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc(), new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        JdbcAgentTaskStore.Task task = store.create(41,
                new CarSystemReadClient.WorkOrder(7006, 1, 0, null, null));

        assertThrows(IllegalArgumentException.class, () -> store.updateSupplement(task.id(), 41, 0,
                new ReadOnlyAdviceFlow.Supplement(" ".repeat(101), "MX-7", null)));
        assertEquals(0, store.find(task.id(), 41).orElseThrow().version());
    }

    @Test
    void customerChoiceIsVersionedIdempotentAndClearedByAdministratorEdit() {
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc(), new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        JdbcAgentTaskStore.Task task = store.create(41,
                new CarSystemReadClient.WorkOrder(7010, 1, 0, null, "v1", 5001L));
        Instant now = Instant.parse("2026-11-02T02:00:00Z");
        OffsetDateTime at = OffsetDateTime.parse("2026-11-07T14:00:00+08:00");
        List<NearbyStoreAvailabilityService.Option> options = List.of(
                new NearbyStoreAvailabilityService.Option("STORE-A", 1.0,
                        NearbyStoreAvailabilityService.Availability.AVAILABLE, true, "snap-a", now, true),
                new NearbyStoreAvailabilityService.Option("STORE-B", 2.0,
                        NearbyStoreAvailabilityService.Availability.NO_VEHICLE, false, "snap-b", now, true));
        JdbcAgentTaskStore.StoreOffer offer = store.createStoreOffer(task.id(), 5001, 0,
                "MX-7", at, options, now.plusSeconds(600));
        UUID offerId = offer.id();

        assertEquals(2, store.findStoreOffer(offerId).orElseThrow().options().size());
        assertTrue(store.chooseStore(offerId, 5001, "STORE-B", "snap-b", now).isEmpty());
        assertTrue(store.chooseStore(offerId, 5002, "STORE-A", "snap-a", now).isEmpty());
        assertTrue(store.chooseStore(offerId, 5001, "STORE-A", "changed-snapshot", now).isEmpty());
        assertTrue(store.chooseStore(offerId, 5001, "STORE-A", "snap-a", now.plusSeconds(601)).isEmpty());
        JdbcAgentTaskStore.StoreOffer competing = store.createStoreOffer(task.id(), 5001, 0,
                "MX-7", at, options, now.plusSeconds(600));
        JdbcAgentTaskStore.Task chosen = store.chooseStore(offerId, 5001, "STORE-A", "snap-a", now)
                .orElseThrow();
        assertEquals(1, chosen.version());
        assertEquals("STORE-A", chosen.supplement().storeId());
        assertEquals(offerId, chosen.storeChoice().offerId());
        assertEquals(5001, chosen.storeChoice().customerId());
        assertEquals(chosen, store.chooseStore(offerId, 5001, "STORE-A", "snap-a", now).orElseThrow());
        assertEquals(chosen, store.chooseStore(offerId, 5001, "STORE-A", "snap-a",
                now.plusSeconds(601)).orElseThrow());
        assertTrue(store.chooseStore(offerId, 5001, "STORE-B", "snap-b", now).isEmpty());
        assertTrue(store.chooseStore(competing.id(), 5001, "STORE-A", "snap-a", now).isEmpty());

        JdbcAgentTaskStore.Task revised = store.updateSupplement(task.id(), 41, 1,
                new ReadOnlyAdviceFlow.Supplement("STORE-C", "MX-7", at)).orElseThrow();
        assertEquals(2, revised.version());
        assertEquals(null, revised.storeChoice());
        assertTrue(store.chooseStore(offerId, 5001, "STORE-A", "snap-a", now).isEmpty());
    }

    @Test
    void customerChoiceInvalidatesEarlierAdministratorPreview() {
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc(), new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        JdbcAgentTaskStore.Task task = store.create(41,
                new CarSystemReadClient.WorkOrder(7011, 1, 0, null, "v1", 5001L));
        Instant now = Instant.parse("2026-11-02T02:00:00Z");
        JdbcAgentTaskStore.Task reviewed = store.recordPreview(task.id(), 41, 0,
                new ReadOnlyAdviceFlow.Result(ReadOnlyAdviceFlow.Stage.NEEDS_REVIEW,
                        7011, "需人工核对", List.of(), List.of(), List.of(), now, false)).orElseThrow();
        assertNotNull(reviewed.preview());
        OffsetDateTime at = OffsetDateTime.parse("2026-11-07T14:00:00+08:00");
        var option = new NearbyStoreAvailabilityService.Option("STORE-A", 1.0,
                NearbyStoreAvailabilityService.Availability.AVAILABLE, true, "snap-a", now, true);
        var offer = store.createStoreOffer(task.id(), 5001, 1, "MX-7", at,
                List.of(option), now.plusSeconds(600));

        JdbcAgentTaskStore.Task chosen = store.chooseStore(offer.id(), 5001,
                "STORE-A", "snap-a", now).orElseThrow();
        assertEquals(2, chosen.version());
        assertEquals(TaskState.NEEDS_INPUT, chosen.state());
        assertEquals(null, chosen.preview());
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"),
                System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD")));
    }
}
