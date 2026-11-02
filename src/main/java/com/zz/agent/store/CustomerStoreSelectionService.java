package com.zz.agent.store;

import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.workflow.JdbcAgentTaskStore;
import com.zz.agent.workflow.TaskState;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 客户仅在模拟五店候选中选店；不写原工单、不预约、不分配。 */
public final class CustomerStoreSelectionService {

    public record CustomerTaskView(UUID taskId, long workOrderId, long version, TaskState state,
                                   String selectedStoreId, String modelCode,
                                   OffsetDateTime appointmentAt, Instant selectedAt) {
    }

    private final CarSystemReadClient carSystem;
    private final JdbcAgentTaskStore tasks;
    private final NearbyStoreAvailabilityService nearby;
    private final NearbyStoreAvailabilityService.AvailabilityLookup lookup;
    private final Clock clock;

    public CustomerStoreSelectionService(CarSystemReadClient carSystem, JdbcAgentTaskStore tasks,
                                         NearbyStoreAvailabilityService nearby,
                                         NearbyStoreAvailabilityService.AvailabilityLookup lookup,
                                         Clock clock) {
        this.carSystem = Objects.requireNonNull(carSystem, "业务系统只读客户端不能为空");
        this.tasks = Objects.requireNonNull(tasks, "任务存储不能为空");
        this.nearby = Objects.requireNonNull(nearby, "附近门店查询不能为空");
        this.lookup = Objects.requireNonNull(lookup, "可用性查询不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    public JdbcAgentTaskStore.StoreOffer offer(String cookieHeader, UUID taskId, long expectedVersion,
                                               GeoPoint location, String modelCode,
                                               OffsetDateTime appointmentAt) {
        long customerId = requireCustomer(cookieHeader);
        JdbcAgentTaskStore.Task task = tasks.findForOwnershipCheck(taskId)
                .orElseThrow(() -> new SecurityException("无权查看此任务"));
        verifyCurrentOrder(cookieHeader, customerId, task);
        if (task.version() != expectedVersion || task.supplement().storeId() != null
                || task.storeChoice() != null
                || task.supplement().modelCode() != null
                && !task.supplement().modelCode().equals(modelCode)
                || task.supplement().appointmentAt() != null
                && (appointmentAt == null || !task.supplement().appointmentAt().toInstant()
                .equals(appointmentAt.toInstant()))) {
            throw new IllegalStateException("任务信息已变化，请重新查看");
        }
        List<NearbyStoreAvailabilityService.Option> options = nearby.findNearestFive(
                location, modelCode, appointmentAt);
        return tasks.createStoreOffer(taskId, customerId, expectedVersion, modelCode,
                appointmentAt, options, clock.instant().plusSeconds(600));
    }

    public CustomerTaskView view(String cookieHeader, UUID taskId) {
        long customerId = requireCustomer(cookieHeader);
        JdbcAgentTaskStore.Task task = tasks.findForOwnershipCheck(taskId)
                .orElseThrow(() -> new SecurityException("无权查看此任务"));
        verifyCurrentOrder(cookieHeader, customerId, task);
        return new CustomerTaskView(task.id(), task.workOrderId(), task.version(), task.state(),
                task.supplement().storeId(), task.supplement().modelCode(),
                task.supplement().appointmentAt(),
                task.storeChoice() == null ? null : task.storeChoice().selectedAt());
    }

    public Optional<JdbcAgentTaskStore.Task> choose(String cookieHeader, UUID offerId, String storeId) {
        long customerId = requireCustomer(cookieHeader);
        JdbcAgentTaskStore.StoreOffer offer = tasks.findStoreOffer(offerId).orElse(null);
        if (offer == null || offer.customerId() != customerId || storeId == null) {
            return Optional.empty();
        }
        JdbcAgentTaskStore.Task task = tasks.findForOwnershipCheck(offer.taskId()).orElse(null);
        if (task == null) {
            return Optional.empty();
        }
        try {
            verifyCurrentOrder(cookieHeader, customerId, task);
        } catch (IllegalStateException changed) {
            return Optional.empty();
        }
        if (task.storeChoice() != null && offerId.equals(task.storeChoice().offerId())
                && customerId == task.storeChoice().customerId()
                && storeId.equals(task.supplement().storeId())) {
            return Optional.of(task);
        }
        if (!clock.instant().isBefore(offer.expiresAt())) {
            return Optional.empty();
        }
        NearbyStoreAvailabilityService.Option option = offer.options().stream()
                .filter(candidate -> storeId.equals(candidate.storeId())).findFirst().orElse(null);
        if (option == null || !option.selectable()
                || option.availability() != NearbyStoreAvailabilityService.Availability.AVAILABLE) {
            return Optional.empty();
        }
        NearbyStoreAvailabilityService.Snapshot current;
        try {
            current = lookup.lookup(storeId, offer.modelCode(), offer.appointmentAt());
        } catch (RuntimeException failed) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (current == null || current.availability() != NearbyStoreAvailabilityService.Availability.AVAILABLE
                || current.id() == null || !current.id().equals(option.snapshotId())
                || current.observedAt() == null || current.observedAt().isAfter(now)
                || !current.observedAt().isAfter(now.minusSeconds(600))) {
            return Optional.empty();
        }
        return tasks.chooseStore(offerId, customerId, storeId, current.id(), now);
    }

    public long requireCustomer(String cookieHeader) {
        CarSystemReadClient.Identity identity;
        try {
            identity = carSystem.currentUser(cookieHeader);
        } catch (CarSystemReadClient.ReadException error) {
            if (error.kind() == CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED) {
                throw new SecurityException("客户会话无效", error);
            }
            throw error;
        }
        if (identity == null || identity.id() <= 0 || identity.userRole() != 0 || identity.userStatus() != 0) {
            throw new SecurityException("需要有效的普通客户会话");
        }
        return identity.id();
    }

    private void verifyCurrentOrder(String cookieHeader, long customerId, JdbcAgentTaskStore.Task task) {
        CarSystemReadClient.WorkOrder order;
        try {
            order = carSystem.getWorkOrder(cookieHeader, task.workOrderId());
        } catch (CarSystemReadClient.ReadException error) {
            if (error.kind() == CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED) {
                throw new SecurityException("客户会话无效或无权读取工单", error);
            }
            throw error;
        }
        if (order == null || order.id() != task.workOrderId()
                || order.customerId() == null || order.customerId() != customerId) {
            throw new SecurityException("当前客户不是此工单的所有人");
        }
        if (order.type() != task.orderType() || order.status() != task.orderStatus()
                || !Objects.equals(order.advisorId(), task.orderAdvisorId())
                || !Objects.equals(order.updateTime(), task.orderUpdatedAt())) {
            throw new IllegalStateException("原工单已变化，请联系管理员重新建任务");
        }
    }
}
