# 阶段 0：接口与技术可行性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本项目由用户亲自编写后续 RAG、状态机、工具边界和评测核心；Codex 辅助探针样板、讲解与审查。本文只规划阶段 0，不授权开始编码。

**Goal:** 在写业务代码前，用可复核的证据锁定 Java 依赖组合、`carSystem` HTTP 契约及管理员 Session 方案，并明确真实分配功能能否安全开始。

**Architecture:** 阶段 0 只做只读核对和隔离的技术探针。探针不读取 `carSystem` 数据库，不调用真实分配接口，不进入产品源码；结论写入本仓库验证记录。RAG、流程、分配和评测各自另写阶段计划，基于本阶段定版结果展开。

**Tech Stack:** 候选 Java 21、Spring Boot 3.5.x、Spring AI Alibaba 1.1.2.2、Spring AI 1.1.2、千问 Plus、`text-embedding-v4` 1024 维、PostgreSQL + pgvector；此处均为待验证候选。

**Spec:** [第一版设计](../specs/2026-10-20-car-work-order-agent-design.md)

## Global Constraints

- 这是独立 Java 项目；不改 `/Users/zz/carProject`，不读写其数据库。
- 仅使用模拟工单和政策样本；不把密码、API Key、Session Cookie、客户个人信息写进文档、命令输出或提交。
- `carSystem` 分配请求只有 `id`、`advisorId`，无幂等键；阶段 0 不调用真实 `POST /api/workOrder/assign`。
- 第一版用 Java 显式状态机；Graph、Redis 会话/检查点、多 Agent、Milvus、MCP 不属于本阶段。
- 每项验证须标注“源码已核对”“运行已验证”或“尚未验证”，不能把候选版本或设计写成实测结论。

## Review Focus

以下五类条件若处理错，会让后续设计失效；对应测试分别安排在任务 1–4。

1. **运行环境不符：**默认 Java 8 或缺少 Maven 时，兼容性探针必须明确失败并停止定版，不能用系统默认运行结果冒充 Java 21 结果（任务 1、4）。
2. **Cookie 作用域：**`localhost` 与 `127.0.0.1` 不可混用；`Path=/api` 的 Cookie 应能到 `/api/agent/**`，但不会自动到其他路径（任务 3）。
3. **业务码与 HTTP 码：**未登录可能是 HTTP 200、业务 `code=40100`；所有业务 API 判定须先解析 `BaseResponse.code`（任务 2、3）。
4. **角色/会话变化：**退出、过期、普通用户或角色变更后，Agent 不得继续把旧建议当作管理员确认（任务 3）。
5. **认证探针误触写入：**阶段 0 的任何测试不得发真实分配 POST；CSRF 反例仅对无业务副作用的探针接口测试（任务 3）。

## Planned Files

| 文件 | 单一职责 |
|---|---|
| `docs/verification/stage-0/toolchain.md` | 记录 Java、构建工具和数据库环境的实测版本与安装来源 |
| `docs/verification/stage-0/car-system-contract.md` | 固定本次只读核对的接口路径、字段、业务码和源码位置 |
| `probes/stage-0/session/LocalSameOriginProxy.java` | 临时本地代理：`localhost:8090` 将 `/api/agent/**` 转至探针，其余 `/api/**` 转至运行中的 `carSystem`；仅用于联调 |
| `probes/stage-0/session/SessionProbe.java` | 只读转发 `/api/user/current`；另提供无业务副作用的 CSRF 测试端点，不实现分配 |
| `docs/verification/stage-0/session-matrix.md` | 记录脱敏的登录态、Cookie、权限、同源和 CSRF 正反例 |
| `probes/stage-0/pom.xml` 与 `probes/stage-0/src/test/java/com/zz/agent/probe/DependencySmokeTest.java` | 独立的 Java 兼容性探针，仅验证依赖和最小模型/向量 API，不作为产品代码 |
| `docs/verification/stage-0/compatibility.md` | 记录 Maven 依赖树、编译、模型/Embedding 调用及异常 |
| `docs/verification/stage-0/decision.md` | 锁定版本、登录方案和阶段 1 入口条件；列出未通过的门槛 |

探针目录只在执行任务 3、4 时创建；本计划提交时不创建任何应用代码、配置、数据库表或部署文件。阶段 0 的 CSRF 结果仅证明所选方案可联调；后续产品写接口仍须用相同规则做集成测试。

### Task 1: 本机工具链预检

**Files:** Create `docs/verification/stage-0/toolchain.md`。

