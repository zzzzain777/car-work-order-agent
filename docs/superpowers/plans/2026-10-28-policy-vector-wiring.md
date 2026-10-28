# 政策向量接线实施计划

> 实施约定：按 `superpowers:executing-plans` 和测试先行的流程逐项验证。下方勾选项只覆盖 Codex 负责的接线准备；检索策略由项目作者亲自设计与实现。

**目标：** 在没有真实 Embedding Key 的情况下，完成模拟政策到 Spring AI 文档的映射，并验证 Java 能连接 pgvector、写入和过滤；为下一步真实语义检索留下可复跑的基础。

**架构：** 沿用现有 `PolicySourceLoader` 与 `PolicyRuleChunker`。映射器为每个规则片段生成稳定 ID、原文和业务元数据；独立探针使用固定的 1024 维测试向量验证 Java 与数据库。测试向量不代表真实语义能力。

**技术栈：** Java 21、Spring Boot 3.5.8、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2、PostgreSQL 17、pgvector 0.8.7。

**设计依据：** [第一版设计](../specs/2026-10-20-car-work-order-agent-design.md)第 4、7、8 节。

## 全局约束

- Agent 保持独立 Java 项目，不修改或直接读写 `carSystem`。
- 每个文档保留模拟标记、政策 ID、版本、来源、门店、车型和生效区间。
- 默认启动和测试不依赖 Embedding Key 或 PostgreSQL；不能在应用启动时自动重新入库。
- 第一轮使用 pgvector 精确检索，不提前加入 HNSW 或重排。
- 不记录 API Key、Session Cookie 或客户个人信息。

## 重点检查

- 同一规则的不同政策版本生成不同稳定 ID；同一版本重跑 ID 不变。
- 没有规则小标题的政策仍保留文件级引用。
- 错误车型过滤不会返回其他车型的政策。
- 数据库拒绝维度不符的向量。
- 新依赖不能破坏无数据库、无 Key 的日常启动。

---

### 任务 1：政策片段映射 — Codex

**文件：** `src/main/java/com/zz/agent/policy/PolicyVectorDocumentMapper.java`、`src/test/java/com/zz/agent/policy/PolicyVectorDocumentMapperTest.java`

**接口：** `List<Document> map(List<PolicySource> sources)`。每个 `Document` 使用规则片段 ID 推导稳定 UUID；元数据包括 `chunkId`、`policyId`、`version`、`sourceRef`、`storeId`、`modelCode`、`ruleType`、`status`、`simulated`、`effectiveFromEpoch`、`effectiveToEpoch`。

- [x] 先写测试：核对原文、引用、元数据、重跑稳定性及版本变化。
- [x] 运行测试，确认因映射器缺失而失败。
- [x] 实现最小映射器；不混入工单或客户数据。
- [x] 重跑单项及全套测试，确认通过。
- [x] 提交代码和测试。

### 任务 2：无 Key 的 Java/pgvector 探针 — Codex

**文件：** `pom.xml`、`src/test/java/com/zz/agent/policy/PolicyPgVectorProbeTest.java`、`probes/stage-1/java-pgvector-smoke.sh`、`docs/verification/stage-1/java-pgvector-probe.md`

**接口：** 手工构造 Spring AI `PgVectorStore`，用一次性数据库和固定 1024 维测试向量验证写入、精确检索、门店/车型过滤及维度约束。普通启动不创建向量库 Bean。

- [x] 先写探针测试，确认缺少 PGvector/JDBC 依赖时编译失败。
- [x] 加入 Spring AI PGvector、Spring JDBC、PostgreSQL 驱动。
- [x] 在独立临时数据库中运行探针，保存结果与失败原因，运行后清理容器。
- [x] 无 Key、无数据库运行全套测试，确认默认应用仍能启动。
- [x] 提交接线、探针和验证记录。

## 交给项目作者的下一块核心实现

在 `src/main/java/com/zz/agent/policy/PolicySemanticSearch.java` 设计检索接口和判断规则；先写 `PolicySemanticSearchTest`，再实现。具体返回类型、排序方法和“证据是否足够回答”由项目作者决定，Codex 负责评审与补充固定案例。

至少用现有 H01–H08 和旧五例验证：H01 的“试车”能找到正确试驾政策；H02 的价格问题不能拿试驾规则作答案；H03/H04/H08 排除过期或错误车型；H05 保留冲突政策并转人工。新的评测结果单独保存，不覆盖关键词检索 6/8 的旧记录。

**尚未验证：** 真实 Embedding 模型、实际语义召回、引用是否足以支持答案。无 Key 探针只证明 Java/数据库接线和元数据过滤可运行。
