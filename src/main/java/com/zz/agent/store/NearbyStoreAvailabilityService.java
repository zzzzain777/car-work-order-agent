package com.zz.agent.store;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** 只展示最近五家模拟门店的指定车型、指定时段状态；不执行选店或预约。 */
public final class NearbyStoreAvailabilityService {

    public enum Availability {
        AVAILABLE, NO_VEHICLE, SLOT_FULL, UNKNOWN
    }

    public record Snapshot(Availability availability, String id, Instant observedAt) {
    }

    @FunctionalInterface
    public interface AvailabilityLookup {
        Snapshot lookup(String storeId, String modelCode, OffsetDateTime appointmentAt);
    }

    public record Option(String storeId, double distanceKm, Availability availability,
                         boolean selectable, String snapshotId, Instant observedAt,
                         boolean simulated) {
    }

    private final NearbyStoreSelector selector;
    private final List<Store> stores;
    private final AvailabilityLookup lookup;
    private final Clock clock;

    public NearbyStoreAvailabilityService(NearbyStoreSelector selector, List<Store> stores,
                                          AvailabilityLookup lookup, Clock clock) {
        this.selector = Objects.requireNonNull(selector, "附近门店筛选器不能为空");
        this.stores = List.copyOf(Objects.requireNonNull(stores, "模拟门店不能为空"));
        this.lookup = Objects.requireNonNull(lookup, "模拟可用性查询器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    public List<Option> findNearestFive(GeoPoint customer, String modelCode,
                                        OffsetDateTime appointmentAt) {
        if (customer == null || !Double.isFinite(customer.latitude())
                || !Double.isFinite(customer.longitude())
                || Math.abs(customer.latitude()) > 90 || Math.abs(customer.longitude()) > 180) {
            throw new IllegalArgumentException("请提供有效的客户位置");
        }
        if (modelCode == null || !modelCode.matches("[A-Za-z0-9-]{1,30}")) {
            throw new IllegalArgumentException("请先确认准确的车型编号");
        }
        if (appointmentAt == null || !appointmentAt.toInstant().isAfter(clock.instant())) {
            throw new IllegalArgumentException("请提供未来的准确试驾时间");
        }
        return selector.findNearestFive(customer, stores).stream().map(nearby -> {
            Snapshot snapshot;
            try {
                snapshot = lookup.lookup(nearby.store().id(), modelCode, appointmentAt);
            } catch (RuntimeException failed) {
                snapshot = null;
            }
            Instant now = clock.instant();
            boolean fresh = snapshot != null && snapshot.id() != null && !snapshot.id().isBlank()
                    && snapshot.observedAt() != null && !snapshot.observedAt().isAfter(now)
                    && snapshot.observedAt().isAfter(now.minusSeconds(600));
            Availability availability = !fresh || snapshot.availability() == null
                    ? Availability.UNKNOWN : snapshot.availability();
            Instant observedAt = snapshot == null || snapshot.observedAt() == null
                    ? now : snapshot.observedAt();
            return new Option(nearby.store().id(), nearby.distanceKm(), availability,
                    availability == Availability.AVAILABLE,
                    fresh ? snapshot.id() : null, observedAt, true);
        }).toList();
    }
}
