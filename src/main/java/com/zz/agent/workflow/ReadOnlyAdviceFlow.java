package com.zz.agent.workflow;

import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.policy.PolicyEvidenceGate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 显式编排只读证据；本阶段仅生成预览，不创建可执行的分配任务。 */
public final class ReadOnlyAdviceFlow {

    @FunctionalInterface
    public interface PolicyLookup {
        PolicyEvidenceGate.Decision search(String storeId, String modelCode,
                                           OffsetDateTime appointmentAt, String question) throws IOException;
    }

    public enum Stage {
        UNAUTHORIZED, NOT_APPLICABLE, NEEDS_INPUT, NEEDS_REVIEW, READY_FOR_ADMIN_REVIEW
    }

    public record Supplement(String storeId, String modelCode, OffsetDateTime appointmentAt) {
    }

    public record AdvisorLoad(long advisorId, String name, int processing, int total,
                              boolean storeAffiliationVerified) {
    }

    public record Result(Stage stage, long workOrderId, String message, List<String> missingFields,
                         List<PolicyEvidenceGate.Evidence> policyEvidence, List<AdvisorLoad> advisors,
                         Instant observedAt, boolean assignmentEnabled) {
        public Result {
            missingFields = List.copyOf(missingFields);
            policyEvidence = List.copyOf(policyEvidence);
            advisors = List.copyOf(advisors);
        }
    }

    private final CarSystemReadClient carSystem;
    private final PolicyLookup policies;
    private final Clock clock;

