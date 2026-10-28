# 阶段 0：PostgreSQL / pgvector 隔离探针

实测时间：2026-10-28 CST。运行环境：本机 Docker，镜像 `pgvector/pgvector:0.8.7-pg17-bookworm`，拉取时镜像摘要 `sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d`。该标签和 `CREATE EXTENSION vector` 的用法参照 [pgvector 官方 README](https://github.com/pgvector/pgvector/blob/master/README.md)。

复跑命令：在仓库根目录运行 `bash probes/stage-0/pgvector-smoke.sh`。脚本启动无对外端口、无外网的短期容器，只用全零和单个非零值构造测试向量，退出时清理容器；不涉及客户数据、真实密钥或 Agent 业务表。

本次复跑输出：

```text
CREATE EXTENSION
postgres=17.11 (Debian 17.11-1.pgdg12+2),pgvector=0.8.7
CREATE TABLE
INSERT 0 2
rows=2,dims=1024..1024
nearest=2
DO
wrong_dimensions=rejected
```

探针检查了 `vector(1024)` 的两条写入、L2 精确最近邻结果，以及三维向量写入时的 `expected 1024 dimensions, not 3` 错误。脚本初版只用 `pg_isready` 判断就绪，会撞上容器初始化过程中即将关闭的临时 PostgreSQL；随后增加“初始化完成且正式服务可用”的条件，复跑通过。此处记录的是**数据库扩展及 SQL 行为**，不是 Java 与数据库连通性验证。

仍未验证：`text-embedding-v4` 实际输出维度、Spring AI 的 PGvector 连接和元数据过滤、真实语义召回、模型成本及性能。`config/application-local.yml` 的 API Key 目前仍是占位值，未执行外部模型调用。真实分配的权限与 CSRF 门槛也与本探针无关，继续保持 No-Go。
