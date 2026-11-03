# 普通证件照服务端文件设计 Astra High 审查（2026-11-03）

## 绑定范围

- 工作树：`/home/ubuntu/print-system/runtime/worktrees/id-photo-preview-backend-20261103`
- 分支：`print/phase1-backend-id-photo-preview`
- 初版设计提交：`2bd9446a2be6ed1e0100bc510382c53c06fd2c8b`
- 修订设计提交：`8387f392ee26be849ec24d12d94e17adba5f3341`
- 最终设计文件：`docs/architecture/id-photo-server-file-design-20261103.md`
- 最终设计 SHA-256：`e8b1bba6a24ad45e7be7d8725121e04c9208f24fc86092d3a80c403e2b418465`

审查者只读，没有编辑文件、迁移数据库、部署、读取凭据或操作原系统。

## 初审

- 角色：固定 `astra_reviewer` / Astra High。
- 实际模型：`gpt-6-astra`。
- 实际 effort：运行环境未提供独立遥测，标记“未核验”。
- 结论：`CHANGES_REQUIRED`。

阻断项：

1. 新代码先于新增列迁移启动会使现有文件查询、异步队列和普通报价读取不存在字段。
2. 新 renderer 缺少文件服务 OS 锁校验、停止准入、活动渲染排空及排空失败保留锁的跨组件生命周期屏障。
3. 身份、并发、总请求体和磁盘门禁没有明确发生在 Servlet/Spring multipart 解析之前。

数据库计价保护方向被认可，但要求 CHECK 显式拒绝 `ID_PHOTO/NULL`，触发器对文件不存在、主体不匹配和非 DOCUMENT 全部失败关闭。

## 修订

`2bd9446..8387f39` 完成以下设计修订：

- 固定为“构建但不启动 → 关闭相关写入口并排空/停服务 → 备份和逐步增量迁移 → 新 JAR 隔离验证 → 最后开放精确路由”。
- 迁移中途失败或新 JAR 启动失败时保留已完成的新增列、CHECK 和报价保护触发器，验证兼容后恢复旧 JAR；禁止破坏性回滚。
- admission filter 在读取请求体和 multipart 解析前完成会话、全局/主体并发、Content-Length、编码、磁盘和存储锁准入，并持有许可到提交结束。
- filter 与 renderer 都依赖文件服务 OS 锁，具有 closing/stopping、活动计数、`@PreDestroy` 排空、失败保留锁和双实例验证。
- CHECK 使用显式 `source_variant IS NOT NULL AND IN (...)`；报价触发器按文件及完整 tenant/store/principal 失败关闭。
- 增加迁移失败、新 JAR 失败、旧 JAR 回滚、低磁盘、解析前拒绝、双实例与在途停机验证门禁。

## 限定增量复审

- 结论：`PASS`，仅限设计增量。
- 原三个阻断全部关闭；允许按最终设计进入实现和隔离验证。
- 此 PASS 不代表代码、SQL、部署或运行时通过。实际实现及验证证据完成后，仍须进行 Astra High 最终差异和发布方案审查。
- 证件照计价、订单、支付、打印任务和真实打印继续关闭；不得套用普通文档价格。
