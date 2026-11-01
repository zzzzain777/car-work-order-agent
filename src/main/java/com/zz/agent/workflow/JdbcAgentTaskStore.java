package com.zz.agent.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Agent 自有任务存储；所有读取与更新都受管理员编号和数据库版本约束。 */
public final class JdbcAgentTaskStore {

    public record AdvisorSnapshot(long advisorId, int processing, int total,
                                  boolean storeAffiliationVerified) {
    }

    public record Preview(ReadOnlyAdviceFlow.Stage stage, List<String> policySourceRefs,
                          List<AdvisorSnapshot> advisors, Instant observedAt) {
        public Preview {
            policySourceRefs = List.copyOf(policySourceRefs);
            advisors = List.copyOf(advisors);
        }
    }

    public record Task(UUID id, long adminId, long workOrderId, int orderType, int orderStatus,
                       Long orderAdvisorId, String orderUpdatedAt, long version, TaskState state,
                       ReadOnlyAdviceFlow.Supplement supplement, Preview preview, Instant updatedAt) {
    }

    private static final String COLUMNS = "task_id, admin_id, work_order_id, order_type, order_status, "
            + "order_advisor_id, order_updated_at, version, state, store_id, model_code, "
            + "appointment_at, preview_json, updated_at";
    private static final String EDITABLE_STATES = "('NEEDS_INPUT', 'NEEDS_REVIEW', "
            + "'READY_FOR_ADMIN_REVIEW', 'STALE_PROPOSAL')";
    private static final Pattern POLICY_SOURCE_REF = Pattern.compile(
            "data/policies/[A-Za-z0-9-]+\\.md(?:#rule-[0-9]+)?");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Task> taskMapper = this::mapTask;

