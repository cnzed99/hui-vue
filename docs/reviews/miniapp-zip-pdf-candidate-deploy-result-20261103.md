# ZIP-PDF 开发候选部署尝试结果

- 日期：2026-11-03
- 当前状态：`NOT_DEPLOYED_OLD_ENVIRONMENT_RESTORED`
- 业务产品审查：Astra High `PASS_FOR_CANDIDATE_DEPLOY`
- 活动环境：旧 JAR、旧 H5、旧 Nginx 已恢复，摘要与备份一致
- 数据库：保留向后兼容的 `archive_sha256`、`archive_entry_ordinal` 与 ENFORCED CHECK；ZIP 来源行数为 0

## 三次停止点

1. 首次在维护排空门禁停止：跨用户 `kill -0` 权限拒绝被误判为旧 worker 未退出；DDL 前停止并恢复。
2. 第二次完成 DDL、候选后端健康及普通 PDF READY 后，在精确路由 reload 的新旧 worker 混合响应门禁停止；H5 未切换，随后恢复旧制品。
3. 经 Astra High 第三次方案审查后，维护探针把 DELETE-only 的会话根路由错误列为 POST 必须 503；实际返回 403，候选 JAR/H5/DDL 均未切换，未进行第四次尝试。

## 恢复证据

- 四项服务均 active，后端与登录页 HTTP 200。
- 两个 ZIP URI 与通用边界连续返回旧配置预期 404。
- 旧上传无会话探针使用不同 loopback 源地址规避既有 10r/m 限流，均返回 401。
- 专属 Nginx body 目录权限正确且为空；候选 zone 未安装；磁盘剩余约 38 GiB。
- 证据：`evidence/zip-dev-candidate-result-20261103.md`、`evidence/zip-dev-old-state-recovery-rate-safe-20261103.log`。

候选部署仍未完成。再次尝试必须作为新部署批次重新审查准确探针集合与脚本，不得把本轮已用完的单次重试授权延续为第四次自动尝试。
