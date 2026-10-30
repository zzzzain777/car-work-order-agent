# 百炼免费 Embedding 接线

核对时间：2026-10-30 CST。项目使用独立的 `bailian-embedding` Profile，通过现有 Spring AI OpenAI 兼容 Starter 调用百炼的 `qwen3.7-text-embedding-flash`。不改 DeepSeek 聊天模型，不把 API Key 提交到仓库。

## 账号额度与限费

在百炼华北2（北京）控制台的“免费额度”页面，按模型 code `embedding` 筛选后，该模型在烟测前显示剩余 **1M / 共 1M Token**，到期时间 **2026-11-30**。已为该模型打开“免费额度用完即停”，控制台状态显示“已开启”。页面说明额度耗尽后会返回 `AllocationQuota.FreeTierOnly`，不会继续按量扣费。这些是当日账号状态，不能推断其他时间或其他模型也免费。

## 接线

- `src/main/resources/application-bailian-embedding.yml`：启用 Embedding，固定模型 ID 与 `/embeddings` 路径。
- `config/application-bailian-embedding.yml`：本地私有的业务空间 Base URL 和 API Key；被 Git 忽略，文件权限为仅当前用户可读写。公开模板为同目录的 `.example` 文件。专用 Key 在百炼控制台只授予这个 Embedding 模型的调用权限。
- [百炼 OpenAI 兼容接口](https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai)支持该模型和业务空间专属 `/compatible-mode/v1` 地址；[模型规格](https://help.aliyun.com/zh/model-studio/embedding-rerank-model)列出的默认输出为 1024 维。换模型时必须重新核对维度并重建已有向量。

启用 Profile：`-Dspring.profiles.active=bailian-embedding`。如果同时使用 DeepSeek 聊天模型，可设 `deepseek,bailian-embedding`，两者的 API Key 分别配置。真实调用只执行显式开启的烟测：

```bash
env JAVA_HOME=/Users/zz/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  '/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' \
  -q -Dtest=BailianEmbeddingLiveSmokeTest -Dbailian.embedding.live=true test
```

## 验证边界

本地 HTTP 模拟服务的集成测试已验证 Spring AI 发出 `POST /compatible-mode/v1/embeddings`、携带正确模型 ID 与 Bearer 头，并能读取 1024 维响应。

2026-10-30 运行显式烟测，使用一条模拟政策短文本，真实接口返回 **1024 维**，本次调用耗时 **757 ms**，测试退出码为 0。同日完整本地测试为 49 个用例、0 失败、0 错误、3 跳过（默认跳过真实接口烟测）。这只是一次联通结果，不能作为稳定延迟或检索质量指标；额度实际扣减需以百炼账单或额度页面为准，也不代表 Agent 业务闭环已完成。
