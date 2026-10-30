package com.zz.agent.policy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** 只用可信的结构化任务字段筛选政策适用范围。 */
public final class PolicyMetadataFilter {

    public enum Outcome {
        NO_MATCH,
        SINGLE_MATCH,
        MULTIPLE_MATCHES
    }

    public record Result(Outcome outcome, List<PolicySource> policies) {
        public Result {
            policies = List.copyOf(policies);
        }
    }

    public Result find(
            List<PolicySource> policies,
            String storeId,
            String modelCode,
            OffsetDateTime appointmentAt
    ) {
        Objects.requireNonNull(policies, "政策列表不能为空");
        Objects.requireNonNull(storeId, "门店编号不能为空");
        Objects.requireNonNull(modelCode, "车型编号不能为空");
        Instant time = Objects.requireNonNull(appointmentAt, "预约时间不能为空").toInstant();

        List<PolicySource> matches = policies.stream()
                .filter(policy -> policy.storeId().equals(storeId))
                .filter(policy -> policy.modelCode().equals(modelCode))
                .filter(policy -> policy.ruleType().equals("TEST_DRIVE"))
                .filter(policy -> policy.status().equals("ACTIVE"))
                .filter(policy -> !time.isBefore(policy.effectiveFrom().toInstant()))
                .filter(policy -> time.isBefore(policy.effectiveTo().toInstant()))
                .toList();

        Outcome outcome = switch (matches.size()) {
            case 0 -> Outcome.NO_MATCH;
            case 1 -> Outcome.SINGLE_MATCH;
            default -> Outcome.MULTIPLE_MATCHES;
        };
        return new Result(outcome, matches);
    }
}
