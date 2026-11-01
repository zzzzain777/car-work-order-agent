package com.zz.agent.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

/** 提案与一次性尝试落在 Agent 自有数据库，状态占用和记录写入共用事务。 */
public final class JdbcConfirmationStore {

    public record Proposal(UUID id, UUID taskId, long taskVersion, long advisorId,
                           String evidenceDigest, Instant expiresAt) {
    }

    public record Attempt(UUID id, UUID proposalId, TaskState state, String observation) {
    }

    private record Recoverable(Proposal proposal, long adminId, Attempt attempt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowMapper<Proposal> proposalMapper = this::mapProposal;
    private final RowMapper<Attempt> attemptMapper = this::mapAttempt;

    public JdbcConfirmationStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "数据库连接不能为空");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException("确认记录需要事务型数据源");
        }
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public void initializeSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_proposal (
                    proposal_id uuid PRIMARY KEY,
                    task_id uuid NOT NULL REFERENCES agent_task(task_id),
                    task_version bigint NOT NULL,
                    advisor_id bigint NOT NULL CHECK (advisor_id > 0),
                    evidence_digest varchar(64) NOT NULL,
                    expires_at timestamptz NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    UNIQUE (task_id, task_version)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_assignment_attempt (
                    attempt_id uuid PRIMARY KEY,
                    proposal_id uuid NOT NULL UNIQUE REFERENCES agent_proposal(proposal_id),
                    state varchar(40) NOT NULL,
                    observation varchar(40),
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
    }

    public Optional<Proposal> publish(JdbcAgentTaskStore.Task task, long advisorId,
                                      String evidenceDigest, Instant expiresAt) {
        Objects.requireNonNull(task, "任务不能为空");
        Objects.requireNonNull(expiresAt, "有效期不能为空");
        if (evidenceDigest == null || !evidenceDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("政策证据摘要不合法");
        }
        return transaction.execute(status -> {
            var versions = jdbc.query("""
                    UPDATE agent_task SET state = 'AWAITING_CONFIRMATION', version = version + 1,
                      updated_at = now()
                    WHERE task_id = ? AND admin_id = ? AND version = ?
                      AND state = 'READY_FOR_ADMIN_REVIEW' AND preview_json IS NOT NULL
                    RETURNING version
                    """, (rs, row) -> rs.getLong(1), task.id(), task.adminId(), task.version());
            if (versions.isEmpty()) {
                return Optional.<Proposal>empty();
            }
            Proposal proposal = new Proposal(UUID.randomUUID(), task.id(), versions.getFirst(), advisorId,
                    evidenceDigest, expiresAt);
            jdbc.update("""
                    INSERT INTO agent_proposal (proposal_id, task_id, task_version, advisor_id,
                                                evidence_digest, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, proposal.id(), proposal.taskId(), proposal.taskVersion(), proposal.advisorId(),
                    proposal.evidenceDigest(), Timestamp.from(proposal.expiresAt()));
            return Optional.of(proposal);
        });
    }

    public Optional<Proposal> findProposal(UUID taskId, long adminId, UUID proposalId) {
        return jdbc.query("""
                SELECT p.proposal_id, p.task_id, p.task_version, p.advisor_id,
                       p.evidence_digest, p.expires_at
                FROM agent_proposal p JOIN agent_task t ON t.task_id = p.task_id
                WHERE p.task_id = ? AND t.admin_id = ? AND p.proposal_id = ?
                """, proposalMapper, taskId, adminId, proposalId).stream().findFirst();
    }

    public Optional<Attempt> findAttempt(UUID taskId, long adminId, UUID proposalId) {
        return jdbc.query("""
                SELECT a.attempt_id, a.proposal_id, a.state, a.observation
                FROM agent_assignment_attempt a
                JOIN agent_proposal p ON p.proposal_id = a.proposal_id
                JOIN agent_task t ON t.task_id = p.task_id
                WHERE p.task_id = ? AND t.admin_id = ? AND p.proposal_id = ?
                """, attemptMapper, taskId, adminId, proposalId).stream().findFirst();
    }

    public boolean reject(Proposal proposal, long adminId) {
        return jdbc.update("""
                UPDATE agent_task SET state = 'REJECTED', version = version + 1, updated_at = now()
                WHERE task_id = ? AND admin_id = ? AND version = ? AND state = 'AWAITING_CONFIRMATION'
                """, proposal.taskId(), adminId, proposal.taskVersion()) == 1;
    }

    public boolean expire(Proposal proposal, long adminId) {
        return jdbc.update("""
                UPDATE agent_task SET state = 'STALE_PROPOSAL', version = version + 1, updated_at = now()
                WHERE task_id = ? AND admin_id = ? AND version = ? AND state = 'AWAITING_CONFIRMATION'
                """, proposal.taskId(), adminId, proposal.taskVersion()) == 1;
    }

    public Optional<Attempt> claim(Proposal proposal, long adminId) {
        return transaction.execute(status -> {
            int changed = jdbc.update("""
                    UPDATE agent_task SET state = 'REVALIDATING', version = version + 1, updated_at = now()
                    WHERE task_id = ? AND admin_id = ? AND version = ? AND state = 'AWAITING_CONFIRMATION'
                    """, proposal.taskId(), adminId, proposal.taskVersion());
            if (changed == 0) {
                return Optional.<Attempt>empty();
            }
            Attempt attempt = new Attempt(UUID.randomUUID(), proposal.id(), TaskState.REVALIDATING, null);
            jdbc.update("""
                    INSERT INTO agent_assignment_attempt (attempt_id, proposal_id, state)
                    VALUES (?, ?, 'REVALIDATING')
                    """, attempt.id(), proposal.id());
            return Optional.of(attempt);
        });
    }

    public Optional<Attempt> advance(Proposal proposal, long adminId, UUID attemptId,
                                     TaskState from, TaskState to, String observation) {
        return transaction.execute(status -> {
            int taskChanged = jdbc.update("""
                    UPDATE agent_task SET state = ?, version = version + 1, updated_at = now()
                    WHERE task_id = ? AND admin_id = ? AND state = ?
                    """, to.name(), proposal.taskId(), adminId, from.name());
            if (taskChanged == 0) {
                return Optional.<Attempt>empty();
            }
            var attempts = jdbc.query("""
                    UPDATE agent_assignment_attempt SET state = ?, observation = ?, updated_at = now()
                    WHERE attempt_id = ? AND proposal_id = ? AND state = ?
                    RETURNING attempt_id, proposal_id, state, observation
                    """, attemptMapper, to.name(), observation, attemptId, proposal.id(), from.name());
            if (attempts.isEmpty()) {
                throw new IllegalStateException("任务与执行尝试状态不一致");
            }
            return Optional.of(attempts.getFirst());
        });
    }

    public Optional<Attempt> recordObservation(UUID attemptId, String observation) {
        return jdbc.query("""
                UPDATE agent_assignment_attempt SET observation = ?, updated_at = now()
                WHERE attempt_id = ? AND state = 'OUTCOME_UNKNOWN'
                RETURNING attempt_id, proposal_id, state, observation
                """, attemptMapper, observation, attemptId).stream().findFirst();
    }

    /** 由运维在确认没有活跃调用后执行；未完成的尝试保守归为未知，绝不重发。 */
    public int recoverInterrupted(Instant lastActivityBefore) {
        Objects.requireNonNull(lastActivityBefore, "中断判定时间不能为空");
        List<Recoverable> candidates = jdbc.query("""
                SELECT p.proposal_id, p.task_id, p.task_version, p.advisor_id,
                       p.evidence_digest, p.expires_at,
                       t.admin_id, a.attempt_id, a.state, a.observation
                FROM agent_assignment_attempt a
                JOIN agent_proposal p ON p.proposal_id = a.proposal_id
                JOIN agent_task t ON t.task_id = p.task_id
                WHERE a.state IN ('REVALIDATING', 'EXECUTING') AND t.state = a.state
                  AND a.updated_at < ?
                """, (rs, row) -> new Recoverable(mapProposal(rs, row), rs.getLong("admin_id"),
                        mapAttempt(rs, row)), Timestamp.from(lastActivityBefore));
        int recovered = 0;
        for (Recoverable candidate : candidates) {
            if (advance(candidate.proposal(), candidate.adminId(), candidate.attempt().id(),
                    candidate.attempt().state(), TaskState.OUTCOME_UNKNOWN, "INTERRUPTED").isPresent()) {
                recovered++;
            }
        }
        return recovered;
    }

    private Proposal mapProposal(ResultSet rs, int row) throws SQLException {
        return new Proposal(rs.getObject("proposal_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getLong("task_version"), rs.getLong("advisor_id"), rs.getString("evidence_digest"),
                rs.getTimestamp("expires_at").toInstant());
    }

    private Attempt mapAttempt(ResultSet rs, int row) throws SQLException {
        return new Attempt(rs.getObject("attempt_id", UUID.class), rs.getObject("proposal_id", UUID.class),
                TaskState.valueOf(rs.getString("state")), rs.getString("observation"));
    }
}
