# 真实模型结构化建议切片评测

日期：2026-11-03。先固定[三道模拟案例](../../../data/eval/workflow/structured-advice-live-v1.json)，再用本机配置的 `deepseek-flash` 运行 `StructuredAdviceService`；[逐例实测结果](../../../data/eval/results/structured-advice-live-v1.tsv)保留输入和政策语料 SHA-256、提示词版本、结果、来源、模型调用次数、Token 与耗时。没有读取真实工单、调用 pgvector、确认提案或执行分配。案例标准答案由 Codex 起草，尚待项目作者复核。

保存结果时将语料哈希的计算范围校正为实际加载的六份政策文件，排除目录模板；模型输入与逐例运行数字未改。

| 案例 | 预期与实测 | 模型调用 | 输入 / 输出 Token | 耗时 |
| --- | --- | ---: | ---: | ---: |
| A01：单条申请时间规则 | 候选顾问 301，引用 `STORE-A #rule-1`，符合预期 | 1 | 404 / 429 | 2809 ms |
| A02：同店申请时间与改期两片段 | 只引用 `STORE-C #rule-1`，符合预期 | 1 | 385 / 264 | 1859 ms |
| A03：故意把改期片段排首位 | 转人工，不给顾问或引用，符合安全预期 | 2 | 743 / 6362 | 38246 ms |

本轮为 **2/2 个应出建议案例正确、1/1 个错误首位片段案例安全停止**。A03 是人为构造的排序错误，并不说明真实 pgvector 本轮排错；它说明当前“仅看首位片段”会在这种错误下停止，且失败重试的代价明显：两次模型调用共报告 6362 个输出 Token、约 38 秒。这里的耗时只计建议服务调用，不含 Maven 或 Spring 启动。Token 是服务商响应的用量，不区分可见文本与可能的内部推理；没有账单，不能换算费用。

这只评测**模型建议与程序校验这一段**。政策检索、工具选择、状态恢复、权限和人工确认各有独立验证，不能将分段成绩合并为完整 Agent 成功率。三例也不足以推断稳定性或平均延迟；模型输出可能随服务端版本变化，复跑时保留新结果，不覆盖这份记录。

v1 是提交 `0a4866c` 时的历史结果。当前运行器已改为生成 v2，仍保留旧输入和结果用于比较。

## 输出预算控制后的复测

仍用同一批模拟政策与业务输入，新版[固定预期](../../../data/eval/workflow/structured-advice-live-v2.json)增加模型调用次数断言；[逐例结果](../../../data/eval/results/structured-advice-live-v2.tsv)单独保存。Java 在模型调用前检查首位政策片段是否含申请时间范围；缺失时转人工。DeepSeek 配置为 `reasoning_effort=none`、每次 `max_tokens=512`。本机假 HTTP 服务已核对实际请求带有这两个参数。DeepSeek [官方文档](https://api-docs.deepseek.com/guides/thinking_mode/)说明思考模式默认开启；[接口文档](https://api-docs.deepseek.com/api/create-chat-completion/)说明 `max_tokens` 控制生成上限。项目所用 Spring AI 1.1.2 的本地配置元数据确认 `spring.ai.openai.chat.options.reasoning-effort` 和 `max-tokens` 两个键。

| 案例 | v1 模型调用 / 输出 Token / 耗时 | v2 模型调用 / 输出 Token / 耗时 | v2 结果 |
| --- | --- | --- | --- |
| A01 正常政策 | 1 / 429 / 2809 ms | 1 / 56 / 1440 ms | 候选与引用正确 |
| A02 同店两片段 | 1 / 264 / 1859 ms | 1 / 61 / 764 ms | 只引用申请时间片段 |
| A03 错误首位片段 | 2 / 6362 / 38246 ms | 0 / 0 / 0 ms | 直接转人工 |

v2 为 **3/3 符合暂定预期**。A03 的 0 毫秒是毫秒计时粒度下的记录，表示本次建议校验不足 1 毫秒；0 Token 是确实未调用模型。正常案例的耗时和 Token 是各一次调用，模型有波动，不能据此声称平均性能提升，也不能把这个切片成绩当作完整 Agent 准确率。限长可能使复杂响应截断并转人工，后续须继续观察误拒绝。

复跑：使用 Java 21、Git 忽略的本地 `config/application-deepseek.yml` 中的 Key，从仓库根目录执行 `mvn -q -Dtest=StructuredAdviceLiveEvaluationTest -Dadvice.eval.live=true test`。默认测试集跳过真实调用；显式运行后看 `target/eval/structured-advice-live-v2.tsv` 的逐例 `pass`。测试退出成功只表示评测器完成运行，不保存模型原文或凭据。
