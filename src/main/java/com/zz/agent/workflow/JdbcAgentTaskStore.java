package com.zz.agent.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.store.NearbyStoreAvailabilityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Agent 自有任务存储；管理员操作按身份与版本约束，客户选择按候选与版本约束。 */
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
                       ReadOnlyAdviceFlow.Supplement supplement, Preview preview, Instant updatedAt,
                       StoreChoice storeChoice) {
        public Task(UUID id, long adminId, long workOrderId, int orderType, int orderStatus,
                    Long orderAdvisorId, String orderUpdatedAt, long version, TaskState state,
                    ReadOnlyAdviceFlow.Supplement supplement, Preview preview, Instant updatedAt) {
            this(id, adminId, workOrderId, orderType, orderStatus, orderAdvisorId,
                    orderUpdatedAt, version, state, supplement, preview, updatedAt, null);
        }
    }

    public record StoreChoice(UUID offerId, long customerId, String snapshotId, Instant selectedAt) {
    }

    public record StoreOffer(UUID id, UUID taskId, long customerId, long taskVersion,
                             String modelCode, OffsetDateTime appointmentAt,
                             List<NearbyStoreAvailabilityService.Option> options, Instant expiresAt) {
        public StoreOffer {
            options = List.copyOf(options);
        }
    }

    private static final String COLUMNS = "task_id, admin_id, work_order_id, order_type, order_status, "
            + "order_advisor_id, order_updated_at, version, state, store_id, model_code, "
            + "appointment_at, preview_json, updated_at, choice_offer_id, "
            + "choice_customer_id, choice_snapshot_id, choice_selected_at";
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
        jdbc.execute("ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS choice_offer_id uuid");
        jdbc.execute("ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS choice_customer_id bigint");
        jdbc.execute("ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS choice_snapshot_id varchar(200)");
        jdbc.execute("ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS choice_selected_at timestamptz");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_store_offer (
                    offer_id uuid PRIMARY KEY,
                    task_id uuid NOT NULL REFERENCES agent_task(task_id),
                    customer_id bigint NOT NULL CHECK (customer_id > 0),
                    task_version bigint NOT NULL CHECK (task_version >= 0),
                    model_code varchar(100) NOT NULL,
                    appointment_at timestamptz NOT NULL,
                    options_json jsonb NOT NULL,
                    expires_at timestamptz NOT NULL
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

    /** 仅供已验证客户身份的流程再次核查工单归属；不得直接暴露为客户查询接口。 */
    public Optional<Task> findForOwnershipCheck(UUID taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_task WHERE task_id = ?",
                taskMapper, taskId).stream().findFirst();
    }

    public StoreOffer createStoreOffer(UUID taskId, long customerId, long expectedVersion,
                                       String modelCode, OffsetDateTime appointmentAt,
                                       List<NearbyStoreAvailabilityService.Option> options,
                                       Instant expiresAt) {
        if (taskId == null || customerId <= 0 || expectedVersion < 0 || invalidCode(modelCode)
                || modelCode == null || modelCode.isBlank() || appointmentAt == null
                || options == null || options.isEmpty() || options.size() > 5 || expiresAt == null) {
            throw new IllegalArgumentException("选店候选参数不合法");
        }
        Task task = findForOwnershipCheck(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (task.version() != expectedVersion || !editable(task.state())
                || task.supplement().storeId() != null || task.storeChoice() != null) {
            throw new IllegalStateException("任务版本或状态已变化");
        }
        String optionsJson;
        try {
            optionsJson = json.writeValueAsString(options);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("候选门店编码失败", error);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_store_offer (offer_id, task_id, customer_id, task_version,
                  model_code, appointment_at, options_json, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, id, taskId, customerId, expectedVersion, modelCode, appointmentAt,
                optionsJson, Timestamp.from(expiresAt));
        return new StoreOffer(id, taskId, customerId, expectedVersion, modelCode,
                appointmentAt, options, expiresAt);
    }

    public Optional<StoreOffer> findStoreOffer(UUID offerId) {
        if (offerId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM agent_store_offer WHERE offer_id = ?", (rs, row) -> {
            List<NearbyStoreAvailabilityService.Option> options;
            try {
                options = json.readValue(rs.getString("options_json"),
                        new TypeReference<List<NearbyStoreAvailabilityService.Option>>() {});
            } catch (JsonProcessingException error) {
                throw new SQLException("候选门店记录格式错误", error);
            }
            return new StoreOffer(rs.getObject("offer_id", UUID.class),
                    rs.getObject("task_id", UUID.class), rs.getLong("customer_id"),
                    rs.getLong("task_version"), rs.getString("model_code"),
                    rs.getObject("appointment_at", OffsetDateTime.class), options,
                    rs.getTimestamp("expires_at").toInstant());
        }, offerId).stream().findFirst();
    }

    /** 快照一致且版本未变时只写一次；重复提交同一选择返回已保存结果。 */
    public Optional<Task> chooseStore(UUID offerId, long customerId, String storeId,
                                      String currentSnapshotId, Instant now) {
        StoreOffer offer = findStoreOffer(offerId).orElse(null);
        if (offer == null || offer.customerId() != customerId || now == null) {
            return Optional.empty();
        }
        Task current = findForOwnershipCheck(offer.taskId()).orElse(null);
        if (current != null && current.storeChoice() != null
                && offerId.equals(current.storeChoice().offerId())
                && customerId == current.storeChoice().customerId()
                && Objects.equals(storeId, current.supplement().storeId())) {
            return Optional.of(current);
        }
        if (!now.isBefore(offer.expiresAt()) || storeId == null || currentSnapshotId == null) {
            return Optional.empty();
        }
        NearbyStoreAvailabilityService.Option option = offer.options().stream()
                .filter(item -> storeId.equals(item.storeId())).findFirst().orElse(null);
        if (option == null || !option.selectable() || !option.simulated()
                || option.availability() != NearbyStoreAvailabilityService.Availability.AVAILABLE
                || !currentSnapshotId.equals(option.snapshotId())) {
            return Optional.empty();
        }
        Optional<Task> saved = jdbc.query("""
                UPDATE agent_task SET store_id = ?, model_code = ?, appointment_at = ?,
                  choice_offer_id = ?, choice_customer_id = ?, choice_snapshot_id = ?,
                  choice_selected_at = ?, preview_json = NULL, state = 'NEEDS_INPUT',
                  version = version + 1, updated_at = now()
                WHERE task_id = ? AND version = ? AND store_id IS NULL
                  AND choice_offer_id IS NULL AND state IN %s
                RETURNING %s
                """.formatted(EDITABLE_STATES, COLUMNS), taskMapper,
                storeId, offer.modelCode(), offer.appointmentAt(), offerId, customerId,
                currentSnapshotId, Timestamp.from(now), offer.taskId(), offer.taskVersion()).stream().findFirst();
        if (saved.isPresent()) {
            return saved;
        }
        Task after = findForOwnershipCheck(offer.taskId()).orElse(null);
        return after != null && after.storeChoice() != null
                && offerId.equals(after.storeChoice().offerId())
                && customerId == after.storeChoice().customerId()
                && Objects.equals(storeId, after.supplement().storeId())
                ? Optional.of(after) : Optional.empty();
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
                  choice_offer_id = NULL, choice_customer_id = NULL,
                  choice_snapshot_id = NULL, choice_selected_at = NULL,
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
        UUID offerId = rs.getObject("choice_offer_id", UUID.class);
        StoreChoice choice = offerId == null ? null : new StoreChoice(offerId,
                rs.getLong("choice_customer_id"), rs.getString("choice_snapshot_id"),
                rs.getTimestamp("choice_selected_at").toInstant());
        return new Task(rs.getObject("task_id", UUID.class), rs.getLong("admin_id"),
                rs.getLong("work_order_id"), rs.getInt("order_type"), rs.getInt("order_status"),
                orderAdvisorId, rs.getString("order_updated_at"), rs.getLong("version"),
                TaskState.valueOf(rs.getString("state")),
                new ReadOnlyAdviceFlow.Supplement(rs.getString("store_id"),
                        rs.getString("model_code"), appointmentAt), preview,
                rs.getTimestamp("updated_at").toInstant(), choice);
    }

    private static boolean editable(TaskState state) {
        return state == TaskState.NEEDS_INPUT || state == TaskState.NEEDS_REVIEW
                || state == TaskState.READY_FOR_ADMIN_REVIEW || state == TaskState.STALE_PROPOSAL;
    }

    private static boolean invalidCode(String value) {
        return value != null && (value.length() > 100
                || !value.isBlank() && !value.matches("[A-Za-z0-9-]+"));
    }
}
