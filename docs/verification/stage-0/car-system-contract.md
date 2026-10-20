# 阶段 0：`carSystem` HTTP 契约核对

检查时间：2026-10-20 CST。来源是 `/Users/zz/carProject` 的当前工作区，HEAD 为 `a5fd78c`；该仓库有未提交修改，其中包括 `WorkOrderServiceImpl.java`。因此下表是**本次源码核对**，不是稳定发布契约；编码和联调前须再次比对。未读取或修改它的数据库。

## 通用约定

- 应用配置的 `context-path` 为 `/api`，下表路径均包含此前缀。见 `src/main/resources/application.yml:34`。
- `BaseResponse<T>` 字段为 `code`、`data`、`message`、`description`；`code=0` 才是业务成功。错误枚举包括 `40000` 参数错误、`40001` 不存在、`40002` 状态冲突、`40100` 未登录、`40101` 无权限、`40102` 角色不匹配、`40301` 禁止操作、`50000` 系统错误。见 `common/BaseResponse.java`、`common/ErrorCode.java`。
- `GlobalExceptionHandler` 把业务异常直接返回 `BaseResponse`，未设置 HTTP 错误状态。客户端必须同时检查 HTTP 传输结果和 `BaseResponse.code`。源码见 `exception/GlobalExceptionHandler.java:22`。
- 除登录、注册等放行路径，`LoginInterceptor` 从 Session 中读取 `USER_LOGIN_STATE`。管理员限定接口另由 `@AuthRole(UserRoleEnum.ADMIN)` 检查。见 `config/WebMvcConfig.java:39`、`interceptor/LoginInterceptor.java:33`、`aop/AuthRoleAspect.java:32`。

## 本项目拟调用的接口

| 方法与路径 | 请求 / `data` 形状 | 权限、业务条件与源码证据 | 本次结论 |
|---|---|---|---|
| `GET /api/user/current` | 无参数；`UserVO`，含 `id`、`userRole`、`userStatus` 等字段 | 已登录；`UserController.java:88`。Agent 只应提取身份和状态，不保存电话、邮箱等无关字段 | 源码已核对；无凭据负例已运行 |
| `GET /api/workOrder/get?id={id}` | `id: long`；`WorkOrderVO` 含 `id`、`title`、`type`、`content`、`status`、`advisorId`、`updateTime` 等 | 已登录；服务按当前 Session 角色限制可见范围，管理员可看全部；`WorkOrderController.java:135`、`WorkOrderServiceImpl.java:214,470` | 源码已核对；管理员实测尚未验证 |
| `GET /api/user/list/advisor` | 无参数；`List<UserVO>` | 仅管理员；查询 `userRole=1`，源码未过滤 `userStatus`；`UserController.java:99`、`UserServiceImpl.java:180` | 源码已核对；运行尚未验证 |
| `GET /api/workOrder/workload` | 无参数；`List<AdvisorWorkloadVO>`，每项含 `advisorId`、`advisorName`、`processing`、`finished`、`closed`、`rejected`、`total` | 仅管理员；`WorkOrderController.java:142`、`WorkOrderServiceImpl.java:428` | 源码已核对；无凭据负例已运行 |
| `POST /api/workOrder/assign` | JSON 仅含 `id: Long`、`advisorId: Long`；`Boolean` | 仅管理员；顾问 ID 必须存在且角色为顾问，工单状态流转用 `id+status` 条件更新；无幂等键或工单版本入参。见 `WorkOrderController.java:77`、`model/request/WorkOrderAssignRequest.java`、`WorkOrderServiceImpl.java:130,515` | 仅源码已核对；**未发出真实 POST** |

`WorkOrderVO` 的试驾类型与待分配状态须在 Agent 中按设计文档校验，不能因下游分配接口只检查状态，就把其他类型工单当试驾任务。下游条件更新只防状态竞争，不能保证确认前读取的标题、内容、更新时间在分配时仍相同。

## 已发现的契约风险

1. **角色或封禁变化可能滞留在旧 Session。** `UserServiceImpl.java:134` 登录时把脱敏 `User` 放进 Session；`LoginInterceptor.java:33` 从 Session 取该对象；`UserController.java:90` 的 `/current` 返回该对象的 `UserVO`。`updateUserRole` 更新数据库，但源码中没有同步刷新其他已登录 Session。因此“确认前再调 `/current`”只能重新校验**这份 Session 快照**，尚不能证明取到了当前数据库角色或封禁状态。这是源码推断，运行联调尚未验证。真实分配必须保持关闭，直到认证方案能确认最新权限，或明确补齐下游契约；本仓库不得自行读 `carSystem` 数据库。
2. **零工单顾问不在工作量结果里。** `WorkOrderMapper.xml:29` 从 `work_order` 出发并按已有顾问工单分组；未出现的顾问不能直接解释为“查询失败”，也不能只看工作量列表就认为没有这名顾问。Agent 应先取顾问名单，再按 ID 合并工作量；仅在两次查询都成功时把缺席记录解释为零。此处为源码推断，运行联调尚未验证。
3. **顾问名单未排除封禁账户。** `listAdvisors()` 只按角色筛选；`assignWorkOrder()` 的 `isAdvisor()` 也只检查角色。Agent 候选集须同时要求 `userStatus=0`，确认前重新核对；下游是否会接受被封禁顾问仍需联调说明。
4. **接口数据可能含个人信息。** `UserVO` 有电话、邮箱，`WorkOrderVO` 有客户姓名和内容。Agent 只保留完成任务所需字段，测试记录、模型输入和审计不得原样复制返回体。

## 无凭据运行负例

本机 `http://localhost:8080/api/health/ping` 返回 HTTP `200`。不带 Cookie 发 `GET /api/user/current` 和 `GET /api/workOrder/workload`，两者均返回 HTTP `200`，响应体 `code=40100`、`data=null`。只记录了状态与业务码，未记录 `Set-Cookie`。管理员、普通用户、过期 Session 与角色变更后的运行结果仍属于阶段 0 后续联调。

## 接入边界

Agent 只通过这些已授权 HTTP API 读写；模拟门店、车型、试驾政策由 Agent 自己管理。`POST /api/workOrder/assign` 在管理员确认、任务版本检查、实时权限与工单复核通过前禁止调用；超时后先复读工单，不自动重发。
