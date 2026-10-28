# 阶段 0：Spring AI Alibaba 与 DeepSeek 接线

核对时间：2026-10-28 CST。DeepSeek 的直连 API 使用 OpenAI 兼容协议；[Spring AI Alibaba 官方接入说明](https://github.com/spring-ai-alibaba/website/blob/main/integration/chatmodels/openai-compatible.md)也要求用 Spring AI 的 `spring-ai-starter-model-openai` 接入这类模型。当前主项目引入 Spring AI Alibaba Agent Framework 1.1.2.2 和 Spring AI 1.1.2；实际 HTTP 模型适配由 Spring AI 的 OpenAI 兼容 Starter 承担。DeepSeek 官方给出的服务地址是 `https://api.deepseek.com`，当前示例模型名为 `deepseek-flash`：[DeepSeek API 文档](https://api-docs.deepseek.com/quick_start/agent_integrations/)。

主项目的 `ModelTextGenerator` 通过自动配置的 `ChatClient.Builder` 调用模型并保留 `ChatResponse` 元数据。它只在 `deepseek` Profile 中存在，没有公开 HTTP 调用入口，也没有给模型注册业务工具。第一版工单状态仍由 Java 显式状态机负责；Spring AI Alibaba 的 Agent Framework 依赖已在主项目中，但 ReactAgent / Graph 尚未用于业务流程。

## 无 Key 可复跑的检查

运行 `mvn -q test`：默认 Profile 没有真实 Key，聊天、Embedding、图像、审核及音频模型均不创建；应用健康检查仍能启动。`DeepSeekFrameworkIntegrationTest` 在本机启动模拟 HTTP 服务，用临时测试 Key 核对 `POST /chat/completions`、Bearer 头、`deepseek-flash` 请求体以及框架解析的响应。它**没有访问 DeepSeek，也不证明真实模型可用**。

`mvn dependency:tree` 核对到 Spring AI 模块均为 1.1.2，Spring AI Alibaba Agent Framework / Graph Core 均为 1.1.2.2。此结论是依赖解析和本地接线结果，不是外部 API 兼容性实测。

## 真实调用记录

2026-10-28 CST，在项目根目录用 JDK 21 执行 `mvn -q -Dtest=DeepSeekLiveSmokeTest -Ddeepseek.live=true test`，仅发送一条模拟短文本，通过 Spring AI `ChatClient` 收到非空响应。实测响应模型 `deepseek-flash`，调用耗时 1040 ms，输入 39 Token、输出 40 Token；Surefire 报告为 1 个测试、0 失败、0 错误、0 跳过。耗时只计模型调用，不含 Spring 启动和 Maven 构建。这是单次联通结果，不是延迟、稳定性或成本基准；未读取账单，也未推算实际扣费。

按 [DeepSeek 官方价目表](https://api-docs.deepseek.com/quick_start/pricing/)，`deepseek-flash` 比 `deepseek-v4-pro` 便宜，当前配置继续使用 Flash。Key 只存于被 Git 忽略的 `config/application-deepseek.yml`，测试输出与本记录均不包含 Key。其他机器克隆后可从 `config/application-deepseek.yml.example` 复制；真实调用测试仍需显式指定 `-Ddeepseek.live=true`，默认测试集不会发出付费请求。

此接入只涵盖聊天模型。`spring.ai.model.embedding=none`，没有用 DeepSeek Key 生成向量；政策语义检索和 pgvector Java 连接仍待单独实现与验证。也不能从这一条模型调用推断工具调用、引用正确或工单 Agent 闭环完成。