**Interfaces:** Produces `JDK_HOME` 的本地路径、`java -version`、`mvn -version` 及 PostgreSQL/pgvector 的实测可用性说明，供任务 4 使用；不把本地路径当成跨机器固定值。

- [ ] **Step 1: 运行预检。** 执行 `java -version`、`/usr/libexec/java_home -V`、`mvn -version`，把命令及结果类别记入 `toolchain.md`。本轮预检已发现系统默认 Java 8、`mvn` 不在 PATH；要重新运行确认。
- [ ] **Step 2: 准备 Java 21 与 Maven。** 使用可核实来源安装或定位已有版本，并在仅限本项目的终端环境中选择 Java 21；不改 `carSystem` 的运行环境。
- [ ] **Step 3: 验证门槛。** `java -version` 必须显示 21，`mvn -version` 必须显示 Maven 正常运行且使用同一 Java 21；否则任务 4 不开始。把 PostgreSQL 及 `vector` 扩展是否可连接作为独立结果记录，未安装时不得写“已验证”。
- [ ] **Step 4: 提交验证记录。** 仅提交脱敏 `toolchain.md`；不提交机器级路径中的凭据或环境文件。

### Task 2: 重新核对 `carSystem` 接口

**Files:** Create `docs/verification/stage-0/car-system-contract.md`。

**Interfaces:** Produces `CarSystemContract` 文档表：路径、方法、请求字段、`BaseResponse`、权限、状态条件和源码位置；供任务 3 以及后续业务工具设计使用。

- [ ] **Step 1: 读取当前源码。** 只读核对 `UserController`、`WorkOrderController`、请求/视图 DTO、`LoginInterceptor`、`AuthRoleAspect`、分配实现及工作量实现；记录新的 Git 提交和工作区是否有未提交修改。
- [ ] **Step 2: 写契约表。** 覆盖 `/api/user/current`、`/api/workOrder/get`、`/api/user/list/advisor`、`/api/workOrder/workload`、`/api/workOrder/assign`；标出 `code=0` 成功和未登录/无权限/状态冲突类别，不抄录密钥或个人数据。
- [ ] **Step 3: 运行无凭据负例。** 对 `/api/user/current` 和 `/api/workOrder/workload` 只发 GET，记录 HTTP 状态与 `BaseResponse.code`。当前运行服务的预检观测为 HTTP 200 + `code=40100`，执行时须重新验证并避免打印 `Set-Cookie` 值。
- [ ] **Step 4: 验收。** 五条接口都能从源码追到 Controller、请求/响应形状和权限；无凭据请求不能被文档误判为成功。提交本任务文档。

### Task 3: 验证管理员 Session 与同源方案

**Files:** Create `probes/stage-0/session/LocalSameOriginProxy.java`、`probes/stage-0/session/SessionProbe.java`、`docs/verification/stage-0/session-matrix.md`。两份 Java 文件只用于本地探针，使用 JDK 内置 HTTP API，不进入产品代码。

**Interfaces:** Produces按“管理员/普通用户/未登录/已退出/已过期/角色变更/跨站请求”划分的可复核矩阵，记录请求 Host、Cookie 属性、HTTP/业务码和 Agent 应采取的拒绝或放行结果；不保存 Cookie 值。

- [ ] **Step 1: 检查 Cookie 属性。** 从浏览器开发者工具或脱敏响应头记录 Cookie 名称、`Domain`、`Path`、`HttpOnly`、`SameSite`；当前无凭据预检观测到 `Domain=localhost`、`Path=/api`、`SameSite=Lax`，必须用登录后的会话再次确认。
- [ ] **Step 2: 建立可丢弃的同源探针。** 用 Java 21 启动 `localhost:8090` 的本地代理与另一个本地端口的探针。代理仅按路径转发 `/api/agent/**` 到探针，其余 `/api/**` 到 `carSystem:8080`；探针 GET 端点只将请求中必要的 `SESSION` Cookie 转发给 `GET /api/user/current`，解析 `BaseResponse.code`、角色和账户状态，绝不输出 Cookie 值。探针 POST 端点仅返回“CSRF 检查通过/拒绝”，没有业务写入。端口冲突时记录并换可用端口，不修改运行中的 `carSystem`。
- [ ] **Step 3: 验证同源路由。** 浏览器在 `http://localhost:8090` 代理入口完成登录，然后从同一 Origin 请求 `/api/agent/session-probe`；核对 Agent 路径收到 Cookie 且当前身份由 `carSystem` 实时认定。分别试 `127.0.0.1`，不能把它当作 `localhost` 的等价 Cookie 域。只记录属性和是否携带，不记录值。
- [ ] **Step 4: 验证权限矩阵。** 用户用本地模拟管理员和普通用户账户登录，分别 GET 当前用户、工单和工作量；退出/过期后再试。探针每次重新调用 `/api/user/current`，记录业务码、可见范围和 Session 角色变化行为；不让探针持久化账号密码。若无法安全取得这些测试身份，对相应行记“未验证”。
- [ ] **Step 5: 验证 Agent 写入口 CSRF。** 探针签发与当前登录会话绑定的短期一次性 Token，POST 时同时检查同源 `Origin`、Token 和实时管理员身份。覆盖有效请求、缺 Token、错误 Token、重放、跨站 `Origin`、退出或过期后持旧 Token，记录每种请求是否被拒绝；不调用真实分配接口。若探针未能运行，明确记“未验证”，阶段 0 不得宣布认证方案通过。
- [ ] **Step 6: 验收。** 管理员可读；普通用户、未登录、退出和过期被拒绝；Cookie 在目标同源路径传递；CSRF 正反例符合预期。任一项失败则真实分配保持关闭。提交探针与脱敏矩阵，注明该探针不是产品认证实现。

