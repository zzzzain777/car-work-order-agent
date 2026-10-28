# 汽车工单智能处置 Agent

这是独立的 Java 21 / Spring Boot 项目。目前有可启动的应用骨架、健康检查、阶段 0 技术探针、DeepSeek 的框架接线及一次真实短文本联通结果、基于模拟门店的纯 Java“最近五家”筛选、管理员确认前的纯规则判断，以及模拟政策原文的加载、校验、元数据筛选和离线关键词检索；Embedding/pgvector 业务接入、完整状态机、业务工具、客户选店和真实分配尚未实现。[第一版设计](docs/superpowers/specs/2026-10-20-car-work-order-agent-design.md)和[阶段 0 决策](docs/verification/stage-0/decision.md)记录了边界与未通过的联调门槛。

## 在 IDEA 中运行

1. 以 Maven 项目打开仓库根目录的 `pom.xml`，项目 SDK 和运行配置 JRE 都选 **JDK 21**。
2. 运行 `com.zz.agent.CarWorkOrderAgentApplication`。应用使用本机 `8091` 端口；访问 `http://localhost:8091/actuator/health`，应得到 `{"status":"UP"}`。
3. 通用设置在 `src/main/resources/application.yml`；默认不创建 AI 模型。DeepSeek 接线在 `src/main/resources/application-deepseek.yml`，需要主动启用 `deepseek` Profile。Key 只放在被 Git 忽略的 `config/application-deepseek.yml`，不用设置环境变量，也不要把 Key 写入模板、提交或聊天。其他机器克隆仓库后可从同目录的 `.example` 模板复制。从项目根目录启动时，Spring Boot 会读取 `./config/`。具体检查和真实调用命令见[DeepSeek 接线记录](docs/verification/stage-0/deepseek-framework.md)。

Java 21 + Spring Boot 3.5.8 是当前**可启动骨架**的版本；pgvector 已有[隔离 SQL 探针](docs/verification/stage-0/pgvector-probe.md)，DeepSeek 的 `deepseek-flash` 已完成一次真实短文本联通测试；Java 向量检索、登录态与真实分配仍按阶段 0 记录逐项验证。

终端可用 JDK 21 运行 `mvn test` 或 `mvn spring-boot:run`；未安装全局 Maven 时，可在 IDEA 的 Maven 窗口运行相同目标。

## 离线检索评测

在 IDEA 运行 `PolicyRetrievalEvaluationTest`，会用固定模拟输入重放政策关键词检索，并把逐例结果写入 `target/eval/`。[草稿基线、挑战集和逐例结果](data/eval/results/README.md)已保存；挑战集目前 6/8 完全符合预期，暴露了同义问法漏召回和无依据价格问题误命中。它不是完整 Agent 的评测结果。

## 参考项目取舍

此前对 Spring AI Alibaba、LangGraph、Dify、Langfuse 的架构调研及本项目的实施顺序见[高 Star Agent 架构借鉴](docs/verification/high-star-agent-architecture-review.md)。鱼皮公开的 `yu-ai-agent` 适合作为 Spring AI 与 RAG 的学习材料；其具体取舍另见[模板适配评审](docs/verification/yu-ai-agent-template-review.md)。
