package com.zz.agent.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfirmationGateTest {

    private static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    private final ConfirmationGate gate = new ConfirmationGate();

    @Test
    void validApprovalStartsRevalidation() {
        ConfirmationDecision result = gate.decide(waitingTask(NOW.plusSeconds(600)), approve(7), NOW);

        assertEquals(ConfirmationDecision.REVALIDATING, result);
    }

    @Test
    void validRejectionEndsTheProposal() {
        ConfirmationCommand reject = new ConfirmationCommand("proposal-1", 7, false);

        ConfirmationDecision result = gate.decide(waitingTask(NOW.plusSeconds(600)), reject, NOW);

        assertEquals(ConfirmationDecision.REJECTED, result);
    }

    @Test
    void olderTaskVersionCannotConfirmCurrentProposal() {
        ConfirmationDecision result = gate.decide(waitingTask(NOW.plusSeconds(600)), approve(6), NOW);

        assertEquals(ConfirmationDecision.STALE_PROPOSAL, result);
    }

    @Test
    void differentProposalCannotBeConfirmed() {
        ConfirmationCommand otherProposal = new ConfirmationCommand("proposal-2", 7, true);

        ConfirmationDecision result = gate.decide(waitingTask(NOW.plusSeconds(600)), otherProposal, NOW);

        assertEquals(ConfirmationDecision.STALE_PROPOSAL, result);
    }

    @Test
    void proposalExpiresAtItsDeadline() {
        ConfirmationDecision result = gate.decide(waitingTask(NOW), approve(7), NOW);

        assertEquals(ConfirmationDecision.STALE_PROPOSAL, result);
    }

    @Test
    void taskAlreadyPastConfirmationCannotStartRevalidationAgain() {
        ConfirmationTask task = new ConfirmationTask(
                TaskState.REVALIDATING, 7, "proposal-1", NOW.plusSeconds(600));

        ConfirmationDecision result = gate.decide(task, approve(7), NOW);

        assertEquals(ConfirmationDecision.STALE_PROPOSAL, result);
    }

    @Test
    void missingProposalIdCannotBeConfirmed() {
        ConfirmationTask task = new ConfirmationTask(
                TaskState.AWAITING_CONFIRMATION, 7, null, NOW.plusSeconds(600));
        ConfirmationCommand command = new ConfirmationCommand(null, 7, true);

        ConfirmationDecision result = gate.decide(task, command, NOW);

        assertEquals(ConfirmationDecision.STALE_PROPOSAL, result);
    }

    @Test
    void blankProposalIdCannotBeConfirmed() {
        ConfirmationTask task = new ConfirmationTask(
                TaskState.AWAITING_CONFIRMATION, 7, " ", NOW.plusSeconds(600));
        ConfirmationCommand command = new ConfirmationCommand(" ", 7, true);

        ConfirmationDecision result = gate.decide(task, command, NOW);

        assertEquals(ConfirmationDecision.STALE_PROPOSAL, result);
    }

    @Test
    void missingExpiryCannotBeConfirmed() {
        ConfirmationDecision result = gate.decide(waitingTask(null), approve(7), NOW);

        assertEquals(ConfirmationDecision.STALE_PROPOSAL, result);
    }

    private static ConfirmationTask waitingTask(Instant expiresAt) {
        return new ConfirmationTask(TaskState.AWAITING_CONFIRMATION, 7, "proposal-1", expiresAt);
    }

    private static ConfirmationCommand approve(long version) {
        return new ConfirmationCommand("proposal-1", version, true);
    }
}
