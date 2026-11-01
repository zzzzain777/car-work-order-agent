# 工单只读建议预览：实现与验证

日期：2026-11-01。本阶段把模拟政策证据接入工单读取流程，停在管理员核对前；没有任务持久化、确认按钮或分配接口。

## 实际流程

1. 从请求的 `SESSION` Cookie 中只取会话值，向本机 `carSystem` 读取 `/api/user/current`。要求 `userRole=2`、`userStatus=0`；用户身份和 Cookie 不放进模型或返回体。
2. 读取 `/api/workOrder/get?id=...`。只接受 `type=1`、`status=0`、`advisorId=null` 的试驾待分配工单。适配器只保留必要状态字段，不保存客户姓名、电话或工单正文。
3. 门店编号、车型编号和准确试驾时间由管理员明确补充；缺失或时间已过时只返回需要补充的字段，不查询政策、顾问或工作量，也不从自由文本猜测确定值。
4. 信息齐全时调用现有模拟政策检索。无依据、过期或冲突时停止。找到原文证据后，再分别读取顾问名单和工作量。只展示正常顾问；两次查询都成功后，工作量列表中缺席的顾问才显示为零。
5. 结果包含政策出处、顾问工作量快照及读取时间，始终 `assignmentEnabled=false`、`storeAffiliationVerified=false`。按处理中工单数排序后，只建议管理员优先**核对**负载最低的顾问。顾问名单没有门店字段，因此不能声称某顾问属于该门店，更不能据此分配。

入口为 `GET /api/agent/preview`，仅在 `policy-rag` 配置组启用且只监听本机。查询参数：必填 `workOrderId`，可选 `storeId`、`modelCode`、`appointmentAt`（例如 `2026-11-07T14:00:00+08:00`，URL 中的 `+` 需编码）。不提供 `POST /api/workOrder/assign` 的客户端方法。`READY_FOR_ADMIN_REVIEW` 仅表示**只读预览已备齐供管理员核对**；真正的 `AWAITING_CONFIRMATION` 要等任务持久化、版本与确认权限完成后才会出现。

## 契约与联调结果

- 2026-11-01 重新核对 `/Users/zz/carProject` 当前工作区：四个 GET 接口、字段和 `BaseResponse.code` 语义仍与[阶段 0 契约](../stage-0/car-system-contract.md)一致。当前 `LoginInterceptor` 工作区代码会按 Session 中的用户 ID 重新查询角色和状态，但该修改尚未提交；**没有凭旧 Session 重跑降级、封禁、退出矩阵**，不能据此宣称真实分配权限门槛已通过。
- 本机运行中的 `carSystem` 对无 Cookie 的 `/api/user/current` 和 `/api/workOrder/workload` 都返回 HTTP 200、业务码 `40100`。适配器同时检查 HTTP 与业务码，并把 `401xx`、`40301` 当成登录态或权限失效。
- `HttpCarSystemReadClientTest` 用本机模拟 HTTP 服务验证四个 GET、只转发 `SESSION`、HTTP 200 但业务码 `50000` 必须失败、缺失 Cookie 与封禁业务码。`ReadOnlyAdviceFlowTest` 覆盖缺准确时间、正常资料、普通用户、已分配工单、政策冲突、工作量失败、会话中途失效及零工单顾问。`ReadOnlyAdviceControllerTest` 验证缺 Cookie 返回 401、时间格式错误返回 400、预览始终不能分配。
- 已有 `PolicyRagLiveIntegrationTest` 在启用真实百炼向量模型与本地 pgvector 后通过，说明新增配置没有破坏政策检索。**尚未用真实管理员 Cookie 跑完整的工单预览**；测试用的管理员、工单和顾问响应均为模拟数据，不作为真实联调成绩。

## 下一道门槛

要把预览变成真正可恢复的 Agent 任务，还需在本仓库持久化任务与版本、明确补充信息的写入权限和 CSRF、对同源代理及 Cookie 路径做浏览器联调。要开放真实分配，还需单独验证 `carSystem` 权限撤销新鲜度、顾问门店归属、确认前复读与结果未知处理。本阶段没有跨过这些门槛。
