# 证件照服务端文件最终审查记录（2026-11-03）

## 结论

- 结论：PASS。
- 角色：`astra_reviewer`，关键规则最终审查。
- 实际模型：GPT-6 Astra；固定角色 High；运行 effort 未独立核验。
- 审查提交：`3bf56c2239823d46fac3328533eb71ce09b30917..ff02c68349b2e0c0f1ac6fdf105730a847606a72`。
- 候选 JAR SHA256：`a39f9d44e7f50a26230db6de8fe77383cf55e48e120fd6fd049f804976cc8bd0`。
- 结论边界：可进入候选迁移与部署准备；不代表已迁移、已部署或用户验收完成。

## 已关闭问题

1. 私有 `multipart` / `scratch` 清理失败可观察并重试，证件照功能关闭时仍由基础文件服务执行启动清理。
2. PNG 解码前明确拒绝 APNG 控制与帧数据块。
3. 真实 HTTP 检查覆盖会话、持久化、幂等、跨主体、低磁盘、并发、双实例和停机排空。
4. 应用层以错误码 `1928012` 拒绝证件照计价，数据库触发器继续失败关闭。
5. 半迁移旧 JAR兼容、候选错误配置失败、旧 JAR回滚和既存证件照报价保护均已验证。
6. 回滚清理使用与 Java `FileChannel` 兼容的 `fcntl.lockf` 记录锁；Java持锁时退出 `75`，释放锁后只清理两个固定目录的 `task-*`，符号链接退出 `65`。
7. 回滚清理验证普通 PDF 哈希、文件表及报价表行数不变；清理后旧 JAR仍拒绝证件照报价且无新增报价。

## 原始验证结果

- `ID_PHOTO_SERVER_CHILD_PROCESS_PASS jpeg png alpha gif apng dimensions`
- `ID_PHOTO_PRIVATE_CLEANUP_PASS observable_retry disabled_feature_restart`
- `ID_PHOTO_MYSQL_ISOLATION_PASS source_pair document_quote id_photo_quote_block archive_regression`
- `ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_PASS removed=2`
- `ID_PHOTO_HTTP_ISOLATION_PASS partial_migration old_jar_rollback rollback_private_cleanup invalid_session low_disk custom_multipart idempotency persistence quote_app_guard quote_db_trigger cross_owner concurrency permit_release double_lock drain cleanup`

## 部署约束

- 回滚清理前必须停止入口流量，并确保后端父 JVM、渲染/PDF 子 JVM及其他存储写入者全部退出；优先由 systemd 停止整个 control-group。
- 锁占用、清理失败或发现非安全目标时保持入口关闭，不启动旧 JAR。
- 数据库新增列、检查约束及禁计价触发器默认保留；撤除或放宽前需新的 Astra High 审查。
