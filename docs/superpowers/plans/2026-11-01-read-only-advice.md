# 工单只读建议流程实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 让本地 Agent 能凭管理员当前 Session 读取试驾工单、模拟政策和顾问工作量，返回可追溯的只读建议预览。

**Architecture:** HTTP 适配器只允许读取 `carSystem` 的四个 GET 接口，检查 HTTP 与业务码并只保留必要字段。纯 Java 流程先核对身份和工单，再检查管理员明确补充的门店、车型、准确时间；证据齐全时展示模拟政策和顾问负载，停止于人工核对。预览不落任务状态、不提供确认或分配写入。

**Tech Stack:** Java 21、Spring Boot 3.5.8、JDK HttpClient、现有 Spring AI pgvector 政策检索。

**Spec:** `docs/superpowers/specs/2026-10-20-car-work-order-agent-design.md` 第 3、5 节；`docs/verification/stage-0/car-system-contract.md`。

## 全局约束

- 只调用 `GET /api/user/current`、`GET /api/workOrder/get`、`GET /api/user/list/advisor`、`GET /api/workOrder/workload`；没有分配客户端方法。
- 仅转发 `SESSION` Cookie；不保存或输出 Cookie、完整客户资料、管理员密码。
- 当前模拟政策不能证明真实车源、时段或顾问门店归属；预览始终不能执行分配。
- `carSystem` 当前工作区有未提交的权限修复，必须单独联调确认；本次不修改它。

## 复核重点

- HTTP 200 但业务 `code` 非 0 时必须失败，不能把顾问工作量缺失当成零。
- 普通用户、封禁用户、已分配或非试驾工单不得得到可确认建议。
- 缺少门店、车型或精确时间时，不调用政策或顾问工具。
- 顾问名单与工作量都成功后，未出现的正常顾问才按零处理中工单展示；不推断门店归属。
- 政策冲突、无依据及只读工具失败须停在人工核对；不得调用任何业务 POST。

## 任务

### 1. `carSystem` 只读 HTTP 适配器

**文件：**新增 `integration/CarSystemReadClient.java`、`integration/HttpCarSystemReadClient.java` 及其测试。

- [x] 先写本地假 HTTP 服务测试：只转发 `SESSION`，四个 GET 按原契约解析；HTTP 200 + `code=50000` 抛安全异常；无 Cookie 拒绝。
- [x] 运行测试确认因类缺失而失败。
- [x] 实现固定本机地址、连接与请求超时、无重定向、无 Cookie 持久化的读取适配器。
- [x] 运行测试确认通过。

### 2. 只读流程与建议

**文件：**新增 `workflow/ReadOnlyAdviceFlow.java` 及其测试，复用 `PolicyRagService` 的候选证据判定。

- [x] 先写固定案例：缺时间、正常资料、非管理员、已分配工单、政策冲突、顾问接口失败、零工单顾问。
- [x] 运行测试确认因类缺失而失败。
- [x] 实现显式状态流转与只读建议；工作量仅供参考，顾问门店归属明确待核实。
- [x] 运行测试确认通过。

### 3. 本地预览入口与联调记录

**文件：**新增 `workflow/ReadOnlyAdviceController.java`、配置与入口测试；更新 README 与联调记录。

- [x] 先写接口测试：缺 Cookie 返回 401；完整输入返回只读预览、没有分配能力。
- [x] 运行测试确认因入口缺失而失败。
- [x] 实现本机 `GET /api/agent/preview`；只在 `policy-rag` 配置组启用，接入前两项服务。
- [x] 跑普通测试、隔离假 HTTP 联调及现有政策检索测试；记录真实 Session 联调未完成的边界。
- [x] 检查密钥与 Cookie 未进入提交，中文提交并推送。
