# 模拟政策语料

这里存放**为本项目虚构的**车型、试驾规则和门店政策。它们不代表真实车企或门店规定，不包含真实客户资料。动态工单和顾问工作量来自 `carSystem` API，不放入本目录。

## 写第一份政策

复制 [`_template.md`](_template.md) 为一个新文件，建议命名为 `STORE-A-MX-7-test-drive-v1.md`。Codex 起草模拟规则正文和适用条件，项目作者复核业务含义；模板本身不参与入库。

- `policyId` 是跨版本不变的规则编号；修改规则时新建文件并递增 `version`，不要覆盖旧原文。
- `storeId` 与 `modelCode` 使用明确编号，例如 `STORE-A`、`MX-7`。`MX-7` 和 `MX-70` 是不同车型，不能仅凭向量相似度互相代替。
- `effectiveFrom` 和 `effectiveTo` 使用带 `+08:00` 时区的时间。适用区间为 `[effectiveFrom, effectiveTo)`，到结束时间即失效。
- `sourceRef` 写本仓库可打开的文件路径和条款标题；建议正文每条规则单独一个标题，保留规则及条件的完整原文。
- `isSimulated` 固定为 `true`。`contentHash`、`ingestedAt` 等入库结果由后续程序生成，不由作者手填。

第一批语料已起草六份**模拟原文**：`STORE-A` 的 `MX-7` 与 `MX-70`、`STORE-C` 的 `MX-7`、`STORE-D` 的过期版本，以及 `STORE-E` 的两份冲突条款。它们服务于 C01–C06、C09 的固定案例；纯 Java 加载器已能读取原文并校验模拟标记、必填元数据、有效期、重复版本与引用文件路径，**尚未入库、切块、生成 Embedding 或运行检索**。无命中场景 C07 留给后续案例验证。
