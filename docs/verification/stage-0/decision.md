# 阶段 0：当前决策记录（No-Go）

初次记录时间：2026-10-21 CST；pgvector 与 DeepSeek 真实短文本联通补充核对：2026-10-28 CST。状态：**真实分配 No-Go；第一版技术选型尚未最终锁定。** 这是阶段 0 的当前结论，未把设计或探针当作已经完成的 Agent。

| 门槛 | 状态 | 证据与下一动作 |
|---|---|---|
| Java 21 + Maven | 通过（隔离容器及主机） | [工具链实测](toolchain.md)：主机已安装 Corretto 21.0.12.1，IDEA 自带 Maven 3.9.9 与应用骨架测试通过；原有 Java 8 保留 |
| `carSystem` 接口 | 源码已核对；登录正反例部分运行通过 | [契约记录](car-system-contract.md)：5 条拟调用接口的路径、权限、字段与业务码；源码仓库有未提交修改，编码前再核对 |
| Session / 实时管理员权限 | **失败** | [联调矩阵](session-matrix.md)：测试管理员降级或封禁后，旧 Session 仍被 `/current` 和管理员接口放行。当前方案不能安全开放真实分配 |
| 同源代理、Agent Cookie 路径、CSRF | 尚未验证 | 需运行隔离代理与无业务副作用的写入探针，覆盖同源、Token、跨站和退出后请求；在此之前保持写入关闭 |
| Spring 依赖与本机接线 | 依赖、模拟 HTTP 和一次真实短文本调用通过 | [兼容性探针](compatibility.md)和[DeepSeek 接线记录](deepseek-framework.md)：Boot 3.5.8、Spring AI 1.1.2、Alibaba 1.1.2.2；真实调用使用 `deepseek-flash` |
| 模型 / Embedding 真实调用 | 聊天模型单次联通通过；Embedding 未验证 | [DeepSeek 联通记录](deepseek-framework.md)：一次模拟短文本，响应非空，输入 39、输出 40 Token；Embedding 服务尚未选择和接入 |
| PostgreSQL + pgvector | 数据库 SQL 探针通过；Java 连接未验证 | [隔离探针](pgvector-probe.md)：PostgreSQL 17.11 / pgvector 0.8.7 可创建扩展、写入及查询 1024 维向量，错误维度被拒绝；尚未接入应用 |

## 必须处理的授权缺口

当前 `carSystem` 在登录时把用户对象存入 Session；后续鉴权和 `/user/current` 读取该快照。对新建测试管理员实测：数据库中角色改为普通用户或账号改为封禁状态后，旧 Session 仍显示管理员、正常状态，并能调用管理员工作量接口。Agent 只重复请求 `/user/current` 无法弥补这个缺口。

**接入条件：**在 `carSystem` 一侧补充“每次受保护请求按最新账号状态/角色鉴权”或“角色/封禁变更时使相关旧 Session 失效”的可验证机制，并让 Agent 的确认前检查与下游分配接口使用一致的最新权限判断。修改 `carSystem` 需要单独实施；当前仓库不直接查询其数据库，也不在 Agent 中保存管理员密码或长期 Session。修复后重跑降级、封禁、退出和并发会话矩阵，全部通过才能讨论开启真实分配。

## 对后续阶段的影响

- 模拟政策语料、RAG 案例和评测标准仍可继续设计；数据库扩展探针通过不等于向量检索已接入，单次模型联通也不等于 Agent 闭环或真实分配已完成。
- Java 显式状态机、只读工具和人工确认的设计不变。真实写入在认证、CSRF、接口复核与结果未知处理都验证通过前关闭。
- 用户亲自实现 RAG 检索策略、状态图、工具/写入边界和评测核心；Codex 可继续提供样板、讲解、评审与测试建议。

## 下一轮阶段 0 验证顺序

1. 先确定并实施 `carSystem` 的最新权限判断方式，复测旧 Session 的降级/封禁行为；不对原有账号作测试性修改。
2. 用新建模拟账号跑同源代理与 CSRF 探针，补完[Session 矩阵](session-matrix.md)的未验证行。
3. DeepSeek 的一次模拟文本聊天调用已通过；另确定 Embedding 服务并实测输出维度，再验证 Java 应用连接 PostgreSQL / pgvector、元数据过滤与实际语义检索。数据库扩展和维度 SQL 探针已完成。
4. 所有硬门槛都有运行证据后，更新本记录为 Go，并锁定版本与阶段 1 实施计划。
