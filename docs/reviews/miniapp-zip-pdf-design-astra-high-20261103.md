# ZIP-PDF 闭环设计 Astra High 审查记录

- 日期：2026-11-03
- 审查角色配置：`astra_reviewer`
- 配置模型：GPT-6 Astra High
- 宿主实际模型/effort：无独立遥测，未核验
- 触发条件：新增身份作用域、持久化幂等、并发和上传资源保护规则
- 基线：`7efd316fb154c65fff089da7a5919284fb1f8cd6`
- 冻结设计：`docs/architecture/miniapp-zip-pdf-flow.md`
- 审查文件 SHA256：`729ee5e94231730020906df54a20c47bfa0b77895948b3365aff71d55001c873`
- 结论：`PASS_TO_IMPLEMENT`

## 审查结论

设计已闭合以下实施前阻断：

1. 主体和原始幂等键唯一绑定 ZIP 摘要、目录序号和 fileId，绑定随文件行在同一事务提交；同键换 ZIP/序号即使 PDF 字节相同也冲突。
2. multipart 解析前取得主体与全局许可；Nginx 与应用分别限制慢上传和已转发请求；临时空间、生命周期锁和重启清理边界明确。
3. inspection 只声明目录候选，不冒充验证未选 payload；selection 对选中项执行实际长度、CRC、比例和 PDF 检查；本期拒绝 descriptor、ZIP64、加密及不支持格式。
4. 10 秒只约束提交前同步解析；提交结果未知时必须以原 key、同 ZIP 和 ordinal 重试。

入站边界冻结为：拒绝未知长度与 Transfer-Encoding；请求总长不超过 2100 KiB；两个精确 Nginx 路由共享连接上限 2，应用 Filter 在 multipart 解析前共享全局许可 2。实现必须用真实容器证据证明 `getParts` 位于 Filter 准入之后、临时文件清理早于许可释放，并验证两个 Nginx 路由共享连接门禁。

## 授权边界

该结论只允许 Sol 实现、编写迁移候选并做隔离验证；不代表实现通过，不授权正式数据库迁移或部署。最终实现、DDL、Nginx、小程序调用链和测试证据必须再次由 Astra High 审查。真实微信与原系统 ZIP 选择后行为保持 `NOT_RUN`。
