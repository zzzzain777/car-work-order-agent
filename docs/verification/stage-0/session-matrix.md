# 阶段 0：管理员 Session、权限与 CSRF 联调矩阵

检查时间：2026-10-21 CST。只使用本机 `carSystem:8080` 和新建的两个模拟测试账号；用户已授权创建测试账号。未调用分配接口。测试口令、Cookie 值与响应中的个人资料均未进入本仓库。测试中临时改变过**新建管理员测试账号**的角色和状态，退出脚本已恢复为 `userRole=2`、`userStatus=0`，并从数据库复核。

## 登录 Cookie

管理员测试账号登录后，响应设置 `SESSION` Cookie；属性为 `Domain=localhost`、`Path=/api`、`HttpOnly`、`SameSite=Lax`。以下只记录属性，不记录值。浏览器是否经未来同源反向代理把它送到 `/api/agent/**` 尚未验证。

## 已运行的请求矩阵

| 场景 | 实际请求与观测 | Agent 判定 / 结果 |
|---|---|---|
| 无 Cookie | `GET /api/user/current`、`GET /api/workOrder/workload` 均 HTTP 200，业务 `code=40100` | 必须拒绝；不能只看 HTTP 200。已验证 |
| 新建管理员正常登录 | 登录及 `GET /api/user/current` 为 `code=0`、`userRole=2`、`userStatus=0`；`GET /api/workOrder/workload` 为 `code=0` | 可进入只读评估。已验证 |
| 新建普通用户正常登录 | 登录及 `GET /api/user/current` 为 `code=0`、`userRole=0`、`userStatus=0`；工作量接口为 `code=40102` | Agent 必须拒绝管理员动作。已验证 |
| Cookie 主机名 | 同一登录 Cookie 对 `http://localhost:8080/api/user/current` 返回 `code=0`；对 `http://127.0.0.1:8080/api/user/current` 返回 `code=40100` | 代理与浏览器必须统一使用 `localhost`。已验证 |
| 管理员在数据库中被降为普通用户，但旧 Session 未退出 | `/user/current` 仍返回 `userRole=2`，工作量接口仍 `code=0` | **当前方案不满足实时权限撤销。已验证失败；真实分配禁止开放。** |
| 管理员在数据库中被封禁，但旧 Session 未退出 | `/user/current` 仍返回 `userStatus=0`，工作量接口仍 `code=0` | **当前方案不满足实时封禁。已验证失败；真实分配禁止开放。** |
| 退出登录 | `POST /api/user/logout` 返回 `code=0`；随后持原 Cookie 调 `/user/current` 返回 `code=40100` | 已验证；旧会话不能继续用于 Agent。 |

以上状态变化只针对临时模拟账号，测试后数据库中的管理员角色和正常状态已恢复。脚本运行未打印 Cookie 或口令。

## 尚未验证

- 24 小时 Session 自然过期后的行为，及同时登录多个设备时的权限撤销。
- 同源代理入口下，Cookie 到 `/api/agent/**` 的浏览器行为。当前只验证了 `localhost` 与 `127.0.0.1` 的主机名差异和 `Path=/api` 属性。
- Agent 写入口的 CSRF：有效 Token、缺失/错误/重放 Token、跨站 `Origin`、退出后旧 Token。后续只对**无业务副作用的探针**执行，不碰真实分配。
- 角色变更后 `carSystem` 是否会主动使其他 Session 失效；本次实测说明当前本机运行实例没有做到。

## 阶段结论

`/user/current` 当前只能证明 Session 中保存的角色和状态，不能证明它们仍是数据库中的最新值。即便 Agent 在确认前再次调用该接口，也无法挡住已降级或被封禁管理员的旧 Session。需由 `carSystem` 提供实时授权判断或旧 Session 失效机制，且用相同矩阵实测通过后，才可开启 Agent 的真实分配。此限制已回写到[第一版设计](../../superpowers/specs/2026-10-20-car-work-order-agent-design.md)。
