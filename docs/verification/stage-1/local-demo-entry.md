# 本机任务操作页与接口

日期：2026-11-02。操作页在 `http://localhost:8091/agent-demo.html`。只有同时启用 `policy-rag,deepseek,bailian-embedding`，任务接口才注册；应用仍只监听本机。页面和任务接口会读取真实 `carSystem` 的**只读**工单及顾问数据，使用本项目虚构的门店政策，调用已配置的 DeepSeek 生成受校验的候选。批准按钮只进入固定返回“拒绝”的模拟端，不发送真实分配请求。

## 使用顺序

1. 启动本机 `carSystem`，在**准备打开演示页的同一个浏览器**访问 `http://localhost:8080/api/index.html` 并登录管理员，再返回演示页点击“重新检查”。必须全程使用 `localhost`；现有 Session Cookie 的 Domain 为 `localhost`、Path 为 `/api`。Chrome 与应用内浏览器的 Cookie 不共享。
2. 在项目根目录执行 `bash probes/stage-1/start-policy-rag-db.sh`，确认 Git 忽略的 DeepSeek 与百炼配置已就绪，再用 JDK 21 启用上述三个 Profile 启动 Agent。
3. 打开页面，先检查管理员会话，再填写一条待分配试驾工单编号。补充门店、车型与准确时间后，刷新证据和建议；有有效提案时才显示确认按钮。示例模拟政策为 `STORE-A`、`MX-7`、2026-11-07 14:00（北京时间），此时间只在其有效期内可用。
4. 点击批准会得到模拟端的确定拒绝，不修改真实工单。拒绝建议也不调用模拟端。若页面显示版本冲突，应重新读取任务；当前页面尚未提供刷新后恢复提案的操作。

## 安全边界和验证

任务写入只在显式 Profile 下注册。`GET /api/agent/tasks/csrf` 先检查当前管理员 Session，再签发 20 分钟有效、绑定该 Session 的令牌；该配置下的写请求统一通过本机同源 `Origin` 和 `X-Agent-CSRF` 校验，只有纯检索 `POST /api/policies/search` 例外。令牌和 Cookie 不入任务表或模型输入。控制器仍按任务归属和版本调用现有流程，浏览器页面使用文本节点展示外部数据。

`AgentTaskCsrfTest` 覆盖跨源、无令牌、篡改、不同 Session、过期、编码路径和只读豁免；`AgentTaskControllerTest` 覆盖无 Cookie、创建、旧版本冲突、发布提案后返回最新版本、模拟决定以及缺 Origin 拦截。Java 21 容器的默认 Maven 测试为 106 例、失败 0、错误 0、跳过 28；另外显式运行临时 PostgreSQL 探针 23 例、失败 0、错误 0。使用本机配置启动三个 Profile 后，已观察到健康接口和页面为 HTTP 200、无 Cookie 的令牌接口为 HTTP 401、无令牌的写入口为 HTTP 403；这证明路由与安全拦截已装配，**不证明管理员登录后的浏览器全链路已联调**。

`carSystem` 的 `/user/current` 仍可能返回旧 Session 权限快照；同源 Cookie 转发与 CSRF 的真实管理员浏览器矩阵尚未逐项验证。当前工单 DTO 也没有标题和内容快照，顾问门店归属与车源信息未核实。真实分配继续关闭；网页结果只能标为模拟结果。

2026-11-02 复核本机入口：`http://localhost:8080/` 返回 404，实际登录页 `http://localhost:8080/api/index.html` 返回 200；Chrome 与应用内浏览器分别访问 `/api/user/current` 都显示未登录。演示页现已直接链接正确登录页，并在同一个应用内浏览器打开了登录标签页。此时尚无管理员会话，不能把页面上的 401 视为联调通过；待管理员本人登录后再跑 Cookie、权限和 CSRF 矩阵。修正后的页面重新打包并启动，浏览器已看到正确链接。
