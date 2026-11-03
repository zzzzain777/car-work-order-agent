# 预先冻结案例的规则与 Agent 对照

日期：2026-11-03。先在提交 `ea4e251` 冻结[五道模拟案例](../../../data/eval/workflow/structured-advice-blind-v1.json)、预期答案、规则基线代码及[基线逐例结果](../../../data/eval/results/structured-advice-blind-baseline-v1.tsv)，之后才运行 DeepSeek。输入和政策语料的 SHA-256 已写在结果文件中；对照时校验两侧哈希一致。冻结后只修正了对照报告中模型快照的文件名显示，没有修改案例、答案或规则判断。

| 案例 | 检查点 | 规则 | Agent |
| --- | --- | --- | --- |
| B01 | 10:00 起点含 | 候选 301 | 候选 301 |
| B02 | 17:00 终点不含 | 人工核对 | 人工核对 |
| B03 | 16:59:59 且同店另有改期片段 | 候选 301 | 候选 301 |
| B04 | UTC 09:00 等于上海 17:00 | 人工核对 | 人工核对 |
| B05 | 改期规则排在首位 | 人工核对 | 人工核对 |

[真实模型逐例记录](../../../data/eval/results/structured-advice-blind-live-v1.tsv)和[规则与 Agent 对照](../../../data/eval/results/structured-advice-blind-ab-v1.tsv)显示：两边均为 **5/5**，状态、顾问编号、引用来源逐例一致。Agent 共调用模型 2 次，服务商报告输入 741 Token、输出 112 Token；另 3 例在模型调用前停止。两次调用的建议服务耗时分别为 1390、900 毫秒。这些是一次小样本实测，不是平均延迟或成本估计。

**结论与边界：**本组测试没有发现模型比简单规则更好的业务结果。它只覆盖已知模拟政策的单一每日时间窗、固定的两位顾问和已选定的政策片段；没有评测检索召回、复杂政策语义、真实业务系统、真实车源或分配执行。案例虽在模型运行前冻结，仍由项目开发者依据现有语料手工设计，不能当成独立随机测试集。简历中不能据此声称模型提高了准确率。下一轮若要证明模型价值，应先确定确实不能由这些规则直接解决的任务，再由独立答案评测；否则应缩小模型职责。

复跑使用 Java 21。基线：`mvn -q -Dtest=StructuredAdviceRuleBaselineEvaluationTest#freezeBlindBaselineBeforeModelRun test`。模型切片：`mvn -q -Dtest=StructuredAdviceLiveEvaluationTest -Dadvice.eval.live=true -Dadvice.eval.input=data/eval/workflow/structured-advice-blind-v1.json -Dadvice.eval.output=target/eval/structured-advice-blind-live-v1.tsv test`。对照：`mvn -q -Dtest=StructuredAdviceRuleBaselineEvaluationTest#compareRuleBaselineWithFixedModelCases -Dadvice.ab.input=data/eval/workflow/structured-advice-blind-v1.json -Dadvice.ab.model-result=data/eval/results/structured-advice-blind-live-v1.tsv -Dadvice.ab.output=target/eval/structured-advice-blind-ab-v1.tsv test`。模型步骤需本机已配置的 Key；其余步骤不访问模型。所有案例均为模拟数据，不调用 carSystem、向量库或分配接口。
