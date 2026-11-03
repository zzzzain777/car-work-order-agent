package com.zz.agent.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 只保存建议评测所需的数字与来源编号，不保存模型原文、Cookie 或客户资料。 */
public final class JdbcAdviceRunStore {

    public record Run(UUID id, UUID taskId, long taskVersion, String outcome,
                      String promptVersion, String modelId, long promptTokens,
                      long completionTokens, long requestElapsedMs,
                      String citedSourceRef, String riskVerdict, String riskSourceRef,
                      String riskQuote, Instant createdAt) {
    }

    private static final String COLUMNS = "run_id, task_id, task_version, outcome, prompt_version, "
            + "model_id, prompt_tokens, completion_tokens, request_elapsed_ms, "
            + "cited_source_ref, risk_verdict, risk_source_ref, risk_quote, created_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<Run> mapper = this::mapRun;

    public JdbcAdviceRunStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "数据库连接不能为空");
    }

    public void initializeSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_advice_run (
                    run_id uuid PRIMARY KEY,
                    task_id uuid NOT NULL REFERENCES agent_task(task_id),
                    task_version bigint NOT NULL CHECK (task_version >= 0),
                    outcome varchar(30) NOT NULL,
                    prompt_version varchar(40) NOT NULL,
                    model_id text,
                    prompt_tokens bigint NOT NULL CHECK (prompt_tokens >= 0),
                    completion_tokens bigint NOT NULL CHECK (completion_tokens >= 0),
                    request_elapsed_ms bigint NOT NULL CHECK (request_elapsed_ms >= 0),
                    cited_source_ref text,
                    risk_verdict varchar(20),
                    risk_source_ref text,
                    risk_quote text,
                    created_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("ALTER TABLE agent_advice_run ADD COLUMN IF NOT EXISTS risk_verdict varchar(20)");
        jdbc.execute("ALTER TABLE agent_advice_run ADD COLUMN IF NOT EXISTS risk_source_ref text");
        jdbc.execute("ALTER TABLE agent_advice_run ADD COLUMN IF NOT EXISTS risk_quote text");
        jdbc.execute("CREATE INDEX IF NOT EXISTS agent_advice_run_task_time_idx "
                + "ON agent_advice_run (task_id, created_at DESC)");
    }

    /** 任务版本已变化时拒绝写入，避免把旧建议记到新任务上。 */
    public Optional<Run> record(JdbcAgentTaskStore.Task task, StructuredAdviceService.Advice advice,
                                long requestElapsedMs) {
        Objects.requireNonNull(task, "任务不能为空");
        Objects.requireNonNull(advice, "建议不能为空");
        if (task.id() == null || task.adminId() <= 0 || task.version() < 0
                || requestElapsedMs < 0 || advice.promptTokens() < 0 || advice.completionTokens() < 0) {
            throw new IllegalArgumentException("建议运行指标不合法");
        }
        String citedRef = advice.citations().isEmpty() ? null : advice.citations().getFirst().sourceRef();
        PolicyRiskReviewService.Review risk = advice.policyRisk();
        UUID runId = UUID.randomUUID();
        return jdbc.query("""
                INSERT INTO agent_advice_run (run_id, task_id, task_version, outcome,
                    prompt_version, model_id, prompt_tokens, completion_tokens,
                    request_elapsed_ms, cited_source_ref, risk_verdict, risk_source_ref, risk_quote)
                SELECT ?, t.task_id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM agent_task t
                WHERE t.task_id = ? AND t.admin_id = ? AND t.version = ?
                RETURNING
                """ + COLUMNS, mapper, runId, task.version(), advice.outcome().name(),
                advice.promptVersion(), advice.modelId(), advice.promptTokens(), advice.completionTokens(),
                requestElapsedMs, citedRef, risk == null ? null : risk.verdict().name(),
                risk == null ? null : risk.sourceRef(), risk == null ? null : risk.quote(),
                task.id(), task.adminId(), task.version()).stream().findFirst();
    }

    public List<Run> recent(UUID taskId, long adminId) {
        if (taskId == null || adminId <= 0) {
            return List.of();
        }
        return jdbc.query("SELECT r.* FROM agent_advice_run r "
                + "JOIN agent_task t ON t.task_id = r.task_id "
                + "WHERE r.task_id = ? AND t.admin_id = ? "
                + "ORDER BY r.created_at DESC, r.run_id DESC LIMIT 20", mapper, taskId, adminId);
    }

    /** 旧提案缺少政策风险记录时，确认端不得继续执行。 */
    public boolean hasApprovedRiskRun(UUID taskId, long adminId, long adviceTaskVersion) {
        if (taskId == null || adminId <= 0 || adviceTaskVersion < 0) {
            return false;
        }
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM agent_advice_run r
                  JOIN agent_task t ON t.task_id = r.task_id
                  WHERE r.task_id = ? AND t.admin_id = ? AND r.task_version = ?
                    AND r.outcome = 'CANDIDATE' AND r.prompt_version = ?
                    AND r.risk_verdict = 'ALLOW' AND r.risk_source_ref = r.cited_source_ref
                    AND length(r.risk_quote) BETWEEN 8 AND 160
                )
                """, Boolean.class, taskId, adminId, adviceTaskVersion,
                StructuredAdviceService.PROMPT_VERSION);
        return Boolean.TRUE.equals(found);
    }

    private Run mapRun(ResultSet rs, int row) throws SQLException {
        return new Run(rs.getObject("run_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getLong("task_version"), rs.getString("outcome"), rs.getString("prompt_version"),
                rs.getString("model_id"), rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"), rs.getLong("request_elapsed_ms"),
                rs.getString("cited_source_ref"), rs.getString("risk_verdict"),
                rs.getString("risk_source_ref"), rs.getString("risk_quote"),
                rs.getTimestamp("created_at").toInstant());
    }
}
