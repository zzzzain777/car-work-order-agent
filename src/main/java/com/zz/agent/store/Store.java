package com.zz.agent.store;

public record Store(String id, GeoPoint location, boolean open, boolean supportsTestDrive) {
}
