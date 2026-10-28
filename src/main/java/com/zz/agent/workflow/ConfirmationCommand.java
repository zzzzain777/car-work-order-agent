package com.zz.agent.workflow;

public record ConfirmationCommand(
        String proposalId,
        long version,
        boolean approve
) {
}
