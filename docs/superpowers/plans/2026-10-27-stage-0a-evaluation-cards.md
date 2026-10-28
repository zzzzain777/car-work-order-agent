# Stage 0A Evaluation Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Codex 起草文档与金标准，项目作者复核业务判断并亲自设计代码架构。

**Goal:** 在只读原型编码前，固定 C01–C06、C09 七张可复跑案例卡及对应模拟政策原文。

**Architecture:** 使用本仓库的 Markdown 文件保存模拟输入、人工金标准与政策来源。案例使用固定时间和 API 响应快照；A/B/C 后续共用同一版输入与标准答案。

**Tech Stack:** Markdown、Git；本阶段不新增 Java 代码或依赖。

**Spec:** [`2026-10-20-car-work-order-agent-design.md`](../specs/2026-10-20-car-work-order-agent-design.md)

## Global Constraints

- `carSystem` 是独立系统，本阶段只写模拟数据，不改其数据库和源码。
- 不保存真实客户信息、账号、Cookie、API Key；不调用真实分配。
- Codex 起草案例、模拟语料和金标准；项目作者复核业务判断，亲自设计检索、流程及工具代码架构，并分析运行失败案例。

## Review Focus

- 预约时间与政策有效期相交边界：`appointmentAt` 到 `effectiveTo` 即失效；`asOf` 只表示模拟的当前时间。
- 车型相近编号：`MX-7` 不得使用 `MX-70` 的原文。
- 零工单顾问：仅顾问列表和工作量接口都成功后，缺席工作量行才代表零。
- HTTP 200 携带非零业务码：不能当成成功的工具结果。
- 缺门店但有位置和时间：先给最近五家及模拟可用状态，客户选店后才进入该店政策检索；缺时间或无可用门店时不能生成可执行建议。

---

> 本计划只准备评测材料，不实现 RAG、状态机或真实分配。Codex 负责起草，项目作者复核并承担后续代码架构与核心实现。

**目标：**在编写只读原型前，固定 C01–C06、C09 七张可复跑案例卡及其所需的模拟政策原文。

**依据：**[第一版设计](../specs/2026-10-20-car-work-order-agent-design.md)第 1、4、7、8 节；[仓库约定](../../../AGENTS.md)。

**交付位置：**案例放在 `data/eval/cases/`；模拟政策放在 `data/policies/`。两处材料都不包含真实客户信息、账号、Cookie 或 API Key。

## 任务 1：起草案例输入与金标准（Codex）

- [x] 建立 [C02 附近门店选店草稿](../../../data/eval/cases/C02-nearby-store-selection.md)，包含六家候选供最近五家排序，并标注模拟可用性；尚未冻结或运行。
- [x] 依据 [`_template.md`](../../../data/eval/cases/_template.md)，起草 C01、C03–C06、C09 案例卡；均未冻结或运行。
- [ ] 每张卡写一个固定的 `asOf` 当前时间、预约试驾时间 `appointmentAt`（缺失时明确写“缺失”）、原始工单快照、管理员可补充字段、顾问与工作量 API 固定响应；数据全部为模拟。
- [ ] 逐张确定初始预期状态、补充后的预期状态、允许引用的政策条款、必须查询和禁止查询的工具、分配 POST 预期次数。C01 在本阶段最多到 `AWAITING_CONFIRMATION`，因此 POST 次数为 0。
- [ ] 逐张写一句判定理由，说明为什么该状态和引用是对的。不要用运行后的模型输出倒推标准答案。

**检查：**只看案例卡和政策原文，不运行模型，也能由人独立判定 C01–C06、C09 应通过还是应停止；七张卡的编号、时间、车型编号和门店编号互相一致。

## 任务 2：准备对应的模拟政策（Codex）

- [x] 按 [`data/policies/_template.md`](../../../data/policies/_template.md)起草有效政策、相近车型政策、过期政策和同范围冲突政策；每条保留 `policyId@version`、适用门店、车型编号、生效区间及可打开的 `sourceRef`。
- [x] 在案例卡中引用现有政策和条款；过期、冲突场景写明应排除或应展示的来源。无命中 C07 留待下一批案例。
- [x] 加入一份没有规范条款标题的冲突政策，用来检查切块策略是否只适配手工整理过的 Markdown。

**检查：**C04 的 `MX-7` 不使用 `MX-70` 政策；C05 的过期判断使用案例中的 `appointmentAt`；C06 的冲突可由两份原文直接核对。政策仍须标明模拟性质，不暗示真实车企规定。

## 任务 3：项目作者复核后冻结（双方）

- [ ] Codex 检查字段缺漏、案例互相矛盾、时间边界及预期工具是否越权；项目作者通读七张卡并确认业务标准答案。
- [ ] 记录本轮案例和政策文件的 Git 提交或文件哈希、冻结日期与修订原因。后续修订保留旧版和对应评测结果。
- [ ] 冻结后再写只读原型的 Java 测试和实现。先跑 A：规则/关键词基线；再在相同输入上比较 B 和 C，不提前声称向量检索或模型工具调用有收益。

**阶段 0A 验收：**七张完整案例卡、可回溯的模拟原文、可人工复核的金标准和冻结版本均存在。阶段 0 的 Session、Cookie、CSRF 和下游权限门槛尚未通过前，真实分配继续关闭；离线案例和只读原型可继续推进。
