package com.zz.agent.workflow;

import java.time.Instant;

public record ConfirmationTask(
        TaskState state,
        long version,
        String proposalId,
        Instant expiresAt
) {
}
