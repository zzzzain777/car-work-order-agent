# 复杂模拟政策的隔离语义评测

日期：2026-11-03。[案例与预期答案](../../../data/eval/workflow/policy-semantics-v1.json)、固定提示词及评分程序先在提交 `33a126b` 中冻结，之后才调用 DeepSeek Flash。三条政策均为评测专用的编造文本，不进入正式 RAG，也不参与真实工单建议或分配。

| 案例 | 需要判断的条件 | 预期 | 模型 | 引用核对 |
| --- | --- | --- | --- | --- |
| S01 | 暂停日期是次日，本次在常规时段内 | ALLOW | ALLOW | 通过 |
| S02 | 本次日期全天暂停申请 | DENY | DENY | 通过 |
| S03 | 政策要求驾龄满一年，顾客驾龄未知 | NEEDS_INFO | NEEDS_INFO | 通过 |

[逐例记录](../../../data/eval/results/policy-semantics-live-v1.tsv)显示 **3/3**：模型返回合法 JSON，判断与预期一致，短引逐字出自政策并包含事先指定的关键证据。共调用模型 3 次，服务商报告输入 706、输出 106 Token；单次耗时为 1276、883、576 毫秒。这些数值仅是本次实测，不代表平均性能或成本。

**结论：**模型在这 3 个手工模拟的例外条件上显示了语义判断能力，但样本太小，且没有独立人工标注或规则基线的预先冻结对照，不能据此声称模型总体优于规则。现有正式 `StructuredAdviceService` 仍只核对单一每日时间窗和引用，不会使用本次的 `ALLOW`、`DENY`、`NEEDS_INFO` 输出。若接入正式流程，须先定义结构化条件与缺失事实、扩大含失败案例的评测集，并把模型判断限制为只读建议；无法验证时转人工核对。

复跑：在 Java 21 且本机已配置 DeepSeek Key 的项目根目录执行 `mvn -q -Dtest=PolicySemanticLiveEvaluationTest -Dpolicy.semantic.eval.live=true test`。输出写入 `target/eval/policy-semantics-live-v1.tsv`。默认测试不会调用在线模型。
