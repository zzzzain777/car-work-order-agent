package com.zz.agent.workflow;

public enum TaskState {
    NEEDS_INPUT,
    NEEDS_REVIEW,
    READY_FOR_ADMIN_REVIEW,
    AWAITING_CONFIRMATION,
    REVALIDATING,
    REJECTED,
    STALE_PROPOSAL
}
