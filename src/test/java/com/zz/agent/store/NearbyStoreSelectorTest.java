package com.zz.agent.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyStoreSelectorTest {

    private final NearbyStoreSelector selector = new NearbyStoreSelector();

    @Test
    void c02ReturnsTheFiveClosestStoresRegardlessOfInputOrder() {
        GeoPoint customer = new GeoPoint(31.230400, 121.473700);
        List<Store> stores = List.of(
                store("STORE-F", 31.230400, 121.505200),
                store("STORE-D", 31.230400, 121.494700),
                store("STORE-B", 31.230400, 121.484200),
                store("STORE-E", 31.207900, 121.473700),
                store("STORE-C", 31.216900, 121.473700),
                store("STORE-A", 31.234900, 121.473700)
        );

        List<NearbyStore> result = selector.findNearestFive(customer, stores);

        assertEquals(List.of("STORE-A", "STORE-B", "STORE-C", "STORE-D", "STORE-E"),
                result.stream().map(nearby -> nearby.store().id()).toList());
        assertTrue(result.get(0).distanceKm() > 0.45 && result.get(0).distanceKm() < 0.55);
        assertFalse(result.stream().anyMatch(nearby -> nearby.store().id().equals("STORE-F")));
    }

    @Test
    void closedStoresAndStoresWithoutTestDrivesAreExcludedBeforeTakingFive() {
        GeoPoint customer = new GeoPoint(31.230400, 121.473700);
        List<Store> stores = List.of(
                new Store("CLOSED", customer, false, true),
                new Store("NO-TEST-DRIVE", customer, true, false),
                store("OPEN", 31.234900, 121.473700)
        );

        List<NearbyStore> result = selector.findNearestFive(customer, stores);

        assertEquals(List.of("OPEN"), result.stream().map(nearby -> nearby.store().id()).toList());
    }

    @Test
    void storesWithEqualDistanceAreOrderedById() {
        GeoPoint customer = new GeoPoint(31.230400, 121.473700);
        List<Store> stores = List.of(
                new Store("STORE-B", customer, true, true),
                new Store("STORE-A", customer, true, true)
        );

        List<NearbyStore> result = selector.findNearestFive(customer, stores);

        assertEquals(List.of("STORE-A", "STORE-B"),
                result.stream().map(nearby -> nearby.store().id()).toList());
    }

    private static Store store(String id, double latitude, double longitude) {
        return new Store(id, new GeoPoint(latitude, longitude), true, true);
    }
}
