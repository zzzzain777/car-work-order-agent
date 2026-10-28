# 阶段 0：工具链预检

初次检查时间：2026-10-20 23:52 CST；主机复测时间：2026-10-21 CST。此文仅记录工具链实测，业务 Agent 功能尚未实现。

| 检查项 | 命令与结果 | 结论 |
|---|---|---|
| macOS 默认 Java | `java -version`：Zulu `1.8.0_504`；`/usr/libexec/java_home -V` 仅列出 Zulu 8，路径为 `/Users/zz/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home` | 已运行验证；不能用于拟定的 Java 21 项目 |
| macOS Maven | `mvn -version`：`command not found` | 已运行验证；主机上不可直接执行 Maven |
| 隔离构建环境 | 已缓存镜像 `maven:3.9.9-eclipse-temurin-21`（arm64，镜像 ID 前缀 `3a4ab3276a08`） | 已运行验证；无须改变主机 Java 或安装 Maven |
| 容器 Java | `docker run --rm maven:3.9.9-eclipse-temurin-21 java -version`：Temurin `21.0.7` | 已运行验证 |
| 容器 Maven | 同镜像执行 `mvn -version`：Maven `3.9.9`，运行时 Java `21.0.7`，容器内 JDK 路径为 `/opt/java/openjdk` | 已运行验证；阶段 0 Maven 探针可使用该镜像 |
| PostgreSQL / pgvector | 2026-10-28 用[隔离探针](pgvector-probe.md)验证 PostgreSQL 17.11 / pgvector 0.8.7 的扩展、1024 维写入、精确查询和错误维度拒绝；主机仍无 `psql` | 数据库 SQL 能力已验证；Java 连接与语义检索未验证 |

**执行约定：**阶段 0 的 Maven 探针在上述固定镜像中运行，先将探针目录挂载到容器工作目录。产出的依赖树、测试结果须记录镜像标签和 Java 版本。容器可验证依赖兼容性，但还不能证明未来 macOS 原生运行或部署环境兼容。没有必要修改现有 `carSystem` 的 Java 8 环境。

## 主机环境复测

已从 Amazon Corretto 官方下载并用官方 SHA-256 校验安装独立的 macOS arm64 JDK 21.0.12.1，`/usr/libexec/java_home -V` 同时列出 Corretto 21 和原有 Zulu 8。IDEA 自带 Maven 3.9.9 在 Corretto 21 下运行；应用骨架已由 IDEA 启动，`GET /actuator/health` 返回 `UP`，Maven 冒烟测试通过。原有 Java 8 未被覆盖，终端默认 `java` 与全局 `mvn` 的状态不代表本项目配置。

**下一项验证门槛：**用 Java 连接隔离 PostgreSQL + pgvector 并验证元数据过滤；模型与 Embedding 的真实调用仍待本地 Key。SQL 探针通过不足以锁定完整技术组合。
