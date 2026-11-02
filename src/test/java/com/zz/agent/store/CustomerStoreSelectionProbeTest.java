package com.zz.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.workflow.JdbcAgentTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 Agent 数据库加模拟下游读取，验证客户只改自己任务中的模拟选店。 */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class CustomerStoreSelectionProbeTest {

    private static final String COOKIE = "SESSION=customer-demo";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-07T14:00:00+08:00");

    @Test
    void ownerCanChooseAvailableStoreOnceWithoutChangingOriginalOrder() throws Exception {
        Fixture fixture = fixture(5001, 0);
        assertEquals(0, fixture.flow.view(COOKIE, fixture.task.id()).version());
        var offer = fixture.flow.offer(COOKIE, fixture.task.id(), 0,
                new GeoPoint(31.230400, 121.473700), "MX-7", AT);
        assertEquals(5, offer.options().size());
        assertTrue(fixture.flow.choose(COOKIE, offer.id(), "STORE-B").isEmpty());

        var chosen = fixture.flow.choose(COOKIE, offer.id(), "STORE-A").orElseThrow();
        assertEquals("STORE-A", chosen.supplement().storeId());
        assertEquals(1, chosen.version());
        assertEquals(5001, chosen.storeChoice().customerId());
        assertEquals(chosen, fixture.flow.choose(COOKIE, offer.id(), "STORE-A").orElseThrow());
        assertTrue(fixture.flow.choose(COOKIE, offer.id(), "STORE-C").isEmpty());
    }

    @Test
    void otherCustomerAndAdministratorCannotIssueOffer() throws Exception {
        Fixture other = fixture(5002, 0);
        assertThrows(SecurityException.class, () -> other.flow.view(COOKIE, other.task.id()));
        assertThrows(SecurityException.class, () -> other.flow.offer(COOKIE, other.task.id(), 0,
                new GeoPoint(31.230400, 121.473700), "MX-7", AT));
        Fixture admin = fixture(5001, 2);
        assertThrows(SecurityException.class, () -> admin.flow.offer(COOKIE, admin.task.id(), 0,
                new GeoPoint(31.230400, 121.473700), "MX-7", AT));
    }

    @Test
    void changedAvailabilityOrOrderStopsSelection() throws Exception {
        Fixture fixture = fixture(5001, 0);
        var offer = fixture.flow.offer(COOKIE, fixture.task.id(), 0,
                new GeoPoint(31.230400, 121.473700), "MX-7", AT);
        fixture.availability.set(NearbyStoreAvailabilityService.Availability.NO_VEHICLE);
        assertTrue(fixture.flow.choose(COOKIE, offer.id(), "STORE-A").isEmpty());
        assertEquals(0, fixture.store.find(fixture.task.id(), 41).orElseThrow().version());

        fixture.availability.set(NearbyStoreAvailabilityService.Availability.AVAILABLE);
        when(fixture.car.getWorkOrder(COOKIE, fixture.task.workOrderId())).thenReturn(
                new CarSystemReadClient.WorkOrder(fixture.task.workOrderId(), 1, 1, null, "v2", 5001L));
        assertTrue(fixture.flow.choose(COOKIE, offer.id(), "STORE-A").isEmpty());
        assertThrows(IllegalStateException.class, () -> fixture.flow.view(COOKIE, fixture.task.id()));
    }

    @Test
    void missingCustomerIdFailsClosed() throws Exception {
        Fixture fixture = fixture(5001, 0);
        when(fixture.car.getWorkOrder(COOKIE, fixture.task.workOrderId())).thenReturn(
                new CarSystemReadClient.WorkOrder(fixture.task.workOrderId(), 1, 0, null, "v1"));
        assertThrows(SecurityException.class, () -> fixture.flow.offer(COOKIE, fixture.task.id(), 0,
                new GeoPoint(31.230400, 121.473700), "MX-7", AT));
    }

    private static Fixture fixture(long loggedInId, int role) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"), System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD")));
        JdbcAgentTaskStore store = new JdbcAgentTaskStore(jdbc, new ObjectMapper().findAndRegisterModules());
        store.initializeSchema();
        var order = new CarSystemReadClient.WorkOrder(8001, 1, 0, null, "v1", 5001L);
        var task = store.create(41, order);
        CarSystemReadClient car = mock(CarSystemReadClient.class);
        when(car.currentUser(COOKIE)).thenReturn(new CarSystemReadClient.Identity(loggedInId, role, 0));
        when(car.getWorkOrder(COOKIE, task.workOrderId())).thenReturn(order);
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(
                Path.of("data/stores/simulated-v1.json"), CLOCK);
        AtomicReference<NearbyStoreAvailabilityService.Availability> availability =
                new AtomicReference<>(NearbyStoreAvailabilityService.Availability.AVAILABLE);
        NearbyStoreAvailabilityService.AvailabilityLookup lookup = (storeId, model, at) -> {
            var original = catalog.lookup(storeId, model, at);
            return "STORE-A".equals(storeId)
                    ? new NearbyStoreAvailabilityService.Snapshot(availability.get(),
                    original.id(), original.observedAt()) : original;
        };
        NearbyStoreAvailabilityService nearby = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), lookup, CLOCK);
        CustomerStoreSelectionService flow = new CustomerStoreSelectionService(car, store, nearby, lookup, CLOCK);
        return new Fixture(flow, store, task, car, availability);
    }

    private record Fixture(CustomerStoreSelectionService flow, JdbcAgentTaskStore store,
                           JdbcAgentTaskStore.Task task, CarSystemReadClient car,
                           AtomicReference<NearbyStoreAvailabilityService.Availability> availability) {
    }
}
