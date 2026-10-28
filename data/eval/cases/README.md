# 固定评测案例

本目录存放**模拟工单**的人工标准答案。案例先写、先冻结，再用同一批输入比较规则流程、固定工具流程和受控模型工具选择；不要看完模型输出再改正确答案。

Codex 已起草 [C01](C01-normal-test-drive.md)、[C02](C02-nearby-store-selection.md)、[C03](C03-missing-appointment-time.md)、[C04](C04-similar-model-code.md)、[C05](C05-expired-policy.md)、[C06](C06-conflicting-policies.md)、[C09](C09-advisor-tool-failure.md) 和相应模拟政策。它们均**尚未冻结或运行**。项目作者负责复核业务标准答案，并亲自设计后续 Java 代码架构与核心实现。

每张卡都要固定 `asOf`（模拟当前时间）和 `appointmentAt`（计划试驾时间，缺失时明确写“缺失”）；政策适用区间按 `appointmentAt` 判断，不使用运行机器的当前时间。`asOf` 可以早于实际写卡日期，只要案例内的时间关系自洽。工单、顾问、客户及门店均使用虚构值，绝不粘贴真实接口返回体或凭据。

本阶段的七张卡是验收用例，不足以单独证明普遍准确率。附近门店的坐标、车辆和时段可用性是模拟结构化数据，**不进入政策 RAG**；无真实门店或车辆 API 时，不能声称查询到实时车源。后续另加未参与调试的改写问法、脏格式政策、异常与并发案例，并报告逐例结果及分子分母。

完整步骤与检查点见[阶段 0A 计划](../../../docs/superpowers/plans/2026-10-27-stage-0a-evaluation-cards.md)。