    public JdbcAgentTaskStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "数据库连接不能为空");
        this.json = Objects.requireNonNull(json, "JSON 编解码器不能为空");
    }

    /** 仅创建 Agent 自己的表，不触碰 carSystem 数据库。 */
    public void initializeSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task (
                    task_id uuid PRIMARY KEY,
                    admin_id bigint NOT NULL CHECK (admin_id > 0),
                    work_order_id bigint NOT NULL CHECK (work_order_id > 0),
                    order_type integer NOT NULL,
                    order_status integer NOT NULL,
                    order_advisor_id bigint,
                    order_updated_at varchar(64),
                    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
                    state varchar(40) NOT NULL,
                    store_id varchar(100),
                    model_code varchar(100),
                    appointment_at timestamptz,
                    preview_json jsonb,
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
    }

    public Task create(long adminId, CarSystemReadClient.WorkOrder order) {
        if (adminId <= 0 || order == null || order.id() <= 0 || order.type() != 1
                || order.status() != 0 || order.advisorId() != null
                || order.updateTime() != null && order.updateTime().length() > 64) {
            throw new IllegalArgumentException("只能为有效管理员创建待分配试驾工单任务");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_task (task_id, admin_id, work_order_id, order_type,
                  order_status, order_advisor_id, order_updated_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'NEEDS_INPUT')
                """, id, adminId, order.id(), order.type(), order.status(), order.advisorId(), order.updateTime());
        return find(id, adminId).orElseThrow();
    }

    public Optional<Task> find(UUID taskId, long adminId) {
        if (taskId == null || adminId <= 0) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_task WHERE task_id = ? AND admin_id = ?",
                taskMapper, taskId, adminId).stream().findFirst();
    }

    /** 任何补充信息写入都使先前的预览失效。版本不匹配时不覆盖。 */
    public Optional<Task> updateSupplement(UUID taskId, long adminId, long expectedVersion,
                                            ReadOnlyAdviceFlow.Supplement supplement) {
        if (taskId == null || adminId <= 0 || expectedVersion < 0 || supplement == null
                || invalidCode(supplement.storeId()) || invalidCode(supplement.modelCode())) {
            throw new IllegalArgumentException("补充信息或任务版本不合法");
        }
        return jdbc.query("""
                UPDATE agent_task SET store_id = ?, model_code = ?, appointment_at = ?,
                  preview_json = NULL, state = 'NEEDS_INPUT', version = version + 1,
                  updated_at = now()
                WHERE task_id = ? AND admin_id = ? AND version = ? AND state IN %s
                RETURNING %s
                """.formatted(EDITABLE_STATES, COLUMNS), taskMapper,
                supplement.storeId(), supplement.modelCode(), supplement.appointmentAt(),
                taskId, adminId, expectedVersion).stream().findFirst();
    }

    /** 只持久化证据引用和顾问工作量；未经授权或具备执行能力的结果不能入库。 */
    public Optional<Task> recordPreview(UUID taskId, long adminId, long expectedVersion,
                                         ReadOnlyAdviceFlow.Result result) {
        if (result == null || result.assignmentEnabled() || result.stage() == ReadOnlyAdviceFlow.Stage.UNAUTHORIZED) {
            throw new IllegalArgumentException("不能保存未经授权或可执行分配的预览");
        }
        Optional<Task> existing = find(taskId, adminId);
        if (existing.isEmpty() || existing.get().version() != expectedVersion) {
            return Optional.empty();
        }
        if (result.workOrderId() != existing.get().workOrderId()) {
            throw new IllegalArgumentException("预览工单与任务不一致");
        }
        if (result.policyEvidence().stream().anyMatch(evidence -> evidence.sourceRef() == null
                || !POLICY_SOURCE_REF.matcher(evidence.sourceRef()).matches())) {
            throw new IllegalArgumentException("只能保存本地模拟政策的来源引用");
        }
        if (result.stage() == ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW
                && (result.policyEvidence().isEmpty() || result.advisors().isEmpty())) {
            throw new IllegalArgumentException("待人工核对的预览必须含政策依据和顾问工作量");
        }
        TaskState state = switch (result.stage()) {
            case NEEDS_INPUT -> TaskState.NEEDS_INPUT;
            case NEEDS_REVIEW -> TaskState.NEEDS_REVIEW;
            case READY_FOR_ADMIN_REVIEW -> TaskState.READY_FOR_ADMIN_REVIEW;
            case NOT_APPLICABLE -> TaskState.STALE_PROPOSAL;
            case UNAUTHORIZED -> throw new IllegalArgumentException("不能保存未授权预览");
        };
        Preview safe = new Preview(result.stage(),
                result.policyEvidence().stream().map(evidence -> evidence.sourceRef()).toList(),
                result.advisors().stream().map(advisor -> new AdvisorSnapshot(advisor.advisorId(),
                        advisor.processing(), advisor.total(), advisor.storeAffiliationVerified())).toList(),
                result.observedAt());
        String previewJson;
        try {
            previewJson = json.writeValueAsString(safe);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("预览摘要编码失败", error);
        }
        return jdbc.query("""
                UPDATE agent_task SET preview_json = CAST(? AS jsonb), state = ?,
                  version = version + 1, updated_at = now()
                WHERE task_id = ? AND admin_id = ? AND version = ? AND state IN %s
                RETURNING %s
                """.formatted(EDITABLE_STATES, COLUMNS), taskMapper,
                previewJson, state.name(), taskId, adminId, expectedVersion).stream().findFirst();
    }

    private Task mapTask(ResultSet rs, int row) throws SQLException {
        String previewJson = rs.getString("preview_json");
        Preview preview;
        try {
            preview = previewJson == null ? null : json.readValue(previewJson, Preview.class);
        } catch (JsonProcessingException error) {
            throw new SQLException("任务预览摘要格式错误", error);
        }
        long advisorId = rs.getLong("order_advisor_id");
        Long orderAdvisorId = rs.wasNull() ? null : advisorId;
        OffsetDateTime appointmentAt = rs.getObject("appointment_at", OffsetDateTime.class);
        return new Task(rs.getObject("task_id", UUID.class), rs.getLong("admin_id"),
                rs.getLong("work_order_id"), rs.getInt("order_type"), rs.getInt("order_status"),
                orderAdvisorId, rs.getString("order_updated_at"), rs.getLong("version"),
                TaskState.valueOf(rs.getString("state")),
                new ReadOnlyAdviceFlow.Supplement(rs.getString("store_id"),
                        rs.getString("model_code"), appointmentAt), preview,
                rs.getTimestamp("updated_at").toInstant());
    }

    private static boolean invalidCode(String value) {
        return value != null && (value.length() > 100
                || !value.isBlank() && !value.matches("[A-Za-z0-9-]+"));
    }
}
