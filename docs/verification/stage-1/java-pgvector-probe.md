# 阶段 1：Java / pgvector 无 Key 联调

实测日期：2026-10-29 CST。目标是核对 Java 21、Spring AI 1.1.2、Spring JDBC、PostgreSQL 17 与 pgvector 0.8.7 能否一起写入和检索模拟政策片段。此探针不调用 DeepSeek 或任何 Embedding 服务，不评价语义召回质量。

运行命令：在仓库根目录执行 `bash probes/stage-1/java-pgvector-smoke.sh`。脚本创建无对外端口的临时 Docker 网络和 PostgreSQL 容器，生成一次性测试密码，运行 `PolicyPgVectorProbeTest` 后自动删除容器与网络。主应用平时启动无需数据库或 API Key。

探针使用固定的 1024 维**测试向量**，验证了：

1. `PolicyVectorDocumentMapper` 生成的模拟政策片段可由 Java 写入 pgvector；文档保留政策 ID、版本、门店、车型、生效区间及来源引用。
2. 相同文档内容能从向量表检索回来；`STORE-A` / `MX-7` 元数据过滤保留目标片段，错误车型过滤返回空结果。
3. 数据库中的向量为 1024 维，三维向量写入被拒绝；检索索引选 `NONE`，先验证精确检索。

首轮失败为 `Unknown type vector`。原因是探针手工构造 `PgVectorStore` 后没有调用 `afterPropertiesSet()`，因此虽设置了 `initializeSchema(true)`，扩展与表仍未初始化。补上生命周期调用后复跑成功；去掉额外的 JDBC 类型注册仍成功，说明此失败由**未初始化 schema**引起。实际由 Spring 管理的 Bean 会调用该生命周期方法；若以后改为手工构造，需保留显式调用。

无数据库、无 Key 的默认 `mvn -q test` 复跑：**38 个测试、0 失败、0 错误、2 个跳过**。跳过的是需要真实 DeepSeek Key 的烟测和此 pgvector 探针。新增的 JDBC 依赖会触发 Spring Boot 数据源自动配置，因此在普通配置中显式关闭了 `DataSourceAutoConfiguration`；正式 RAG/任务持久化接入时需配置专用数据源并重新审查该开关。

**未通过的门槛：**真实 Embedding 模型与输出维度、Java 应用长期数据源、可重复入库、政策语义召回、引用支持和拒答判定。此前关键词挑战集仍为 6/8，不能把本探针当作 RAG 评测结果。下一步由项目作者决定检索与证据充分性规则，再用同一批固定案例对比关键词和真实语义检索。
