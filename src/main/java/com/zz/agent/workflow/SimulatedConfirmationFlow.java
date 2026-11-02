package com.zz.agent.workflow;

import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.policy.PolicyEvidenceGate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 模拟确认闭环；只读校验可访问业务系统，写操作只走注入的模拟端。 */
public final class SimulatedConfirmationFlow {

    public record Decision(TaskState state, UUID attemptId, String observation) {
    }

    private static final Duration PROPOSAL_TTL = Duration.ofMinutes(10);

    private final CarSystemReadClient carSystem;
    private final ReadOnlyAdviceFlow readOnly;
    private final JdbcAgentTaskStore tasks;
    private final JdbcConfirmationStore confirmations;
    private final SimulatedAssignmentPort simulatedAssignment;
    private final Clock clock;
    private final ConfirmationGate gate = new ConfirmationGate();

    public SimulatedConfirmationFlow(CarSystemReadClient carSystem, ReadOnlyAdviceFlow readOnly,
                                     JdbcAgentTaskStore tasks, JdbcConfirmationStore confirmations,
                                     SimulatedAssignmentPort simulatedAssignment, Clock clock) {
        this.carSystem = Objects.requireNonNull(carSystem, "业务系统只读客户端不能为空");
        this.readOnly = Objects.requireNonNull(readOnly, "只读预览流程不能为空");
        this.tasks = Objects.requireNonNull(tasks, "任务存储不能为空");
        this.confirmations = Objects.requireNonNull(confirmations, "确认记录不能为空");
        this.simulatedAssignment = Objects.requireNonNull(simulatedAssignment, "模拟分配端不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** 只接受本次完整只读证据与程序校验过的候选建议。 */
    public Optional<JdbcConfirmationStore.Proposal> publish(
            String cookieHeader, AgentAdviceOrchestrator.Recommendation recommendation) {
        long adminId = requireAdmin(cookieHeader);
        if (!publishable(recommendation) || recommendation.task().adminId() != adminId) {
            return Optional.empty();
        }
        var evidenceAge = Duration.between(recommendation.preview().observedAt(), clock.instant());
        if (evidenceAge.isNegative() || evidenceAge.compareTo(PROPOSAL_TTL) >= 0) {
            return Optional.empty();
        }
        JdbcAgentTaskStore.Task current = tasks.find(recommendation.task().id(), adminId).orElse(null);
        if (current == null || current.version() != recommendation.task().version()
                || !Objects.equals(current.preview(), recommendation.task().preview())) {
            return Optional.empty();
        }
        return confirmations.publish(recommendation.task(), recommendation.advice().advisorId(),
                evidenceDigest(recommendation.preview().policyEvidence()), clock.instant().plus(PROPOSAL_TTL));
    }

    /** 页面刷新后只恢复本人、当前版本且尚未过期的模拟提案。 */
    public Optional<JdbcConfirmationStore.Proposal> pending(String cookieHeader, UUID taskId) {
        long adminId = requireAdmin(cookieHeader);
        if (taskId == null) {
            return Optional.empty();
        }
        return confirmations.findPendingProposal(taskId, adminId)
                .filter(proposal -> clock.instant().isBefore(proposal.expiresAt()));
    }

    public Decision confirm(String cookieHeader, UUID taskId, ConfirmationCommand command) {
        long adminId = requireAdmin(cookieHeader);
        if (taskId == null || command == null || command.version() < 0) {
            return stale();
        }
        UUID proposalId;
        try {
            proposalId = UUID.fromString(command.proposalId());
        } catch (RuntimeException invalid) {
            return stale();
        }
        JdbcConfirmationStore.Proposal proposal = confirmations.findProposal(taskId, adminId, proposalId)
                .orElse(null);
        if (proposal == null || proposal.taskVersion() != command.version()) {
            return stale();
        }
        Optional<JdbcConfirmationStore.Attempt> prior = confirmations.findAttempt(taskId, adminId, proposalId);
        if (prior.isPresent()) {
            return decision(prior.get());
        }
        JdbcAgentTaskStore.Task task = tasks.find(taskId, adminId).orElse(null);
        if (task != null && task.state() == TaskState.REJECTED && !command.approve()
                && task.version() == proposal.taskVersion() + 1) {
            return new Decision(TaskState.REJECTED, null, null);
        }
        if (task != null && task.state() == TaskState.AWAITING_CONFIRMATION
                && task.version() == proposal.taskVersion() && !clock.instant().isBefore(proposal.expiresAt())) {
            confirmations.expire(proposal, adminId);
            return stale();
        }
        if (task == null || gate.decide(new ConfirmationTask(task.state(), task.version(),
                proposalId.toString(), proposal.expiresAt()), command, clock.instant())
                == ConfirmationDecision.STALE_PROPOSAL) {
            return stale();
        }
        if (!command.approve()) {
            return confirmations.reject(proposal, adminId)
                    ? new Decision(TaskState.REJECTED, null, null) : stale();
        }
        JdbcConfirmationStore.Attempt attempt = confirmations.claim(proposal, adminId).orElse(null);
        if (attempt == null) {
            return confirmations.findAttempt(taskId, adminId, proposalId).map(SimulatedConfirmationFlow::decision)
                    .orElseGet(SimulatedConfirmationFlow::stale);
        }
        if (!revalidate(cookieHeader, adminId, task, proposal)) {
            return finish(proposal, adminId, attempt.id(), TaskState.REVALIDATING, TaskState.STALE_PROPOSAL, null);
        }
        Decision executing = finish(proposal, adminId, attempt.id(), TaskState.REVALIDATING,
                TaskState.EXECUTING, null);
        if (executing.state() != TaskState.EXECUTING) {
            return executing;
        }
        SimulatedAssignmentPort.Result result;
        try {
            result = simulatedAssignment.assign(cookieHeader, task.workOrderId(), proposal.advisorId());
        } catch (RuntimeException uncertain) {
            return unknownAfterObservation(cookieHeader, proposal, adminId, attempt.id(), task);
        }
        String observation = observe(cookieHeader, task, proposal.advisorId());
        if (result == SimulatedAssignmentPort.Result.ACCEPTED && "TARGET_ASSIGNED".equals(observation)) {
            return finish(proposal, adminId, attempt.id(), TaskState.EXECUTING,
                    TaskState.SUCCEEDED, observation);
        }
        if (result == SimulatedAssignmentPort.Result.REJECTED && "UNCHANGED".equals(observation)) {
            return finish(proposal, adminId, attempt.id(), TaskState.EXECUTING,
                    TaskState.DEFINITELY_FAILED, observation);
        }
        return finish(proposal, adminId, attempt.id(), TaskState.EXECUTING,
                TaskState.OUTCOME_UNKNOWN, observation);
    }

    private boolean revalidate(String cookieHeader, long adminId, JdbcAgentTaskStore.Task task,
                               JdbcConfirmationStore.Proposal proposal) {
        try {
            if (requireAdmin(cookieHeader) != adminId || !sameSnapshot(task,
                    carSystem.getWorkOrder(cookieHeader, task.workOrderId()))) {
                return false;
            }
            ReadOnlyAdviceFlow.Result current = readOnly.preview(task.workOrderId(), cookieHeader,
                    task.supplement());
            List<String> refs = current.policyEvidence().stream().map(e -> e.sourceRef()).toList();
            if (current.stage() != ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW
                    || current.assignmentEnabled() || task.preview() == null
                    || !task.preview().policySourceRefs().equals(refs)
                    || !proposal.evidenceDigest().equals(evidenceDigest(current.policyEvidence()))
                    || current.advisors().stream().noneMatch(advisor -> advisor.advisorId() == proposal.advisorId())) {
                return false;
            }
            return clock.instant().isBefore(proposal.expiresAt())
                    && requireAdmin(cookieHeader) == adminId && sameSnapshot(task,
                    carSystem.getWorkOrder(cookieHeader, task.workOrderId()));
        } catch (CarSystemReadClient.ReadException | SecurityException error) {
            return false;
        }
    }

    private Decision unknownAfterObservation(String cookieHeader, JdbcConfirmationStore.Proposal proposal,
                                             long adminId, UUID attemptId, JdbcAgentTaskStore.Task task) {
        Decision unknown = finish(proposal, adminId, attemptId, TaskState.EXECUTING,
                TaskState.OUTCOME_UNKNOWN, null);
        String observation = observe(cookieHeader, task, proposal.advisorId());
        return confirmations.recordObservation(attemptId, observation)
                .map(SimulatedConfirmationFlow::decision).orElse(unknown);
    }

    private String observe(String cookieHeader, JdbcAgentTaskStore.Task task, long advisorId) {
        try {
            CarSystemReadClient.WorkOrder current = carSystem.getWorkOrder(cookieHeader, task.workOrderId());
            if (current == null || current.id() != task.workOrderId()) {
                return "READ_FAILED";
            }
            if (Objects.equals(current.advisorId(), advisorId) && current.status() != 0) {
                return "TARGET_ASSIGNED";
            }
            return sameSnapshot(task, current) ? "UNCHANGED" : "CHANGED";
        } catch (RuntimeException error) {
            return "READ_FAILED";
        }
    }

    private Decision finish(JdbcConfirmationStore.Proposal proposal, long adminId, UUID attemptId,
                            TaskState from, TaskState to, String observation) {
        return confirmations.advance(proposal, adminId, attemptId, from, to, observation)
                .map(SimulatedConfirmationFlow::decision)
                .orElseGet(() -> confirmations.findAttempt(proposal.taskId(), adminId, proposal.id())
                        .map(SimulatedConfirmationFlow::decision).orElseGet(SimulatedConfirmationFlow::stale));
    }

    private long requireAdmin(String cookieHeader) {
        CarSystemReadClient.Identity identity;
        try {
            identity = carSystem.currentUser(cookieHeader);
        } catch (CarSystemReadClient.ReadException error) {
            throw new SecurityException("管理员身份校验失败", error);
        }
        if (identity == null || identity.id() <= 0 || identity.userRole() != 2 || identity.userStatus() != 0) {
            throw new SecurityException("需要有效的管理员登录态");
        }
        return identity.id();
    }

    private static boolean publishable(AgentAdviceOrchestrator.Recommendation recommendation) {
        if (recommendation == null || recommendation.task() == null || recommendation.preview() == null
                || recommendation.advice() == null) {
            return false;
        }
        JdbcAgentTaskStore.Task task = recommendation.task();
        ReadOnlyAdviceFlow.Result preview = recommendation.preview();
        StructuredAdviceService.Advice advice = recommendation.advice();
        if (task.state() != TaskState.READY_FOR_ADMIN_REVIEW || task.preview() == null
                || preview.stage() != ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW
                || preview.assignmentEnabled() || preview.workOrderId() != task.workOrderId()
                || !Objects.equals(preview.observedAt(), task.preview().observedAt())
                || advice.outcome() != StructuredAdviceService.Outcome.CANDIDATE
                || advice.advisorId() == null || advice.advisorId() <= 0 || advice.citations().isEmpty()
                || !StructuredAdviceService.PROMPT_VERSION.equals(advice.promptVersion())) {
            return false;
        }
        List<String> refs = preview.policyEvidence().stream().map(e -> e.sourceRef()).toList();
        return task.preview().policySourceRefs().equals(refs)
                && task.preview().advisors().stream().anyMatch(advisor -> advisor.advisorId() == advice.advisorId()
                        && advisor.processing() == advice.processing() && advisor.total() == advice.total())
                && preview.advisors().stream().anyMatch(advisor -> advisor.advisorId() == advice.advisorId()
                        && advisor.processing() == advice.processing() && advisor.total() == advice.total())
                && advice.citations().stream().allMatch(citation -> preview.policyEvidence().stream()
                        .anyMatch(evidence -> Objects.equals(evidence.sourceRef(), citation.sourceRef())
                                && citation.quote() != null && citation.quote().length() >= 8
                                && evidence.text() != null && evidence.text().contains(citation.quote())));
    }

    private static boolean sameSnapshot(JdbcAgentTaskStore.Task task, CarSystemReadClient.WorkOrder order) {
        return order != null && order.id() == task.workOrderId() && order.type() == task.orderType()
                && order.status() == task.orderStatus() && Objects.equals(order.advisorId(), task.orderAdvisorId())
                && Objects.equals(order.updateTime(), task.orderUpdatedAt());
    }

    private static String evidenceDigest(List<PolicyEvidenceGate.Evidence> evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (PolicyEvidenceGate.Evidence item : evidence) {
                String canonical = item.policyId() + "\u0000" + item.version() + "\u0000"
                        + item.sourceRef() + "\u0000" + item.text() + "\u0000";
                digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前运行环境缺少 SHA-256", error);
        }
    }

    private static Decision stale() {
        return new Decision(TaskState.STALE_PROPOSAL, null, null);
    }

    private static Decision decision(JdbcConfirmationStore.Attempt attempt) {
        return new Decision(attempt.state(), attempt.id(), attempt.observation());
    }
}
