# 客户模拟选店实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 客户在有效登录态下从本次展示的五家模拟门店中选择一家，选择结果只写入 Agent 任务。

**Architecture:** Agent 数据库保存十分钟有效的五店候选及当时的可用性快照，不保存客户坐标。客户提交时重查工单归属、任务版本和所选门店状态，数据库条件更新保证并发只有一次成功；重复提交同一选择返回原结果。接口由现有同源 CSRF 过滤器保护。

**Tech Stack:** Java 21、Spring Boot 3、PostgreSQL、JdbcTemplate、JUnit 5。

**Spec:** [附近门店与客户选店](../specs/2026-10-28-nearby-store-selection-design.md)

状态：已实现并完成本计划的隔离测试；代码在本阶段统一提交。

## Global Constraints

- 不修改 `carSystem` 或其数据库，不执行真实预约和分配。
- 模拟门店状态只作为演示数据；政策检索仍在独立的 RAG 中。
- 客户身份必须是原工单 `customerId`；写请求必须有同源 Origin、CSRF 和任务版本。

## Review Focus

- 非工单客户、管理员、已封禁客户均不能选店。
- 不在本次五店候选中、无车、满额、未知状态的门店均不能选。
- 候选过期或门店快照变化时不写任务。
- 重复相同提交幂等；并发或改选不能覆盖。
- 工单状态、时间或顾问变化时停止选店。

---

### Task 1: 业务工单客户归属字段

**Files:** `CarSystemReadClient.java`、`HttpCarSystemReadClient.java`、`HttpCarSystemReadClientTest.java`

**Interfaces:** `WorkOrder.customerId()` 为可空 `Long`；缺失时客户流程拒绝。

- [x] 测试响应含 `customerId=5001` 时客户端只提取该编号；先观察失败。
- [x] 实现字段解析并使测试通过。
- [x] 阶段结束统一提交。

### Task 2: 候选与选择持久化

**Files:** `JdbcAgentTaskStore.java`、`JdbcAgentTaskStoreProbeTest.java`

**Interfaces:** `createStoreOffer(...)`、`findStoreOffer(...)`、`chooseStore(...)`；选择记录含客户编号、快照、时间和 offer ID。

- [x] 用真实 PostgreSQL 写失败测试：保存五店候选、条件写入、重复与并发选择、旧预览失效。
- [x] 扩展 Agent 自有表和版本条件更新，跑通测试。
- [x] 阶段结束统一提交。

### Task 3: 客户选店流程与 HTTP 接口

**Files:** `CustomerStoreSelectionService.java`、`CustomerStoreController.java`、`PolicyRagConfiguration.java`、对应测试。

**Interfaces:** `offer(cookie,taskId,expectedVersion,location,model,time)` 与 `choose(cookie,offerId,storeId)`；HTTP 路径 `/api/agent/customer-stores`。

- [x] 先写失败测试覆盖权限、过期、状态变化、门店快照变化与重复提交。
- [x] 实现只读校验和 Agent 数据库写入，接入 CSRF、`no-store` 响应。
- [x] 跑完整 Maven 测试和数据库探针，提交并推送。
