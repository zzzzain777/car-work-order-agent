package com.zz.agent.store;

import java.util.List;
import java.util.Comparator;
import java.util.Objects;

public class NearbyStoreSelector {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    public List<NearbyStore> findNearestFive(GeoPoint customer, List<Store> stores) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(stores, "stores");

        return stores.stream()
                .filter(Store::open)
                .filter(Store::supportsTestDrive)
                .map(store -> new NearbyStore(store, distanceKm(customer, store.location())))
                .sorted(Comparator.comparingDouble(NearbyStore::distanceKm)
                        .thenComparing(nearby -> nearby.store().id()))
                .limit(5)
                .toList();
    }

    private static double distanceKm(GeoPoint from, GeoPoint to) {
        double fromLatitude = Math.toRadians(from.latitude());
        double toLatitude = Math.toRadians(to.latitude());
        double latitudeDifference = toLatitude - fromLatitude;
        double longitudeDifference = Math.toRadians(to.longitude() - from.longitude());

        double a = Math.pow(Math.sin(latitudeDifference / 2), 2)
                + Math.cos(fromLatitude) * Math.cos(toLatitude)
                * Math.pow(Math.sin(longitudeDifference / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
