package com.zz.agent.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 从仓库结构化模拟数据读取门店与指定时段的状态，不连接真实车源系统。 */
public final class SimulatedStoreCatalog implements NearbyStoreAvailabilityService.AvailabilityLookup {

    private record Status(String vehicle, String slot) {
    }

    private final List<Store> stores;
    private final Map<String, Status> statusByStore;
    private final String modelCode;
    private final OffsetDateTime appointmentAt;
    private final String version;
    private final String contentHash;
    private final Clock clock;

    private SimulatedStoreCatalog(List<Store> stores, Map<String, Status> statusByStore,
                                  String modelCode, OffsetDateTime appointmentAt,
                                  String version, String contentHash, Clock clock) {
        this.stores = List.copyOf(stores);
        this.statusByStore = Map.copyOf(statusByStore);
        this.modelCode = modelCode;
        this.appointmentAt = appointmentAt;
        this.version = version;
        this.contentHash = contentHash;
        this.clock = clock;
    }

    public static SimulatedStoreCatalog load(Path file, Clock clock) throws IOException {
        Objects.requireNonNull(file, "模拟门店文件不能为空");
        Objects.requireNonNull(clock, "时钟不能为空");
        byte[] source = Files.readAllBytes(file);
        JsonNode root = new ObjectMapper().readTree(source);
        if (root == null || !root.path("simulated").asBoolean(false)
                || !root.path("stores").isArray()) {
            throw new IllegalArgumentException("门店数据必须明确标为模拟且包含门店列表");
        }
        String version = requiredText(root, "version");
        String modelCode = requiredText(root, "modelCode");
        OffsetDateTime appointmentAt = OffsetDateTime.parse(requiredText(root, "appointmentAt"));
        List<Store> stores = new ArrayList<>();
        Map<String, Status> statuses = new HashMap<>();
        for (JsonNode item : root.path("stores")) {
            String id = requiredText(item, "id");
            if (!item.path("latitude").isNumber() || !item.path("longitude").isNumber()
                    || !item.path("open").isBoolean() || !item.path("supportsTestDrive").isBoolean()
                    || statuses.putIfAbsent(id, new Status(requiredText(item, "vehicle"),
                    requiredText(item, "slot"))) != null) {
                throw new IllegalArgumentException("模拟门店数据格式错误或编号重复");
            }
            GeoPoint location = new GeoPoint(item.path("latitude").doubleValue(),
                    item.path("longitude").doubleValue());
            if (!Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude())
                    || Math.abs(location.latitude()) > 90 || Math.abs(location.longitude()) > 180) {
                throw new IllegalArgumentException("模拟门店坐标不合法");
            }
            stores.add(new Store(id, location, item.path("open").booleanValue(),
                    item.path("supportsTestDrive").booleanValue()));
        }
        return new SimulatedStoreCatalog(stores, statuses, modelCode, appointmentAt,
                version, sha256(source), clock);
    }

    public List<Store> stores() {
        return stores;
    }

    @Override
    public NearbyStoreAvailabilityService.Snapshot lookup(String storeId, String modelCode,
                                                            OffsetDateTime requestedAt) {
        Status status = statusByStore.get(storeId);
        NearbyStoreAvailabilityService.Availability availability;
        if (status == null || !this.modelCode.equals(modelCode) || requestedAt == null
                || !appointmentAt.toInstant().equals(requestedAt.toInstant())) {
            availability = NearbyStoreAvailabilityService.Availability.UNKNOWN;
        } else if ("UNAVAILABLE".equals(status.vehicle())) {
            availability = NearbyStoreAvailabilityService.Availability.NO_VEHICLE;
        } else if ("FULL".equals(status.slot())) {
            availability = NearbyStoreAvailabilityService.Availability.SLOT_FULL;
        } else if ("AVAILABLE".equals(status.vehicle()) && "AVAILABLE".equals(status.slot())) {
            availability = NearbyStoreAvailabilityService.Availability.AVAILABLE;
        } else {
            availability = NearbyStoreAvailabilityService.Availability.UNKNOWN;
        }
        return new NearbyStoreAvailabilityService.Snapshot(availability,
                version + ":" + contentHash + ":" + storeId + ":" + this.modelCode
                        + ":" + appointmentAt.toInstant(),
                clock.instant());
    }

    private static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("运行环境不支持 SHA-256", unavailable);
        }
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("模拟门店字段缺失：" + key);
        }
        return value.textValue();
    }
}
