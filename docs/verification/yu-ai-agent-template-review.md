# 鱼皮「AI 超级智能体」项目参考评审

核对日期：2026-10-28。参考的是鱼皮公开仓库 [`liyupi/yu-ai-agent`](https://github.com/liyupi/yu-ai-agent/tree/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8)，固定到提交 `6d4608c`。本文只评估公开代码；付费教程内容不在评估范围。该仓库未提供可识别的许可证文件，因此本项目只学习设计思路和框架用法，不复制其源码、语料或提示词。

## 结论

**值得学，不整套套用。**它是一个能演示 Java 21、Spring Boot、Spring AI、RAG、工具调用和 ReAct 循环的教学项目。我们的任务是处理有权限、人工确认和外部写入风险的工单，不能把通用 `YuManus` 当作业务 Agent 基类。第一版继续使用已确认的 Java 显式状态机，Spring AI 负责模型与检索接口；Graph 与自由规划仍按评测结果决定是否引入。

## 具体怎么借鉴

| 公开项目中的做法 | 本项目采用的思路 | 必须补上的工单约束 |
|---|---|---|
| [`LoveAppDocumentLoader`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/rag/LoveAppDocumentLoader.java) 加载 Markdown；[`MyTokenTextSplitter`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/rag/MyTokenTextSplitter.java) 切块 | 参考“原文加载 → 切块 → Embedding → 入库”的流水线。现有 `policy` 包已能加载、校验并切模拟政策原文 | 元数据显式写入政策 ID、版本、门店、车型编号、生效区间和来源；不能从文件名截几个字当业务字段；保留原文定位 |
| [`PgVectorVectorStoreConfig`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/rag/PgVectorVectorStoreConfig.java) 与 [`LoveAppRagCustomAdvisorFactory`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/rag/LoveAppRagCustomAdvisorFactory.java) | 参考 Spring AI 的 `PgVectorStore`、`DocumentRetriever` 与元数据过滤 API；复用框架接口，不重写向量库 | 入库与服务启动分开，按政策版本去重；车型编号先精确校验，再按门店和时间过滤，最后比较关键词、向量及可选重排；回答必须带可核对的政策引用 |
| [`ToolCallAgent`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/agent/ToolCallAgent.java) 展示模型提出工具请求、程序执行工具的流程 | 学习 `ChatClient`、`ToolCallback` 和工具结果回填的调用方式；只读工具可作为 A/B/C 对照中的 C 方案 | 只注册读工单、查政策、查顾问与工作量等白名单；程序校验参数、权限、次数和超时；分配接口永远不暴露给模型自行选择 |
| [`LoveApp`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/app/LoveApp.java) 使用 `ChatClient`、Advisor 和结构化输出 | 参考模型调用与结构化结果封装；当前 `ModelTextGenerator` 已通过 DeepSeek 单次真实短文本联通 | 模型文本不能直接当工单状态或分配命令；任务状态、证据、人工确认和审计由 Java 与本项目数据库管理 |

## 明确不照搬的部分

1. [`BaseAgent`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/agent/BaseAgent.java) 的 `IDLE/RUNNING/FINISHED/ERROR` 是内存中的通用执行状态。它没有“等客户选店”“等管理员确认”“外部结果未知”等工单状态，也没有任务重启恢复和重复确认约束。其 SSE 超时回调改变内存状态，但不能代替外部分配结果核查。
2. [`ReActAgent`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/agent/ReActAgent.java) 在单步异常后返回错误文字，循环可能继续。我们的工具失败必须成为明确状态，写入超时必须进入“结果未知”，不能让模型凭错误文字自行决定重试。
3. [`ToolRegistration`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/tools/ToolRegistration.java) 给 Agent 注册文件、下载、网页、终端等工具；这适合演示通用能力，不适合工单权限边界。第一版不引入终端执行、任意文件读写、联网搜索或 MCP。
4. 示例 RAG 把文档加入向量库的动作放在 Bean 初始化中；示例测试多为真实服务调用后只断言结果非空（见 [`LoveAppTest`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/test/java/com/yupi/yuaiagent/app/LoveAppTest.java)）。我们的入库要可重跑和可比对，评测要核对具体来源、正确拒答、状态与工具轨迹。
5. [`MyLoggerAdvisor`](https://github.com/liyupi/yu-ai-agent/blob/6d4608c58b442d470fffc7a5e0b2aedb0e9478c8/src/main/java/com/yupi/yuaiagent/advisor/MyLoggerAdvisor.java) 记录完整请求和回复。我们的审计记录任务、检索片段 ID、工具参数摘要、状态、耗时和 Token；不能把客户原文、Session 或 Key 无筛选地打到日志。

## 对当前项目的决定

- 保留 `policy`、`store`、`workflow`、`model` 的现有边界，不改成 `LoveApp + YuManus` 两套演示应用，也不复制对方 `pom.xml`：其 Spring Boot 3.4.4 / Spring AI 1.0.0 与本项目已验证的 3.5.8 / 1.1.2 不是同一版本组合。
- RAG 进入下一步时，先由项目作者确定政策元数据、精确过滤与检索对照标准；Codex 可评审实现并补固定案例。向量检索仅在评测说明收益后进入正式路径。
- 工具调用进入下一步时，先由项目作者画出只读工具与状态边界；框架负责传递工具请求，Java 状态机负责强制必经步骤、人工确认和外部分配安全检查。
- 最终面试表述是“参考鱼皮教学项目学习 Spring AI API，并为工单场景自行设计安全工作流”；不能把教学项目的功能、代码或评测结果写成自己的项目成果。
