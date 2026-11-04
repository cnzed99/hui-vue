# 六寸照片纸计价设计 Astra High 初审记录

- 日期：2026-11-04
- 审查角色：固定 `astra_reviewer`
- 实际模型：GPT-6 Astra / Astra High
- 运行 effort：未独立核验
- 审查对象提交：`6bc276c39ebfb53145f16125c7f93e2b64c43db7`
- 设计 SHA-256：`31599ac7dff290b25e658235803a14a1eeb1bee3189bc4d4d8ad313d45ca8e01`
- 结论：`CHANGES_REQUIRED`

## 阻断项

1. 线上 `print_price_profile` 由 `trg_print_price_profile_immutable` 保护，不能原位修改价格和 version；必须插入不可变新版本，并通过门店作用域活动指针和 CAS revision 切换。
2. 改价后同一报价幂等键重试必须返回原报价，不能因为当前 profile id 变化产生冲突；既存记录应在读取当前价格前恢复。
3. 应用报价开关关闭时还需要数据库门禁，直接 SQL 不能插入合法照片来源和价格档案组合。
4. 回退边界从第一条新来源或 schema v2 数据开始，不能只看 PHOTO 行；兼容构建必须支持 v1/v2、支付退款和打印任务状态。
5. 普通照片允许 30 页与既有文档 20 页冲突，必须按来源设置独立上限；拼版和证件照必须恰好一页。
6. 分阶段上传不能只保存在进程内；必须持久化主体、规格、有序摘要、渲染版本和最终 fileId，支持并发 complete、响应丢失及重启恢复。

金额公式 `page_count × copies × pricePerSheet` 被确认无歧义。权限方向可接受，但实现必须使用 `PrintScopeService.requireCurrent()`，不能仅信任请求租户头。

## 处理

Sol 已据此编写 revision 2：保留价格不可变触发器；新增门店活动价格指针及数据库 quote_enabled；稳定报价幂等输入；来源专用页数；持久化上传会话；第一条 v2 数据前保全兼容构建；扩充回退和状态验证。revision 2 仍须同一 Astra High 复审通过后才能实现。
