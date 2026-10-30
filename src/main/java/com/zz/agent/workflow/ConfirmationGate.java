package com.zz.agent.workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * 纯规则确认检查。REVALIDATING 只允许重新执行只读核验。
 * 调用方必须在下游分配接口执行前，原子地占用状态流转并处理重复请求；
 * 此类既不持久化任务状态，也不调用分配接口。
 */
public class ConfirmationGate {

    public ConfirmationDecision decide(ConfirmationTask task, ConfirmationCommand command, Instant now) {
        Objects.requireNonNull(task, "任务不能为空");
        Objects.requireNonNull(command, "确认指令不能为空");
        Objects.requireNonNull(now, "当前时间不能为空");

        if (task.state() != TaskState.AWAITING_CONFIRMATION) {
            return ConfirmationDecision.STALE_PROPOSAL;
        }

        if (task.version() != command.version()
                || task.proposalId() == null
                || task.proposalId().isBlank()
                || !task.proposalId().equals(command.proposalId())
                || task.expiresAt() == null
                || !now.isBefore(task.expiresAt())) {
            return ConfirmationDecision.STALE_PROPOSAL;
        }

        return command.approve()
                ? ConfirmationDecision.REVALIDATING
                : ConfirmationDecision.REJECTED;
    }
}
