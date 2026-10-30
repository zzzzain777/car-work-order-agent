# 汽车工单智能处置 Agent

这是独立的 Java 21 / Spring Boot 项目。目前有可启动的应用骨架、健康检查、阶段 0 技术探针、DeepSeek 的框架接线及一次真实短文本联通结果、基于模拟门店的纯 Java“最近五家”筛选、管理员确认前的纯规则判断，以及模拟政策的加载、校验、离线关键词基线和可选的百炼 Embedding + pgvector 只读检索。完整状态机、业务工具、客户选店和真实分配尚未实现。[第一版设计](docs/superpowers/specs/2026-10-20-car-work-order-agent-design.md)和[阶段 0 决策](docs/verification/stage-0/decision.md)记录了边界与未通过的联调门槛。

## 在 IDEA 中运行

1. 以 Maven 项目打开仓库根目录的 `pom.xml`，项目 SDK 和运行配置 JRE 都选 **JDK 21**。
2. 运行 `com.zz.agent.CarWorkOrderAgentApplication`。应用使用本机 `8091` 端口；访问 `http://localhost:8091/actuator/health`，应得到 `{"status":"UP"}`。
3. 通用设置在 `src/main/resources/application.yml`；默认不创建 AI 模型。DeepSeek 接线在 `src/main/resources/application-deepseek.yml`，需要主动启用 `deepseek` Profile。Key 只放在被 Git 忽略的 `config/application-deepseek.yml`，不用设置环境变量，也不要把 Key 写入模板、提交或聊天。其他机器克隆仓库后可从同目录的 `.example` 模板复制。从项目根目录启动时，Spring Boot 会读取 `./config/`。具体检查和真实调用命令见[DeepSeek 接线记录](docs/verification/stage-0/deepseek-framework.md)。

4. 百炼 Embedding 单独使用 `bailian-embedding` Profile：模型为 `qwen3.7-text-embedding-flash`，默认输出 1024 维。业务空间 Base URL 和 Key 只写在被 Git 忽略的 `config/application-bailian-embedding.yml`，公开模板见同名 `.example`。启用后可用 `BailianEmbeddingLiveSmokeTest` 做一次模拟短文本联通，默认测试集不会发出真实请求。该模型在本账号的免费额度页面显示 2026-11-30 到期；免费额度用完即停已开启。过期后需重新核对额度，不能把当前免费状态视为永久免费。接线和实测边界见[百炼 Embedding 记录](docs/verification/stage-1/bailian-embedding.md)。
5. 如需跑模拟政策向量检索，先在项目根目录执行 `bash probes/stage-1/start-policy-rag-db.sh`，再在 IDEA 的运行配置中把活动配置组设为 `policy-rag,bailian-embedding`。脚本会启动只绑定本机的 pgvector，并把随机生成的数据库密码写入 Git 忽略的 `config/application-policy-rag.yml`。如果没有 Docker，可参考同目录的 `.example` 模板填写自己的本地 PostgreSQL 连接。接口、请求示例与验证结果见[政策向量检索记录](docs/verification/stage-1/policy-rag.md)。

Java 21 + Spring Boot 3.5.8 是当前**可启动骨架**的版本；pgvector 已有[隔离 SQL 探针](docs/verification/stage-0/pgvector-probe.md)和[无 Key 的 Java 接线探针](docs/verification/stage-1/java-pgvector-probe.md)。DeepSeek 的 `deepseek-flash` 完成了一次真实短文本联通；百炼的 `qwen3.7-text-embedding-flash` 已与本地 pgvector 跑通模拟政策检索。登录态与真实分配仍待逐项验证。

政策检索有[候选证据判断](docs/verification/stage-1/policy-evidence-gate.md)：价格、实时车源等问题不会拿模拟试驾政策作依据；同范围多份政策交人工。可选的向量检索已返回真实存储的片段并核对原文，但还没有生成答案或进行答案事实核验。

终端可用 JDK 21 运行 `mvn test` 或 `mvn spring-boot:run`；未安装全局 Maven 时，可在 IDEA 的 Maven 窗口运行相同目标。

## 离线检索评测

在 IDEA 运行 `PolicyRetrievalEvaluationTest`，会用固定模拟输入重放政策关键词检索，并把逐例结果写入 `target/eval/`。[草稿基线、挑战集和逐例结果](data/eval/results/README.md)已保存；挑战集目前 6/8 完全符合预期，暴露了同义问法漏召回和无依据价格问题误命中。它不是完整 Agent 的评测结果。

显式运行 `PolicyRagLiveIntegrationTest` 会用真实向量模型和本地 pgvector 重放同一组八个政策案例；当前 8/8 通过，逐例记录见[政策向量检索记录](docs/verification/stage-1/policy-rag.md)。这些案例主要验证范围筛选、拒答、冲突和引用；每个有效范围目前只有一个规则片段，不能据此证明大语料的语义排序质量，也不能当作完整 Agent 成功率。

## 参考项目取舍

此前对 Spring AI Alibaba、LangGraph、Dify、Langfuse 的架构调研及本项目的实施顺序见[高 Star Agent 架构借鉴](docs/verification/high-star-agent-architecture-review.md)。鱼皮公开的 `yu-ai-agent` 适合作为 Spring AI 与 RAG 的学习材料；其具体取舍另见[模板适配评审](docs/verification/yu-ai-agent-template-review.md)。
