# 阶段 0：Java 依赖兼容性探针

检查时间：2026-10-21 CST。探针位于 `probes/stage-0/`，只检验依赖能否一起编译和加载基础类型；它不是 Agent 应用，也没有业务状态机、政策入库或分配调用。

## 候选组合与来源

| 组件 | 探针版本 | 依据 |
|---|---|---|
| JDK / Maven | 容器内 Temurin 21.0.7 / Maven 3.9.9 | [工具链实测](toolchain.md) |
| Spring Boot | 3.5.8 | [Spring Boot 发布记录](https://spring.io/blog/2025/11/20/spring-boot-3-5-8-available-now/) |
| Spring AI | 1.1.2 | [Spring AI Alibaba 1.1.2.2 源码版本组合](https://github.com/alibaba/spring-ai-alibaba/blob/main/pom.xml) |
| Spring AI Alibaba DashScope | 1.1.2.2 | [项目发布记录](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.2) |
| PGvector starter | 1.1.2 | [Spring AI 1.1.2 的 PgVectorStore API](https://docs.spring.io/spring-ai/docs/1.1.2/api/org/springframework/ai/vectorstore/pgvector/PgVectorStore.html)；编译实际解析版本见下文 |

## 可复核结果

1. **红灯：**只加入 Boot 测试依赖时，`mvn -q test` 在测试编译阶段因找不到 `ChatModel`、`EmbeddingModel`、`PgVectorStore` 和 `DashScopeChatModel` 而失败；表明测试确实会检测缺失依赖。
2. **BOM 问题：**导入 Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.2 BOM 后，Maven 仍报告 `spring-ai-alibaba-starter-dashscope` 缺版本号。故探针为该 starter 显式固定 `1.1.2.2`；后续升级必须重新核对，不能假定 BOM 管理它。
3. **绿灯：**加入 DashScope starter 和 PGvector starter 后，同一 Maven 镜像运行 `mvn -q test` 成功。Surefire 报告：1 个测试、0 失败、0 错误、0 跳过。测试同时加载 Spring AI `ChatModel`、`EmbeddingModel`、`PgVectorStore` 与阿里巴巴 `DashScopeChatModel`，并检查运行 JDK 为 21。
4. **依赖树：**`mvn dependency:tree` 解析的 Spring Boot 组件为 3.5.8；所有列出的 Spring AI 组件为 1.1.2；DashScope starter、自动配置与实现为 1.1.2.2。本次未见混入 Spring AI 2.x。完整依赖树是本地探针 `target/dependency-tree.txt`，构建产物不提交。

**结论：**这一组版本的**最小编译与类型加载已验证**。独立的[数据库 SQL 探针](pgvector-probe.md)也验证了扩展及 1024 维操作；主应用另有一次 [DeepSeek 真实短文本联通](deepseek-framework.md)。pgvector 的 Java 连接、Embedding 输出维度和语义检索行为仍未验证，因此不能把技术选型写成全面通过。

## 主项目的真实调用进展

截至 2026-10-28，千问和 Embedding 尚未真实调用；原 [DashScope YAML 模板](../../../config/application-local.yml.example)仍是未启用候选。主项目已加入 Spring AI Alibaba Agent Framework 1.1.2.2 与 Spring AI OpenAI 兼容 Starter 1.1.2；[DeepSeek 联通记录](deepseek-framework.md)证明 `deepseek-flash` 的一次真实短文本调用成功，响应模型、耗时和 Token 用量已有单次实测值。真实 Key 仅在用户被 Git 忽略的本地 YAML 中，本次测试和文档均未输出 Key。PostgreSQL + pgvector 仅通过隔离容器中的 SQL 探针，**未连接 Java 应用**；向量检索仍是阶段 0 的未通过门槛。
