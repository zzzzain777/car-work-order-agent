# 本机任务演示入口实施计划

依据：[入口设计](../specs/2026-11-02-local-demo-entry-design.md)。用户已多次要求 Codex 直接完成非核心页面与接线；本计划直接实施。

## 文件职责

- `AgentTaskCsrf.java`：签发和校验绑定 Session 的短时令牌与本机同源 Origin。
- `AgentTaskCsrfFilter.java`：覆盖任务路径的全部写请求。
- `AgentTaskController.java`：创建、补充、读取、建议、模拟确认；把现有内部结果映射成 HTTP 响应。
- `PolicyRagConfiguration.java`：仅在相应 Profile 下创建确认存储和模拟端。
- `static/agent-demo.html`：顺序操作页，清楚标注模拟边界。
- 测试：CSRF 与路由行为、默认配置下的关闭状态、隔离数据库流程。

## 任务 1：入口安全

先写失败测试：缺 Cookie、跨源、缺令牌、篡改令牌和过期令牌拒绝写入；同源有效令牌允许。再实现 CSRF 服务与过滤器，运行对应测试。

## 任务 2：任务路由和接线

先写失败测试：创建、补充、建议、拒绝、模拟批准均调用现有业务流程；陈旧版本和无权限得到明确状态。再实现控制器与 Profile Bean。模拟端固定拒绝，不触碰 `carSystem` 写接口。运行控制器测试和隔离数据库探针。

## 任务 3：可操作页面与说明

加入简单页面，读取令牌、显示任务版本/引用/提案有效期，并顺序调用接口；更新 README 运行步骤和模拟边界。检查页面在浏览器中的状态与错误展示。最后运行完整 Maven 默认测试、隔离数据库探针、`git diff --check`。
