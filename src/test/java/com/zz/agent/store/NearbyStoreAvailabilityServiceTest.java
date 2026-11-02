package com.zz.agent.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyStoreAvailabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final OffsetDateTime APPOINTMENT = OffsetDateTime.parse("2026-11-07T14:00:00+08:00");
    private static final GeoPoint CUSTOMER = new GeoPoint(31.230400, 121.473700);

    @Test
    void c02KeepsUnavailableStoresWithinTheNearestFive() throws Exception {
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(
                Path.of("data/stores/simulated-v1.json"), CLOCK);
        NearbyStoreAvailabilityService service = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), catalog, CLOCK);

        List<NearbyStoreAvailabilityService.Option> result =
                service.findNearestFive(CUSTOMER, "MX-7", APPOINTMENT);

        assertEquals(List.of("STORE-A", "STORE-B", "STORE-C", "STORE-D", "STORE-E"),
                result.stream().map(NearbyStoreAvailabilityService.Option::storeId).toList());
        assertEquals(List.of(true, false, true, false, true),
                result.stream().map(NearbyStoreAvailabilityService.Option::selectable).toList());
        assertEquals(List.of("AVAILABLE", "NO_VEHICLE", "AVAILABLE", "SLOT_FULL", "AVAILABLE"),
                result.stream().map(option -> option.availability().name()).toList());
        assertTrue(result.stream().allMatch(NearbyStoreAvailabilityService.Option::simulated));
        assertTrue(result.stream().allMatch(option -> NOW.equals(option.observedAt())));
        assertFalse(result.stream().anyMatch(option -> option.storeId().equals("STORE-F")));
    }

    @Test
    void lookupFailureLeavesThatStoreUnknownWithoutPromotingTheSixth() throws Exception {
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(
                Path.of("data/stores/simulated-v1.json"), CLOCK);
        NearbyStoreAvailabilityService.AvailabilityLookup failing = (storeId, modelCode, at) -> {
            if (storeId.equals("STORE-C")) {
                throw new IllegalStateException("模拟查询故障");
            }
            return catalog.lookup(storeId, modelCode, at);
        };
        NearbyStoreAvailabilityService service = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), failing, CLOCK);

        List<NearbyStoreAvailabilityService.Option> result =
                service.findNearestFive(CUSTOMER, "MX-7", APPOINTMENT);

        assertEquals(5, result.size());
        assertEquals("STORE-C", result.get(2).storeId());
        assertEquals(NearbyStoreAvailabilityService.Availability.UNKNOWN, result.get(2).availability());
        assertFalse(result.get(2).selectable());
        assertFalse(result.stream().anyMatch(option -> option.storeId().equals("STORE-F")));
    }

    @Test
    void invalidLocationOrPastAppointmentCannotProduceOptions() throws Exception {
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(
                Path.of("data/stores/simulated-v1.json"), CLOCK);
        NearbyStoreAvailabilityService service = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), catalog, CLOCK);

        assertThrows(IllegalArgumentException.class, () -> service.findNearestFive(
                new GeoPoint(Double.NaN, 121.47), "MX-7", APPOINTMENT));
        assertThrows(IllegalArgumentException.class, () -> service.findNearestFive(
                new GeoPoint(91, 121.47), "MX-7", APPOINTMENT));
        assertThrows(IllegalArgumentException.class, () -> service.findNearestFive(
                CUSTOMER, "MX-7", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    }

    @Test
    void otherModelHasNoSimulatedAvailability() throws Exception {
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(
                Path.of("data/stores/simulated-v1.json"), CLOCK);
        NearbyStoreAvailabilityService service = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), catalog, CLOCK);

        List<NearbyStoreAvailabilityService.Option> result =
                service.findNearestFive(CUSTOMER, "MX-70", APPOINTMENT);

        assertTrue(result.stream().noneMatch(NearbyStoreAvailabilityService.Option::selectable));
        assertTrue(result.stream().allMatch(option -> option.availability()
                == NearbyStoreAvailabilityService.Availability.UNKNOWN));
    }

    @Test
    void snapshotIdChangesWhenSimulatedAvailabilityChanges(@TempDir Path temp) throws Exception {
        Path originalFile = Path.of("data/stores/simulated-v1.json");
        SimulatedStoreCatalog before = SimulatedStoreCatalog.load(originalFile, CLOCK);
        Path changedFile = temp.resolve("changed.json");
        Files.writeString(changedFile, Files.readString(originalFile)
                .replace("\"slot\": \"AVAILABLE\"", "\"slot\": \"FULL\""));
        SimulatedStoreCatalog after = SimulatedStoreCatalog.load(changedFile, CLOCK);

        String previousId = before.lookup("STORE-C", "MX-7", APPOINTMENT).id();
        String changedId = after.lookup("STORE-C", "MX-7", APPOINTMENT).id();
        assertNotEquals(previousId, changedId);
        assertEquals(NearbyStoreAvailabilityService.Availability.SLOT_FULL,
                after.lookup("STORE-C", "MX-7", APPOINTMENT).availability());
    }

    @Test
    void availableResultWithoutFreshSnapshotCannotBeOffered() throws Exception {
        SimulatedStoreCatalog catalog = SimulatedStoreCatalog.load(
                Path.of("data/stores/simulated-v1.json"), CLOCK);
        NearbyStoreAvailabilityService.AvailabilityLookup incomplete = (storeId, modelCode, at) ->
                new NearbyStoreAvailabilityService.Snapshot(
                        NearbyStoreAvailabilityService.Availability.AVAILABLE,
                        null, NOW.minusSeconds(3600));
        NearbyStoreAvailabilityService service = new NearbyStoreAvailabilityService(
                new NearbyStoreSelector(), catalog.stores(), incomplete, CLOCK);

        List<NearbyStoreAvailabilityService.Option> result =
                service.findNearestFive(CUSTOMER, "MX-7", APPOINTMENT);

        assertTrue(result.stream().noneMatch(NearbyStoreAvailabilityService.Option::selectable));
        assertTrue(result.stream().allMatch(option -> option.availability()
                == NearbyStoreAvailabilityService.Availability.UNKNOWN));
    }
}
