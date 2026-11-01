package com.zz.agent.workflow;

import com.zz.agent.integration.CarSystemReadClient;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 将只读工单预览接到 Agent 自有任务；此类不提供分配能力。 */
public final class AgentTaskWorkflow {

    public record Refresh(JdbcAgentTaskStore.Task task, ReadOnlyAdviceFlow.Result result) {
    }

    private final CarSystemReadClient carSystem;
    private final ReadOnlyAdviceFlow advice;
    private final JdbcAgentTaskStore tasks;
    private final Clock clock;

    public AgentTaskWorkflow(CarSystemReadClient carSystem, ReadOnlyAdviceFlow advice,
                             JdbcAgentTaskStore tasks, Clock clock) {
        this.carSystem = Objects.requireNonNull(carSystem, "业务系统只读客户端不能为空");
        this.advice = Objects.requireNonNull(advice, "预览流程不能为空");
        this.tasks = Objects.requireNonNull(tasks, "任务存储不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    public JdbcAgentTaskStore.Task open(String cookieHeader, long workOrderId) {
        long adminId = requireAdmin(cookieHeader);
        if (workOrderId <= 0) {
            throw new IllegalArgumentException("工单编号必须大于零");
        }
        CarSystemReadClient.WorkOrder order;
        try {
            order = carSystem.getWorkOrder(cookieHeader, workOrderId);
        } catch (CarSystemReadClient.ReadException error) {
            throw translateAuth(error);
        }
        if (order == null || order.id() != workOrderId) {
            throw new IllegalArgumentException("业务系统返回的工单与请求不一致");
        }
        return tasks.create(adminId, order);
    }

    public Optional<JdbcAgentTaskStore.Task> find(String cookieHeader, UUID taskId) {
        return tasks.find(taskId, requireAdmin(cookieHeader));
    }

    public Optional<JdbcAgentTaskStore.Task> supplement(String cookieHeader, UUID taskId,
                                                         long expectedVersion,
                                                         ReadOnlyAdviceFlow.Supplement supplement) {
        return tasks.updateSupplement(taskId, requireAdmin(cookieHeader), expectedVersion, supplement);
    }

    public Optional<Refresh> refresh(String cookieHeader, UUID taskId, long expectedVersion) {
        long adminId = requireAdmin(cookieHeader);
        Optional<JdbcAgentTaskStore.Task> found = tasks.find(taskId, adminId);
        if (found.isEmpty() || found.get().version() != expectedVersion) {
            return Optional.empty();
        }
        JdbcAgentTaskStore.Task task = found.get();
        ReadOnlyAdviceFlow.Result result;
        try {
            CarSystemReadClient.WorkOrder current = carSystem.getWorkOrder(cookieHeader, task.workOrderId());
            if (snapshotChanged(task, current)) {
                result = reviewResult(task.workOrderId(), ReadOnlyAdviceFlow.Stage.NOT_APPLICABLE,
                        "工单状态或更新时间已变化，请重新建任务");
            } else {
                result = advice.preview(task.workOrderId(), cookieHeader, task.supplement());
            }
        } catch (CarSystemReadClient.ReadException error) {
            if (error.kind() == CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED) {
                throw new SecurityException("管理员登录态无效或权限不足", error);
            }
            result = reviewResult(task.workOrderId(), ReadOnlyAdviceFlow.Stage.NEEDS_REVIEW,
                    "工单重新读取失败，需人工核对");
        }
        if (result.stage() == ReadOnlyAdviceFlow.Stage.UNAUTHORIZED
                || requireAdmin(cookieHeader) != adminId) {
            throw new SecurityException("刷新预览期间管理员身份失效");
        }
        ReadOnlyAdviceFlow.Result finalResult = result;
        return tasks.recordPreview(taskId, adminId, expectedVersion, result)
                .map(saved -> new Refresh(saved, finalResult));
    }

    private long requireAdmin(String cookieHeader) {
        CarSystemReadClient.Identity identity;
        try {
            identity = carSystem.currentUser(cookieHeader);
        } catch (CarSystemReadClient.ReadException error) {
            throw translateAuth(error);
        }
        if (identity == null || identity.id() <= 0 || identity.userRole() != 2 || identity.userStatus() != 0) {
            throw new SecurityException("需要有效的管理员登录态");
        }
        return identity.id();
    }

    private static RuntimeException translateAuth(CarSystemReadClient.ReadException error) {
        return error.kind() == CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED
                ? new SecurityException("管理员登录态无效或权限不足", error) : error;
    }

    private static boolean snapshotChanged(JdbcAgentTaskStore.Task task, CarSystemReadClient.WorkOrder current) {
        return current == null || current.id() != task.workOrderId()
                || current.type() != task.orderType() || current.status() != task.orderStatus()
                || !Objects.equals(current.advisorId(), task.orderAdvisorId())
                || !Objects.equals(current.updateTime(), task.orderUpdatedAt());
    }

    private ReadOnlyAdviceFlow.Result reviewResult(long workOrderId, ReadOnlyAdviceFlow.Stage stage,
                                                    String message) {
        Instant observedAt = clock.instant();
        return new ReadOnlyAdviceFlow.Result(stage, workOrderId, message,
                List.of(), List.of(), List.of(), observedAt, false);
    }
}
