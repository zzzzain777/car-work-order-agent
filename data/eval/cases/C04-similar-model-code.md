# C04：MX-7 与 MX-70 编号相近

> 模拟案例草稿，未冻结、未运行。

## 固定输入

- `asOf=2026-10-28T10:00:00+08:00`；`appointmentAt=2026-11-07T14:00:00+08:00`。
- 工单固定响应：`id=1004`、`type=1`、`status=0`、`advisorId=null`、`customerId=5004`、`content="已选 STORE-A，想试驾 MX-7，11 月 7 日 14 点"`。
- 可检索原文同时包含 [`POL-STORE-A-MX-7-TEST@1`](../../policies/STORE-A-MX-7-test-drive-v1.md#rule-1) 与 [`POL-STORE-A-MX-70-TEST@1`](../../policies/STORE-A-MX-70-test-drive-v1.md#rule-1)。两份都处于生效区间，编号不同，允许时段也不同。
- 模拟试驾车和时段对 `MX-7` 为“有车且空闲”；顾问列表和工作量可沿用 C01 的固定响应，但本卡只判检索和引用。

## 人工标准答案

- 车型过滤必须精确使用 `modelCode=MX-7`；允许引用 `POL-STORE-A-MX-7-TEST@1` 的 `rule-1`，不允许引用 `MX-70` 条款来解释 `MX-7`。
- 若语义检索把 `MX-70` 排在前面，仍须在返回模型前排除；不能靠提示词让模型自行忽略。
- 政策层面 14:00 可申请；只读原型可继续后续顾问步骤，确认前分配 POST 为 `0`。

**判定理由：**车型编号是业务主键条件，不是模糊的语义相似词。
