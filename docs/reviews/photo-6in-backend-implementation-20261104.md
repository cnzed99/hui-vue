# 六寸照片纸后端实现与定向验证

状态：IMPLEMENTED_NOT_DEPLOYED / FINAL_ASTRA_HIGH_REVIEW_REQUIRED。

基线为 4aef8999a233a5bb85b8dc4f6982eaeb196022d7；工作树为 `/home/ubuntu/print-system/runtime/worktrees/photo-6in-backend-20261104`，分支 `print/phase1-photo-6in-backend`。本开发 Agent 的运行模型/effort 未独立核验。设计审查独立记录在 `astra-photo-6in-pricing-design-r3-20261104.md`，仅设计层 PASS。

实现包括：有限 PHOTO 来源与 variant；DOCUMENT 20、普通 PHOTO 30、拼版与证件照 1 页统一规则；持久化上传会话与有序图片项；manifest、来源、规格和渲染版本冻结；稳定内部文件域及 final_file_id 恢复；隔离子 JVM 六寸 contain 渲染；不可变价格版本、门店 binding、CAS、审计；existing-first 报价；v1 原金额表示和 wire shape 保留、v2 照片快照；付款、建任务和 CLAIM/ACCEPT/START 对象/摘要/期限/来源准入；历史查询和 RESULT_UNKNOWN 证据恢复不依赖对象期限。

接口约定：

- `/app-api/print/miniapp/demo/photo-upload-sessions`：POST `{variant,fileCount}` + 稳定 Idempotency-Key；GET `/{uploadId}`；POST `/{uploadId}/parts/{index}`（从 0 起、唯一 multipart file）；POST `/{uploadId}/complete`（空 body）。返回 `{uploadId,state,variant,fileCount,uploadedIndexes,expiresAtMs,result}`；`result` 为原文件 metadata，可用原 `/files/{id}` 轮询 READY。跨主体 404、期限 410、冲突 409、未知提交 503；未知结果必须恢复原 uploadId。
- 价格 GET/PUT `/admin-api/print/miniapp-demo/photo-price` 无 query。PUT 严格 `{expectedRevision,pricePerSheet}`；revision/profileVersion 为字符串，quoteEnabled 只读 boolean。43100 输入、43101 CAS 冲突、43102 配置不可用。GET/PUT 分别绑定 query/update 权限，作用域来自 PrintScopeService。
- 照片报价使用原 `/quotes`，提交 PHOTO_6IN/COLOR/SIMPLEX。v2 aggregationPolicy=PER_FILE_ALL_COPIES，pricingMode=PHOTO_SHEET，金额恰好两位小数。

定向验证原件位于 `/home/ubuntu/print-system/evidence/photo-6in-backend-20261104/`，包含 source-manifest、compile/contracts-renderer/db-probe/db-migration-readback 和总 manifest。无生产写入、无服务变更。

- 全部 print 包源码以 javac 256MiB 对已编译基线和 Maven 缓存做定向编译，PASS。不是全量 Maven/JAR 构建。
- 实际隔离子 JVM 渲染与快照/来源校验 131 项 PASS：3/4/5/6 寸横竖及极端比例、六寸物理页框、contain 矩阵、确定性、20/21/30 页完整保留、31 页拒绝、1–9 拼版单页、输出超 2MiB 整批拒绝、最长边拒绝；v1 金额与输出字段保留；v2 篡改和非法金额拒绝。子 JVM 固定 heap 256MiB、metaspace 96MiB。
- 独立 mysqld 32MiB buffer pool，复制表结构元数据、不复制原数据；实际执行迁移草案与 51 项 SQL/服务测试 PASS。覆盖来源 CHECK、不可变 profile、CAS 单胜者、角色授权数据、RR 旧快照遇关闸/改价、报价先锁时关闸等待、existing-first 原键保价、新键新价、真实 payment/task service 的文件缺失/期限失败、RESULT_UNKNOWN+过期+关闸后原证据恢复且唯一任务。
- 上传状态测试中的持久化会话/序号/manifest/事务为实际 MySQL；仅 renderer 与文件 worker 为替身。验证并发 complete、响应丢失/重启、文件落库而 final 引用未写的恢复、封口后替换、同序号换内容、TTL。真实渲染已单独测试，但没有声称 HTTP 上传到真实 worker 的整体联调已通过。
- 脚本 bash -n 与 git diff --check PASS。测试中修正过新 fixture 的 Java accessor/API 拼写，以及复制表结构时的外键导入顺序；没有把这些测试搭建失败当作业务运行结果。

迁移草案在 `ops/db/print-miniapp-demo-photo-6in.sql`：先有限 preflight、增表/初始独立 UUID/权限，单个 ALTER 同时替换来源与来源页数保护，最后以 file→binding→profile 当前锁定读替换报价 trigger。初始 quote_enabled=0。`apply-photo-6in-reviewed-migration.sh` 要求准确最终审查提交、SQL/兼容 JAR 哈希和两个审查 PASS，备份验证后停止并排空写入口；任何失败或成功均保持 Nginx/后端停止，由另一个已审兼容部署步骤接管。它不会自行切 JAR、改配置、重启或逆迁移。本轮未执行该脚本。

尚未运行：最终 Astra High 实际 diff 审查、全量兼容 JAR 构建及哈希保全、真实登录角色的 HTTP 403、真实 HTTP 上传/完整浏览器联调、生产迁移、部署、真实支付与实机。主任务接管最终审查和串行构建；构建仍须遵守 4GB/60GB 停服恢复与健康等待规则。任何新来源或 v2 数据落库前须保全兼容 JAR；此后不得回退旧 verifier/JAR。
