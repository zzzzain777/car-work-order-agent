# 固定评测案例

本目录存放**模拟工单**的人工标准答案。案例先写、先冻结，再用同一批输入比较规则流程、固定工具流程和受控模型工具选择；不要看完模型输出再改正确答案。

Codex 已起草 [C01](C01-normal-test-drive.md)、[C02](C02-nearby-store-selection.md)、[C03](C03-missing-appointment-time.md)、[C04](C04-similar-model-code.md)、[C05](C05-expired-policy.md)、[C06](C06-conflicting-policies.md)、[C07](C07-no-applicable-policy.md)、[C09](C09-advisor-tool-failure.md) 和相应模拟政策。它们均**尚未由项目作者复核或冻结**。项目作者负责复核业务标准答案，并亲自设计后续 Java 代码架构与核心实现。C01/C04/C05/C06/C07 的政策检索部分已有单独的[草稿基线评测](../results/README.md)；这不表示对应的整条业务流程已经运行。

每张卡都要固定 `asOf`（模拟当前时间）和 `appointmentAt`（计划试驾时间，缺失时明确写“缺失”）；政策适用区间按 `appointmentAt` 判断，不使用运行机器的当前时间。`asOf` 可以早于实际写卡日期，只要案例内的时间关系自洽。工单、顾问、客户及门店均使用虚构值，绝不粘贴真实接口返回体或凭据。

本阶段的案例卡不足以单独证明普遍准确率。附近门店的坐标、车辆和时段可用性是模拟结构化数据，**不进入政策 RAG**；无真实门店或车辆 API 时，不能声称查询到实时车源。政策检索已有单独的[未参与关键词调试的挑战集](../retrieval/keyword-holdout-v1.tsv)，覆盖改写问法、无依据问题和时间边界，并保存逐例结果。脏格式政策、完整流程异常与并发案例仍待补充，不能把这组检索成绩当作任务成功率。

完整步骤与检查点见[阶段 0A 计划](../../../docs/superpowers/plans/2026-10-27-stage-0a-evaluation-cards.md)。
