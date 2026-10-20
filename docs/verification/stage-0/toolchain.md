# 阶段 0：工具链预检

检查时间：2026-10-20 23:52 CST。此文仅记录实测环境，不表示 Agent 已能启动。

| 检查项 | 命令与结果 | 结论 |
|---|---|---|
| macOS 默认 Java | `java -version`：Zulu `1.8.0_504`；`/usr/libexec/java_home -V` 仅列出 Zulu 8，路径为 `/Users/zz/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home` | 已运行验证；不能用于拟定的 Java 21 项目 |
| macOS Maven | `mvn -version`：`command not found` | 已运行验证；主机上不可直接执行 Maven |
| 隔离构建环境 | 已缓存镜像 `maven:3.9.9-eclipse-temurin-21`（arm64，镜像 ID 前缀 `3a4ab3276a08`） | 已运行验证；无须改变主机 Java 或安装 Maven |
| 容器 Java | `docker run --rm maven:3.9.9-eclipse-temurin-21 java -version`：Temurin `21.0.7` | 已运行验证 |
| 容器 Maven | 同镜像执行 `mvn -version`：Maven `3.9.9`，运行时 Java `21.0.7`，容器内 JDK 路径为 `/opt/java/openjdk` | 已运行验证；阶段 0 Maven 探针可使用该镜像 |
| PostgreSQL / pgvector | 主机无 `psql`；当前运行的 Docker 容器只有 MySQL 与 Redis；未连接 PostgreSQL 或检查 `vector` 扩展 | 尚未验证；不得声称向量库已可用 |

**执行约定：**阶段 0 的 Maven 探针在上述固定镜像中运行，先将探针目录挂载到容器工作目录。产出的依赖树、测试结果须记录镜像标签和 Java 版本。容器可验证依赖兼容性，但还不能证明未来 macOS 原生运行或部署环境兼容。没有必要修改现有 `carSystem` 的 Java 8 环境。

**下一项验证门槛：**在隔离探针项目中运行 Maven 编译和测试；另起 PostgreSQL + pgvector 的模拟环境并检查扩展与 1024 维向量操作。未完成之前，这两项仍是候选。
