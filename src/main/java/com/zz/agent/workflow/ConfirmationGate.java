package com.zz.agent.workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * Pure confirmation check. A REVALIDATING decision only permits fresh, read-only checks.
 * The caller must atomically claim the transition and handle repeated requests before
 * any downstream assignment call; this class does not persist state or call that API.
 */
public class ConfirmationGate {

    public ConfirmationDecision decide(ConfirmationTask task, ConfirmationCommand command, Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(now, "now");

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
