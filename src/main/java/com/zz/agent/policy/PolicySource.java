package com.zz.agent.policy;

import java.time.OffsetDateTime;

public record PolicySource(
        String policyId,
        int version,
        String title,
        boolean simulated,
        String storeId,
        String modelCode,
        String ruleType,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        String status,
        String sourceRef,
        String body
) {
}
