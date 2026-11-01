# Agent 任务持久化 Implementation Plan

> **For agentic workers:** 当前由 Codex 在本会话直接实施；每项先写失败测试，再实现并复测。

**Goal:** 在 Agent 数据库保存按管理员隔离、可恢复、带版本的只读建议任务。

**Architecture:** 在 `policy-rag` 配置组复用现有 JDBC 连接，新增独立任务表和存储类。数据库条件更新负责版本冲突；预览只保留不含个人信息的依据引用和顾问工作量。

**Tech Stack:** Java 21、Spring Boot 3.5.8、Spring JDBC、PostgreSQL、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-11-01-durable-agent-task-design.md`

## Global Constraints

- 不修改 `carSystem`，不访问其数据库，不保存登录凭据或个人信息。
- 本阶段没有任务写入 HTTP 接口、确认或分配。

## Review Focus

- 旧版本重放：数据库应拒绝第二次更新。
- 管理员 ID 不一致：读取和修改都无结果。
- 补充后的旧预览：必须立即清空。
- 未授权或可执行的预览：必须拒绝保存。
- 进程重建：任务仍可从 PostgreSQL 读取。

---

### Task 1: 数据库任务与版本更新

**Files:** 新建 `src/main/java/com/zz/agent/workflow/JdbcAgentTaskStore.java` 和 `src/test/java/com/zz/agent/workflow/JdbcAgentTaskStoreProbeTest.java`；修改 `TaskState.java`、`PolicyRagConfiguration.java`、`probes/stage-1/java-pgvector-smoke.sh`。

**Interfaces:** `create(long adminId, WorkOrder order)`、`find(UUID taskId, long adminId)`、`updateSupplement(UUID taskId, long adminId, long expectedVersion, Supplement supplement)`、`recordPreview(UUID taskId, long adminId, long expectedVersion, Result result)`。

- [x] 写隔离数据库测试，覆盖 Review Focus 的五种输入和持久化字段。
- [x] 运行探针，确认测试因缺少实现失败。
- [x] 创建任务表和条件更新实现；只保存安全精简的预览。
- [x] 重跑探针与完整 Maven 测试，记录结果。
- [x] 更新验证记录，并提交、推送代码。
