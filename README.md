# 汽车工单智能处置 Agent

目前已有[模拟附近五店可用性查询](docs/verification/stage-1/nearby-store-availability.md)和[客户本人选店流程](docs/verification/stage-1/customer-store-selection.md)：客户可从最近五家中选择模拟有车且时段空闲的门店，结果只保存在 Agent 任务中。[简历演示验收单](docs/verification/stage-1/resume-demo-acceptance.md)列出可复现步骤和证据边界。

这是独立的 Java 21 / Spring Boot 项目。目前有可启动的应用骨架、健康检查、阶段 0 技术探针、DeepSeek 的框架接线及一次真实短文本联通结果、基于模拟门店的纯 Java“最近五家”筛选、管理员确认前的纯规则判断、模拟政策向量检索，以及工单与顾问数据的**只读建议预览**。Agent 自有的[任务持久化能力](docs/verification/stage-1/durable-agent-task.md)已与[只读预览内部流程](docs/verification/stage-1/task-preview-wiring.md)接通，并有[模型结构化建议与引用核验](docs/verification/stage-1/structured-advice.md)、[模拟人工确认](docs/verification/stage-1/simulated-confirmation.md)和[本机操作页](docs/verification/stage-1/local-demo-entry.md)。`GET /api/agent/preview` 仍是独立的无任务只读接口；本机操作页使用新的任务接口，最终只调用模拟分配端。客户选店已有隔离测试及本机双身份联调；真实分配尚未完成。[第一版设计](docs/superpowers/specs/2026-10-20-car-work-order-agent-design.md)和[阶段 0 决策](docs/verification/stage-0/decision.md)记录了边界与未通过的联调门槛。

## 在 IDEA 中运行

1. 以 Maven 项目打开仓库根目录的 `pom.xml`，项目 SDK 和运行配置 JRE 都选 **JDK 21**。
2. 运行 `com.zz.agent.CarWorkOrderAgentApplication`。应用使用本机 `8091` 端口；访问 `http://localhost:8091/actuator/health`，应得到 `{"status":"UP"}`。
3. 通用设置在 `src/main/resources/application.yml`；默认不创建 AI 模型。DeepSeek 接线在 `src/main/resources/application-deepseek.yml`，需要主动启用 `deepseek` Profile。Key 只放在被 Git 忽略的 `config/application-deepseek.yml`，不用设置环境变量，也不要把 Key 写入模板、提交或聊天。其他机器克隆仓库后可从同目录的 `.example` 模板复制。从项目根目录启动时，Spring Boot 会读取 `./config/`。具体检查和真实调用命令见[DeepSeek 接线记录](docs/verification/stage-0/deepseek-framework.md)。

4. 百炼 Embedding 单独使用 `bailian-embedding` Profile：模型为 `qwen3.7-text-embedding-flash`，默认输出 1024 维。业务空间 Base URL 和 Key 只写在被 Git 忽略的 `config/application-bailian-embedding.yml`，公开模板见同名 `.example`。启用后可用 `BailianEmbeddingLiveSmokeTest` 做一次模拟短文本联通，默认测试集不会发出真实请求。该模型在本账号的免费额度页面显示 2026-11-30 到期；免费额度用完即停已开启。过期后需重新核对额度，不能把当前免费状态视为永久免费。接线和实测边界见[百炼 Embedding 记录](docs/verification/stage-1/bailian-embedding.md)。
5. 如需跑模拟政策向量检索，先在项目根目录执行 `bash probes/stage-1/start-policy-rag-db.sh`，再在 IDEA 的运行配置中把活动配置组设为 `policy-rag,bailian-embedding`。脚本会启动只绑定本机的 pgvector，并把随机生成的数据库密码写入 Git 忽略的 `config/application-policy-rag.yml`。如果没有 Docker，可参考同目录的 `.example` 模板填写自己的本地 PostgreSQL 连接。接口、请求示例与验证结果见[政策向量检索记录](docs/verification/stage-1/policy-rag.md)。
6. 同一配置组下另有本机 `GET /api/agent/preview`，根据当前管理员 Session 读取试驾工单、模拟政策和顾问工作量，返回“缺信息／需人工核对／待管理员核对”的预览结果。[只读预览联调记录](docs/verification/stage-1/read-only-advice.md)写明输入、限制和测试证据。它本身不保存任务；任务操作页的真实管理员浏览器联调见下文。
7. 任务表和提案表会在 `policy-rag` 启动时初始化。隔离数据库测试可执行 `bash probes/stage-1/java-pgvector-smoke.sh`；[任务持久化记录](docs/verification/stage-1/durable-agent-task.md)列出已覆盖的版本与隔离规则。
8. 同时启用 `policy-rag,deepseek,bailian-embedding`，从项目根目录启动应用后，在浏览器打开 `http://localhost:8091/agent-demo.html`。先在**同一个浏览器**打开 `http://localhost:8080/api/index.html` 登录本机 `carSystem` 管理员；两页都使用 `localhost`，再回演示页点击“重新检查”。Chrome 与应用内浏览器的 Cookie 不共享。页面会依次创建任务、补充信息、显示政策和顾问依据、生成模型候选并做**模拟确认**；写入要求同源 Origin 和会话绑定的 CSRF Token。需要已有的 DeepSeek、百炼和本地数据库配置；详见[操作页记录](docs/verification/stage-1/local-demo-entry.md)。
9. [模拟人工确认](docs/verification/stage-1/simulated-confirmation.md)的提案有效期 10 分钟，确认时复查管理员、工单、政策和顾问，持久化一次性尝试；模拟分配超时只复读工单并保持“结果未知”。网页接入的模拟端固定返回“拒绝”且绝不改 `carSystem`。正常成功、超时和并发结果由隔离测试验证，不能把网页操作算作真实分配联调。
10. 客户选店时，管理员创建任务后把任务链接交给**原工单客户本人**。客户在同一个浏览器登录 `carSystem` 普通客户账号，打开 `http://localhost:8091/customer-store.html`，核对工单归属后查看五家模拟门店并选择。只写 Agent 任务；需要已有的三组 Profile 和数据库配置。接口、权限、联调结果和剩余边界见[客户选店记录](docs/verification/stage-1/customer-store-selection.md)。

