# 汽车工单智能处置 Agent

这是独立的 Java 21 / Spring Boot 项目。目前有可启动的应用骨架、健康检查、阶段 0 技术探针、基于模拟门店的纯 Java“最近五家”筛选、管理员确认前的纯规则判断，以及模拟政策原文的加载与元数据校验；完整状态机、政策检索与 Embedding、业务工具、客户选店和真实分配尚未实现。[第一版设计](docs/superpowers/specs/2026-10-20-car-work-order-agent-design.md)和[阶段 0 决策](docs/verification/stage-0/decision.md)记录了边界与未通过的联调门槛。

## 在 IDEA 中运行

1. 以 Maven 项目打开仓库根目录的 `pom.xml`，项目 SDK 和运行配置 JRE 都选 **JDK 21**。
2. 运行 `com.zz.agent.CarWorkOrderAgentApplication`。应用使用本机 `8091` 端口；访问 `http://localhost:8091/actuator/health`，应得到 `{"status":"UP"}`。
3. 通用设置在 `src/main/resources/application.yml`。仅本机使用的 `config/application-local.yml` 被 Git 忽略，初始内容来自同目录 `.example` 模板；从项目根目录启动时，Spring Boot 会读取 `./config/`。将来需要调用模型时，由你在本地文件中填写 Key；不要写入模板、提交或聊天。这个外部文件不会被 Maven 打进 JAR。当前应用没有加入 AI/数据库自动配置依赖，因此填写 Key 本身不会启动真实模型调用。

Java 21 + Spring Boot 3.5.8 是当前**可启动骨架**的版本；AI、PGvector、登录态与真实分配仍按阶段 0 记录逐项验证，不能视为已经完成。

终端可用 JDK 21 运行 `mvn test` 或 `mvn spring-boot:run`；未安装全局 Maven 时，可在 IDEA 的 Maven 窗口运行相同目标。
