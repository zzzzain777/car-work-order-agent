# 高 Star Agent 项目的架构借鉴

核对日期：2026-10-28。本记录整理此前对 Spring AI Alibaba、LangGraph、Dify、Langfuse 的调研，并对照本仓库的实际进度。GitHub Star 数随时间变化，不作为技术选型依据。下列“借鉴”是设计决定，只有对应代码和测试完成后才算实现。

## 我们从四个项目学什么

| 项目与依据 | 可借鉴的设计思想 | 在汽车工单 Agent 中的具体用法 | 当前状态 |
|---|---|---|---|
| [Spring AI Alibaba Graph Core](https://github.com/alibaba/spring-ai-alibaba/blob/main/spring-ai-alibaba-graph-core/README.md) | 状态随节点流转，条件分支决定下一步，关键节点可以暂停等待人工介入。 | 第一版先由 Java 显式状态机控制“补信息 → 选店 → 查依据和顾问 → 建议 → 管理员确认 → 执行/核查”。Graph 放到第二阶段，只有它能减少可测的流程复杂度时再引入。 | 目前只有确认前的纯规则判断；完整流程与持久化未实现。 |
| [LangGraph 的重放与幂等说明](https://github.com/langchain-ai/docs/blob/main/src/oss/langgraph/graph-api.mdx#re-execution-and-idempotency) | 恢复运行时，节点里的代码和外部副作用可能再次执行；“有检查点”不等于外部写入只执行一次。 | 持久保存任务及确认状态；重复确认只允许一次执行尝试。确认后重查工单，再调用分配 API。调用超时进入“结果未知”，先查真实工单状态，不自动重试；现有 `carSystem` 分配 API 没有幂等键。 | 设计已确定；持久化、执行与重查未实现。 |
| [Dify 的工作流执行记录](https://github.com/langgenius/dify/blob/main/api/core/app/workflow/layers/persistence.py)、[日志说明](https://github.com/langgenius/dify-docs/blob/main/en/cloud/use-dify/monitor/logs.mdx) | 每次运行和每一步都有状态、输入输出、耗时及失败位置，能从错例回到具体步骤。 | 记录任务 ID、步骤、状态变化、检索片段 ID、脱敏的工具参数与结果、人工确认、错误类别、耗时和 Token。日志不保存 API Key、Session Cookie 或未脱敏客户信息。 | 有离线检索逐例结果；完整运行轨迹未实现。 |
| [Langfuse 的数据集评测方法](https://langfuse.com/docs/evaluation/get-started/offline) | 固定案例集、重复实验、逐例比较，让每次修改的收益和退步都可查。 | 用同一批人工核对案例比较规则方案、单轮模型和完整 Agent；统计检索、引用、漏问、错误工具选择、任务完成、误分配、耗时和成本。初期用仓库内的 Java 测试与结果文件，不为评测单独部署 Langfuse。 | 已有关键词检索基线和挑战集；完整 Agent 对照尚未运行。 |

[OpenHands](https://github.com/OpenHands/OpenHands) 面向通用代码执行与沙箱。它的核心问题与受权限约束的汽车工单不同，因此不作为本项目的架构模板。鱼皮 `yu-ai-agent` 的 Spring AI API 用法另见[模板适配评审](yu-ai-agent-template-review.md)；它与上面四个项目的架构调研是两次不同的学习。

## 实施顺序与验收点

1. **先完善政策检索。** 基于现有模拟政策和关键词基线，接入 Embedding 与 pgvector。车型编号、门店和政策生效时间先做精确过滤，再进行语义检索；答案必须引用能支持结论的原文。用固定案例对比关键词方案，保留漏召回和误命中案例。
2. **再完成可恢复的业务流程。** 把任务状态、补充信息、建议依据和管理员确认持久化。只读工具受权限、参数、次数和超时限制；分配动作由程序控制。用“确认后重启、重复确认、工单已变化、分配超时”案例验证不会盲目重复写入。
3. **每一步都留下可定位的记录。** 从检索与只读工具开始记录步骤和结果；写入流程完成后增加确认和执行轨迹，并检查日志脱敏。
4. **最后做方案对照。** 固定案例同时跑规则、单轮模型、完整 Agent；只报告实测值，并逐例解释哪些改动确实改善了结果。

项目作者亲自掌握检索策略、状态流转、工具与写入边界，以及失败案例分析；Codex 负责文档、固定案例、测试建议和代码评审。当前下一项可交付工作是**政策语义检索与关键词基线对照**，不需要先搭完整通用 Agent 平台。