### Task 4: 验证依赖和模型接口兼容性

**Files:** Create `probes/stage-0/pom.xml`、`probes/stage-0/src/test/java/com/zz/agent/probe/DependencySmokeTest.java`、`docs/verification/stage-0/compatibility.md`。

**Interfaces:** Produces固定 Maven 依赖组合与 `DependencySmokeTest` 的结果；只暴露“候选通过/失败及原因”，供任务 5 定版。探针不包含业务状态机、政策入库或分配接口。

- [ ] **Step 1: 先写失败的兼容性测试。** `DependencySmokeTest` 至少检查 Java 运行版本为 21，并能加载同一项目中的 `ChatModel`、`EmbeddingModel` 与 PGvector 相关类。先在空探针项目执行 `mvn -q test`，预期因缺依赖或类失败；保留该输出摘要。
- [ ] **Step 2: 添加最小依赖。** 候选从 Boot 3.5.8、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2 起步，使用各自 BOM/官方推荐的依赖管理方式；具体模块名称从锁定版本的官方文档核对，不从 Spring AI 2.x 文档复制。
- [ ] **Step 3: 运行 `mvn -q test` 与 `mvn dependency:tree`。** 测试通过且依赖树不存在两个互不兼容的 Spring AI 主版本，才记录“编译兼容”。失败则在 `compatibility.md` 记录根因及下一候选，不在业务仓库补版本覆盖凑通过。
- [ ] **Step 4: 可用时做最小真实调用。** 用户选择把 API Key 填入 Git 忽略的本地 YAML（模板见 `config/application-local.yml.example`）；用模拟短文本调用所选千问模型和 `text-embedding-v4`，记录模型 ID、地域、向量维度、成功/错误码和耗时，不记录 Key 或响应中的个人信息。无 Key 时标“真实调用未验证”，不得写成通过。
- [ ] **Step 5: 验收并提交。** 只有 Java 21、Maven 测试、依赖树和真实调用均有成功证据，才把该组合写为已验证；否则保留候选状态。探针及脱敏结果可单独提交，且不得被产品直接依赖。

### Task 5: 阶段 0 定版与交接

**Files:** Create `docs/verification/stage-0/decision.md`；如验证结果推翻设计假设，修改对应的[设计文档](../specs/2026-10-20-car-work-order-agent-design.md)。

**Interfaces:** Produces阶段 1 可使用的版本号、认证路由、Cookie/CSRF 规则、接口契约版本和未解决风险；后续各阶段计划只引用这份定版记录。

- [ ] **Step 1: 汇总四份验证记录。** 每个门槛列“通过/失败/未验证”、证据文件、时间和执行环境；不能把未验证算作通过。
- [ ] **Step 2: 对照设计硬边界。** 确认不改 `carSystem`、不使用真实个人信息、不让模型调用分配、无登录或 CSRF 失败时不能执行真实写入。
- [ ] **Step 3: 作 Go/No-Go 决定。** 只有工具链、接口、同源 Session/CSRF、依赖和模型调用都通过，才锁定阶段 1 技术选型；否则列出具体阻塞和下一次验证动作。
- [ ] **Step 4: 提交定版记录并写下一阶段计划。** 阶段 1 聚焦模拟语料与 RAG，由用户亲自实现关键检索，Codex提供设计评审和测试建议；在此之前不把阶段 0 探针当成完成的 Agent 产品。