Java 21 + Spring Boot 3.5.8 是当前**可启动骨架**的版本；pgvector 已有[隔离 SQL 探针](docs/verification/stage-0/pgvector-probe.md)和[无 Key 的 Java 接线探针](docs/verification/stage-1/java-pgvector-probe.md)。DeepSeek 的 `deepseek-flash` 完成了一次真实短文本联通；百炼的 `qwen3.7-text-embedding-flash` 已与本地 pgvector 跑通模拟政策检索。真实管理员浏览器能读取证据并写入 Agent 任务；重新编译后的本机 `carSystem` 已通过[账号降级和封禁后的旧会话复测](docs/verification/stage-1/session-revocation-retest.md)，但该下游修改尚未提交，且工单快照与顾问门店归属等门槛未完成，真实分配继续关闭。[联调记录](docs/verification/stage-1/local-demo-entry.md)保留了旧运行版本的失败案例。

政策检索有[候选证据判断](docs/verification/stage-1/policy-evidence-gate.md)：价格、实时车源等问题不会拿模拟试驾政策作依据；同范围多份政策交人工。可选的向量检索已返回真实存储的片段并核对原文，但还没有生成答案或进行答案事实核验。

终端可用 JDK 21 运行 `mvn test` 或 `mvn spring-boot:run`；未安装全局 Maven 时，可在 IDEA 的 Maven 窗口运行相同目标。

## 离线评测

在 IDEA 运行 `PolicyRetrievalEvaluationTest`，会用固定模拟输入重放政策关键词检索，并把逐例结果写入 `target/eval/`。[草稿基线、挑战集和逐例结果](data/eval/results/README.md)已保存；挑战集目前 6/8 完全符合预期，暴露了同义问法漏召回和无依据价格问题误命中。它不是完整 Agent 的评测结果。

显式运行 `PolicyRagLiveIntegrationTest` 会用真实向量模型和本地 pgvector 重放同一组八个政策案例；当前 8/8 通过，逐例记录见[政策向量检索记录](docs/verification/stage-1/policy-rag.md)。这些案例主要验证范围筛选、拒答、冲突和引用；每个有效范围目前只有一个规则片段，不能据此证明大语料的语义排序质量，也不能当作完整 Agent 成功率。

另有[七个模拟案例的只读流程回放](docs/verification/stage-1/read-only-workflow-evaluation.md)：固定工单、政策、顾问和工作量响应，检查状态、出处、缺失信息与工具调用次数，逐例结果保存在 `data/eval/results/read-only-flow-v1.tsv`。本次暂定标准答案下为 7/7；它没有调用向量库或真实模型，不能当成完整 Agent 成绩。

## 参考项目取舍

此前对 Spring AI Alibaba、LangGraph、Dify、Langfuse 的架构调研及本项目的实施顺序见[高 Star Agent 架构借鉴](docs/verification/high-star-agent-architecture-review.md)。鱼皮公开的 `yu-ai-agent` 适合作为 Spring AI 与 RAG 的学习材料；其具体取舍另见[模板适配评审](docs/verification/yu-ai-agent-template-review.md)。