    public ReadOnlyAdviceFlow(CarSystemReadClient carSystem, PolicyLookup policies, Clock clock) {
        this.carSystem = Objects.requireNonNull(carSystem, "业务系统只读客户端不能为空");
        this.policies = Objects.requireNonNull(policies, "政策检索器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    public Result preview(long workOrderId, String cookieHeader, Supplement supplement) {
        if (workOrderId <= 0) {
            throw new IllegalArgumentException("工单编号必须大于零");
        }
        Instant observedAt = clock.instant();
        CarSystemReadClient.Identity identity;
        try {
            identity = carSystem.currentUser(cookieHeader);
        } catch (CarSystemReadClient.ReadException error) {
            return readFailure(workOrderId, observedAt, error, "管理员身份读取失败");
        }
        if (identity == null || identity.id() <= 0 || identity.userRole() != 2 || identity.userStatus() != 0) {
            return result(Stage.UNAUTHORIZED, workOrderId, "需要有效的管理员登录态", observedAt);
        }

        CarSystemReadClient.WorkOrder order;
        try {
            order = carSystem.getWorkOrder(cookieHeader, workOrderId);
        } catch (CarSystemReadClient.ReadException error) {
            return readFailure(workOrderId, observedAt, error, "工单读取失败");
        }
        if (order == null || order.id() != workOrderId || order.type() != 1
                || order.status() != 0 || order.advisorId() != null) {
            return result(Stage.NOT_APPLICABLE, workOrderId, "工单不是待分配的试驾工单", observedAt);
        }

        List<String> missing = missingFields(supplement, observedAt);
        if (!missing.isEmpty()) {
            return new Result(Stage.NEEDS_INPUT, workOrderId, "请确认门店、车型编号和准确试驾时间",
                    missing, List.of(), List.of(), observedAt, false);
        }

        PolicyEvidenceGate.Decision evidence;
        try {
            evidence = policies.search(supplement.storeId(), supplement.modelCode(),
                    supplement.appointmentAt(), "试驾申请时间");
        } catch (IOException | RuntimeException error) {
            return result(Stage.NEEDS_REVIEW, workOrderId, "模拟政策检索失败，需人工核对", observedAt);
        }
        if (evidence == null || evidence.outcome() != PolicyEvidenceGate.Outcome.CANDIDATES
                || evidence.evidence().isEmpty()) {
            List<PolicyEvidenceGate.Evidence> reviewEvidence = evidence == null ? List.of() : evidence.evidence();
            return new Result(Stage.NEEDS_REVIEW, workOrderId, "政策无依据或存在冲突，需人工核对",
                    List.of(), reviewEvidence, List.of(), observedAt, false);
        }

        List<CarSystemReadClient.Advisor> advisors;
        List<CarSystemReadClient.Workload> workloads;
        try {
            advisors = carSystem.listAdvisors(cookieHeader);
            workloads = carSystem.workloads(cookieHeader);
        } catch (CarSystemReadClient.ReadException error) {
            Result failed = readFailure(workOrderId, observedAt, error, "顾问名单或工作量读取失败");
            return new Result(failed.stage(), workOrderId, failed.message(), List.of(),
                    failed.stage() == Stage.UNAUTHORIZED ? List.of() : evidence.evidence(),
                    List.of(), observedAt, false);
        }
        List<AdvisorLoad> loads = mergeWorkloads(advisors, workloads);
        if (loads.isEmpty()) {
            return new Result(Stage.NEEDS_REVIEW, workOrderId, "没有可核对的正常顾问或工作量数据异常",
                    List.of(), evidence.evidence(), List.of(), observedAt, false);
        }
        return new Result(Stage.READY_FOR_ADMIN_REVIEW, workOrderId,
                "模拟政策与顾问工作量已备齐；可优先核对顾问 " + loads.getFirst().advisorId()
                        + " 的门店归属，管理员仍需核对政策原文和实时条件",
                List.of(), evidence.evidence(), loads, observedAt, false);
    }

    private List<String> missingFields(Supplement supplement, Instant now) {
        List<String> missing = new ArrayList<>();
        if (supplement == null || supplement.storeId() == null || supplement.storeId().isBlank()) {
            missing.add("storeId");
        }
        if (supplement == null || supplement.modelCode() == null || supplement.modelCode().isBlank()) {
            missing.add("modelCode");
        }
        if (supplement == null || supplement.appointmentAt() == null
                || !supplement.appointmentAt().toInstant().isAfter(now)) {
            missing.add("appointmentAt");
        }
        return missing;
    }

    private static List<AdvisorLoad> mergeWorkloads(List<CarSystemReadClient.Advisor> advisors,
                                                     List<CarSystemReadClient.Workload> workloads) {
        if (advisors == null || workloads == null) {
            return List.of();
        }
        Map<Long, CarSystemReadClient.Workload> byId = new HashMap<>();
        for (CarSystemReadClient.Workload workload : workloads) {
            if (workload == null || workload.advisorId() <= 0 || workload.processing() < 0
                    || workload.total() < workload.processing()
                    || byId.putIfAbsent(workload.advisorId(), workload) != null) {
                return List.of();
            }
        }
        List<AdvisorLoad> result = new ArrayList<>();
        Set<Long> seenAdvisors = new HashSet<>();
        for (CarSystemReadClient.Advisor advisor : advisors) {
            if (advisor == null || advisor.id() <= 0 || !seenAdvisors.add(advisor.id())) {
                return List.of();
            }
            if (advisor.userRole() != 1 || advisor.userStatus() != 0) {
                continue;
            }
            CarSystemReadClient.Workload workload = byId.get(advisor.id());
            result.add(new AdvisorLoad(advisor.id(), advisor.name(),
                    workload == null ? 0 : workload.processing(), workload == null ? 0 : workload.total(), false));
        }
        result.sort(Comparator.comparingInt(AdvisorLoad::processing).thenComparingLong(AdvisorLoad::advisorId));
        return List.copyOf(result);
    }

    private static Result readFailure(long workOrderId, Instant at, CarSystemReadClient.ReadException error,
                                      String fallbackMessage) {
        if (error.kind() == CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED) {
            return result(Stage.UNAUTHORIZED, workOrderId, "管理员登录态无效或权限不足", at);
        }
        return result(Stage.NEEDS_REVIEW, workOrderId, fallbackMessage, at);
    }

    private static Result result(Stage stage, long workOrderId, String message, Instant at) {
        return new Result(stage, workOrderId, message, List.of(), List.of(), List.of(), at, false);
    }
}
