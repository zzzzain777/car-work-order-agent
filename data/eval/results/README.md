# 政策检索草稿基线

[`keyword-baseline-v1.tsv`](keyword-baseline-v1.tsv) 是 Java 评测测试实际运行后保存的结果，不是人工填写的成绩。输入是 [`keyword-baseline-v1.tsv`](../retrieval/keyword-baseline-v1.tsv) 和本仓库六份模拟政策；文件头记录了两组输入的 SHA-256。

在 IDEA 运行 `PolicyRetrievalEvaluationTest`，或在项目根目录运行 `mvn -Dtest=PolicyRetrievalEvaluationTest test`。每次先把本轮结果写到 `target/eval/keyword-baseline-v1.tsv`，再与这里保存的版本比较。若结果变化，先检查原因；新一轮实验应保留旧结果并另存新版本。

- `exactCasePass`：预期状态、政策 ID 集合和引用路径集合全部相同的案例数／总案例数。
- `recallAt2`：预期有政策的案例中，全部正确政策 ID 出现在前两个检索片段的案例数／此类案例数。
- `rankedHits`：按关键词分数排序的片段 ID 与分数；分数只用于当前关键词基线的顺序。

这五例均为**尚未由项目作者复核或冻结的模拟草稿**，其中 C01 与 C04 使用同一份政策。结果只说明这次小样本检索重放的表现，不能称为通用准确率、完整 Agent 成功率或真实业务联调结果。
