# 模拟政策向量检索联调记录

日期：2026-10-30。范围仅为本仓库的模拟试驾政策，不包含真实车源、预约或工单分配。

## 这一步交付了什么

- 主应用显式启用 `policy-rag,bailian-embedding` 后，将六份模拟政策按规则切成六个片段，使用百炼 `qwen3.7-text-embedding-flash` 的 1024 维向量写入本机 PostgreSQL + pgvector 的 `policy_vector_store` 表。默认配置组不连接数据库和模型。
- 启动时对政策原文、片段元数据和模型编号计算指纹；内容未变则跳过向量重建。语料改变会更新片段并清理不再存在的片段。原始 Markdown 仍是引用依据。
- `POST /api/policies/search` 只监听 `127.0.0.1`。输入门店编号、车型编号、计划时间和问题，返回结果类别、`simulated` 标记、政策原文片段与出处。没有写入业务系统的接口。
- 先用结构化字段和生效时间判断政策范围。价格、实时车源等无关问题拒绝拿试驾规则作答；同一范围出现冲突政策交人工。只有单份适用政策才调用向量检索，检索结果还要与仓库原文、元数据逐项核对。

## 本机运行

1. 在项目根目录执行 `bash probes/stage-1/start-policy-rag-db.sh`。脚本创建只对本机开放的数据库，随机密码和连接地址只保存在 Git 忽略的 `config/` 文件中。
2. 保持 `config/application-bailian-embedding.yml` 中的百炼配置可用。不要把密钥放进仓库。
3. 在 IDEA 的 `CarWorkOrderAgentApplication` 运行配置中，把程序参数设为 `--spring.profiles.active=policy-rag,bailian-embedding`，然后启动。
4. 示例请求：

```bash
curl -sS http://127.0.0.1:8091/api/policies/search \
  -H 'Content-Type: application/json' \
  -d '{"storeId":"STORE-A","modelCode":"MX-7","appointmentAt":"2026-11-07T14:00:00+08:00","question":"下午两点能去试车吗"}'
```

预期 `outcome` 为 `CANDIDATES`，`simulated` 为 `true`，出处为 `data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1`。`CANDIDATES` 只表示找到了供人工核对的模拟政策片段，不表示已得到试驾车、预约成功或允许分配。

## 实测

- 使用真实百炼向量模型和本机 pgvector 跑通了 Spring Boot HTTP 接口；首次启动入库六个片段，再次启动未重新生成向量。运行系统设置了 SOCKS 代理，本地 PostgreSQL 连接通过仅限回环地址的 socket 工厂直连，联调无需额外修改 IDEA 的 Java 参数。
- 显式运行 `PolicyRagLiveIntegrationTest`，将固定的八个案例逐例送入真实接口，结果 **8/8** 与期望的结果类别、政策编号和出处一致。[逐例结果](../../../data/eval/results/policy-rag-holdout-v1.tsv)。案例涵盖同义问法、价格拒答、时间边界、错车型、冲突政策及车型编号 `MX-7`/`MX-70` 的精确区分。
- 普通测试集不调用真实模型或本地开发数据库。独立的 `PolicyPgVectorProbeTest` 使用可重复的测试向量检查数据库过滤和索引更新，不用于证明语义质量。

复跑真实联调测试时，在 IDEA 中运行 `PolicyRagLiveIntegrationTest` 并添加 VM 参数 `-Dpolicy.rag.live=true`；或用本机 Maven 执行 `mvn -Dpolicy.rag.live=true -Dtest=PolicyRagLiveIntegrationTest test`。该测试会发起真实向量请求，需先确认当前账号免费额度仍有效。

## 结果边界与下一步

这八个案例验证的是政策范围、拒答和引用的端到端路径。当前每个有效门店、车型、时段范围只有一个规则片段，向量排序难度很低；**8/8 不能解释为大语料语义召回率或完整 Agent 成功率**。接口尚未接入工单状态机和管理员确认，也没有答案生成与事实核验。下一阶段需把只读政策证据接入工单建议流程，并增加同范围多片段的检索排序案例，再比较关键词基线和向量方案。
