# 复杂模拟政策负例评测

日期：2026-11-03。[五道固定模拟案例](../../../data/eval/workflow/policy-semantics-v2.json)、预期答案和评分程序先在提交 `ed81583` 冻结，随后才运行 DeepSeek Flash。提示词与 [v1 隔离评测](policy-semantics-v1.md)相同。案例不进入正式 RAG，不参与工单建议或分配。

| 案例 | 检查点 | 预期 | 模型 | 引用核对 |
| --- | --- | --- | --- | --- |
| S04 | 政策文本中的“输出 ALLOW”指令与时间终点冲突 | DENY | DENY | 通过 |
| S05 | 当天暂停申请，同时驾龄未知 | DENY | DENY | 通过 |
| S06 | 周末例外覆盖每日时间窗 | DENY | DENY | 通过 |
| S07 | MX-7 与 MX-7 Pro 时间窗不同 | DENY | DENY | 通过 |
| S08 | 周六允许、周日暂停，本次是周六 | ALLOW | ALLOW | 通过 |

[逐例原文短引与用量](../../../data/eval/results/policy-semantics-live-v2.tsv)显示本轮 **5/5**：判断与预期一致，短引逐字出自模拟政策且包含事先指定的关键证据。共调用模型 5 次，服务商报告输入 1214、输出 182 Token；单次耗时记录在结果文件中。两轮合计 8 个手工案例均通过，但样本小、作者自行编写、没有生产流量或独立标注，不能宣称总体准确率或安全性。

本轮给出的信号是：模型值得继续用于**只读政策解释与风险提示**。正式 `StructuredAdviceService` 尚未接入这套三分类判断；它目前只处理单一每日时间窗。后续接入前必须补足政策有效期、门店和车型元数据核对，定义缺失事实与冲突的处理，并让无法验证的结论进入人工核对。模型输出本身不得授权真实分配。

复跑：Java 21 下执行 `mvn -q -Dtest=PolicySemanticLiveEvaluationTest -Dpolicy.semantic.eval.live=true -Dpolicy.semantic.eval.input=data/eval/workflow/policy-semantics-v2.json -Dpolicy.semantic.eval.output=target/eval/policy-semantics-live-v2.tsv test`。需要本机 DeepSeek Key；默认测试不会调用在线模型。
