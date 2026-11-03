# 小程序 ZIP-PDF 打印闭环冻结设计

状态：`DESIGN_REVIEW_REQUIRED`  
基线：后端 `7efd316fb154c65fff089da7a5919284fb1f8cd6`，小程序 `30a80b6c066b087601755d46465131445497f068`  
范围：只新增 ZIP 中 PDF 的候选检查、选中提取及接入既有 PDF 草稿链路；不改变计价、订单、模拟支付、打印防重复及退款规则。

## 1. 接口与边界

- `POST /app-api/print/miniapp-demo/archive-inspections`
  - 单个 multipart 文件字段 `archive`，除该字段外不接受任何字段、重复字段或重复文件。
  - 服务端从现有会话解析 `tenantId/storeId/principalId`；拒绝客户端提交任何身份字段。
  - 返回 `archiveSha256`、`expiresAtMs` 及候选 PDF 列表 `{ordinal, displayName, declaredSizeBytes}`。
  - `ordinal` 是完整中央目录中的零基序号，不按名称重新检索。
  - 成功仅表示中央目录和结构声明通过本期检查，并列出候选 PDF；不表示所有未选条目的 payload、CRC 或真实类型已验证。
  - 不创建业务记录或可引用存储对象，响应结束前删除本次临时文件。
- `POST /app-api/print/miniapp-demo/archive-files`
  - 单个 multipart 文件字段 `archive`，文本字段精确为 `archiveSha256`、`ordinal`；拒绝额外或重复字段。
  - 请求头必须携带原始 `Idempotency-Key`，格式沿用现有 PDF 上传规则。
  - 重新完整校验 ZIP 结构，不信任 inspection 结果；按完整目录 ordinal 选择条目，实际限量读取并核对 CRC、实际长度与 PDF 签名。
  - 选中 PDF 作为服务端内存／私有临时 MultipartFile 适配器调用现有 `MiniappDemoFileService`，返回现有文件元数据；后续 READY、页数和可用性完全由现有异步 PDF 检查决定。
- 两接口继续使用现有 `requireForWrite` 的 Authorization、Origin、Referer、Host 校验。`archiveSha256` 只用于内容一致性，不是授权令牌。
- 通用 `/app-api/` 继续返回 404；只增加上述两个精确 Nginx location，限制 POST，`client_max_body_size 2100k`，不扩大跨域白名单。

## 2. 持久化幂等与提交契约

- 在 `print_miniapp_demo_file` 增加可空字段：
  - `archive_sha256 CHAR(64) NULL`
  - `archive_entry_ordinal INT NULL`
  - CHECK：两字段必须同时为空或同时非空；ordinal 范围 `0..19`。
- 现有唯一键 `(tenant_id, store_id, principal_id, idempotency_sha256)` 保持不变。原始 `Idempotency-Key` 只做 SHA-256 后持久化，不把 ZIP 摘要或 ordinal 拼进派生 key。
- `MiniappDemoFileService` 新增明确的 archive provenance 参数；普通 PDF 上传必须传空 provenance，ZIP selection 必须传完整 provenance。
- 文件行的 `id/fileId`、主体、原始 key 摘要、archiveSha256、ordinal、选中 PDF contentSha256/size 在现有会话锁事务内一次插入。不得以进程内 map 代替持久化绑定。
- 发现既有 key 时（包括正常重试和 DuplicateKeyException 恢复分支），必须同时比较：
  1. 主体；
  2. provenance 类型（普通上传或 ZIP selection）；
  3. archiveSha256；
  4. ordinal；
  5. 选中 PDF contentSha256 与 size。
  任一不一致返回 `IDEMPOTENCY_CONFLICT`；即使两个 ZIP/条目的 PDF 字节相同也不得视为同一请求。
- 保留期内同键同请求始终返回同一 fileId 及当前状态。FAILED、DELETED 或过期记录不创建替代文件；仍返回原 fileId/状态。客户端只有选择了不同 ZIP/条目时才生成新 key。
- 文件对象先写随机私有路径，再在事务中插入行；事务失败或唯一键竞争时只删除本请求尚未被数据库引用的对象。进程崩溃后的孤儿对象继续由单实例存储锁下的现有清理逻辑回收。
- 会话在提交事务内再次锁定并校验有效；不得只依赖请求开始时身份。

## 3. ZIP 格式、名称与校验能力

- 实际 ZIP 字节数 `<= 2,097,152`；不得只信 Content-Length。
- 总条目 `<= 20`（包含目录）；声明单项解压大小 `<= 2 MiB`，声明总解压大小 `<= 20 MiB`；未知、负数或算术溢出均拒绝。
- 只允许单卷、非加密、非 ZIP64、无 data descriptor 的 STORED/DEFLATED。拒绝其他压缩方法、分卷字段、加密位、ZIP64 extra 或 descriptor 位。
- 解析器同时核对中央目录与本地头：flags、method、原始名称字节、CRC、压缩/解压大小、local header offset；计算每个 data range 并拒绝越界、截断、重叠或指向目录结构区域。
- 原始名称编码后长度 `<= 1024` 字节。先对原始名称拒绝 NUL、绝对路径、盘符、UNC、空段、`.`/`..`、反斜杠、控制字符、目录伪装、符号链接/特殊 Unix mode、重复名称及规范化碰撞；通过后才生成不超过 200 字符的纯文本 `displayName`。名称永不参与落盘路径。
- 候选项仅为名称后缀 `.pdf`（大小写不敏感）且非目录的普通文件。已识别的 `.zip/.rar/.7z/.tar/.gz` 条目不列为候选并按本期规则拒绝；不宣称对未读取 payload 检测伪装嵌套归档。
- selection 对选中项使用有界流实际读取：输出始终独立限制 `<= 2 MiB`；实际长度必须等于声明长度，CRC 必须一致；压缩比 `uncompressed/compressed <= 100`，正大小且 compressed=0 拒绝；校验 PDF `%PDF-` 签名。最终页数和有效性仍由既有隔离 PDF 检查产生 READY/FAILED。
- 不递归解压、不调用外部解压命令、不将未选 payload 解压到磁盘。

## 4. multipart 前准入、并发与磁盘预算

- 新增 ZIP 专用最高优先级 Filter，精确匹配两个 URI，并在任何 `getParts/getParameter*` 或 multipart 解析前执行：方法、Content-Type、Content-Length 上限、写会话、Origin/Referer/Host、主体和资源许可校验。请求必须只有一个由容器解析出的非负 `Content-Length` 且 `<= 2,150,400` 字节（2100 KiB）；缺失、未知、重复/冲突长度或任何 `Transfer-Encoding` 均拒绝。应用依赖 HTTP/1.1/2 容器按已解析 framing 严格限定请求体边界，multipart 解析后仍独立核对实际 ZIP 字节 `<= 2,097,152`，因此伪报较小长度不能越过 framing 获得更大 part。该 Filter 在调用 `chain.doFilter` 前取得全局许可并在返回后释放；实施测试必须证明 Spring/Tomcat 在 Filter 之后才调用 `getParts`，否则不得使用该方案。
- 限制：每主体 inspection 同时 2、selection 同时 1；两个接口全局合计同时 2；无等待队列，未取得许可立即拒绝。
- 全局许可和主体许可从 Filter 成功准入起持有到整个 controller 请求真正结束（包括 multipart 临时文件清理、ZIP 解析/提取和 selection 同步提交），不得在后台任务仍使用资源时提前释放。
- 本功能私有临时空间预算初值 64 MiB，按最坏占用预留；同时检查文件系统可用空间在预留后仍 `>= 10 GiB`。预算包含应用 ZIP 副本、选中 PDF 和内部副本；Servlet multipart/Nginx body temp 由各自硬限制与独立目录/容量界保护，并记录为独立边界，不重复宣称由应用 64 MiB 覆盖。
- `spring.servlet.multipart.location` 使用现有私有 multipart root；ZIP 应用临时文件使用存储根下专属 `archive-requests/`，目录 0700、文件 0600、随机名且禁止跟随链接。
- ZIP 临时目录沿用现有 MiniappDemoFileService 单实例 OS 生命周期锁：只有持锁进程启动时才能清扫本功能明确归属且不在当前活动集合中的遗留文件。正常结束 finally 删除；删除失败保留预算占用并进入维护线程重试，不能释放磁盘预算后任其积累。关闭时等待活动请求排空；未排空则保留 OS 锁至进程退出，不清理其他进程可能在用的文件。
- Nginx 两精确路由设置 `client_max_body_size 2100k`、`client_body_timeout 15s`，并用 `limit_conn_zone $server_name zone=print_archive_global:1m` + `limit_conn print_archive_global 2` 将两个路由合计的正在接收/缓冲连接限制为 2；另用客户端地址 zone 限制单地址 2。该连接门禁覆盖请求体尚未转发到应用前的慢上传阶段，故专属 Nginx body temp 的活动主体最坏值为 `2 * 2100 KiB = 4200 KiB`（另计文件系统块/元数据）；断连由 Nginx 请求清理，服务重启时只清专属目录内本功能归属残留。应用 Filter 在 multipart 解析前持有全局 2 个许可，故 Servlet multipart 活动主体最坏值同为 4200 KiB，并覆盖绕过 Nginx 的内部直连；应用启动且取得单实例生命周期锁、尚未接受请求时，才清理已配置 multipart root 内的遗留临时文件。全局 Spring multipart 上限保持现状，不能将其作为 ZIP 路由的唯一保护。部署前必须验证：`nginx -t`、两条精确 location 共享同一全局 zone、3个并发慢请求中第3个被拒、内部直连的第3个请求在解析 multipart 前被拒、断连与服务重启后专属临时目录无残留且不删除活动文件。

## 5. 10 秒期限与结果未知

- 10 秒只覆盖应用取得 ZIP 输入后、进入文件落库事务前的 ZIP 复制、结构解析、选中提取与校验阶段。
- 该阶段使用同步、可中断的有界读取，不启动脱离请求生命周期的后台解析任务；每个解析循环检查 deadline，超时在任何数据库提交前失败并清理。
- 开始事务前最后检查 deadline。事务开始后不再声称 10 秒可取消；请求断开或响应超时时，提交结果可能未知。
- 结果未知时客户端必须用同一个 Idempotency-Key、同一 ZIP 和 ordinal 重试；服务端按持久化绑定返回同一 fileId/当前状态或明确冲突。不得因超时自动更换 key。
- PDF 后续隔离解析沿用现有 worker 和状态轮询，不纳入这 10 秒期限。

## 6. 小程序状态机

- MP 使用 `chooseMessageFile` 选择 ZIP；H5 使用本地文件 input。首批只允许一个 ZIP。
- inspection 成功后显示候选 PDF 列表，用户单选；提交 selection 时固定本次 `archiveSha256 + ordinal + idempotencyKey`。
- 重复点击、网络断开、页面恢复继续沿用本次 key；用户重新选择 ZIP 或条目才建立新的 selection 操作和 key。
- 以页面操作 epoch 隔离迟到响应；旧响应不得覆盖新选择。会话变化时清除旧 fileId、草稿与 key。
- selection 返回后轮询既有 `GET /file` 至 READY/FAILED；仅 READY 的服务端 fileId/pageCount/size 进入 `createDraft('SERVER_UPLOAD', ...)`，然后复用现有设置、权威报价、订单、模拟支付、后台处理和模拟打印结果链路。
- 本功能本身不创建订单、不支付、不启动打印；不修改任何计价、支付退款或打印防重复规则。

## 7. 最少充分验证与发布门禁

- 隔离迁移预检和回滚演练；正式数据库执行前再次 Astra High 审查实际 DDL 与差异。本次若仅工程候选，不直接改正式库。
- 后端必测：同 key 并发/重试同 fileId；同 key 换 ZIP或ordinal且 PDF 字节相同仍冲突；事务前中断、提交结果未知后重试、重启恢复、会话失效、两主体隔离。
- 恶意 ZIP 必测：路径/符号链接、重复/规范化碰撞、截断、中央/本地头不一致、CRC/大小不符、超比率/超输出、超条目、ZIP64、descriptor、加密及不支持压缩方法；失败不产生业务文件或活动任务。
- 准入必测：multipart 前超限、主体/全局并发、磁盘不足、异常清理、重启遗留清理且不删除活动文件。
- 集成必测：ZIP → 候选 → 选择 → UPLOADED/READY → 草稿 → 权威报价 → 订单 →模拟支付 → 后台处理 → 模拟回执/结果查询。
- H5 实际浏览器证据与 MP 构建证据分开记录；真实微信选择、原系统 ZIP 选择后行为保持 `NOT_RUN`，不冒充完成。
- 实现和测试完成后必须由 Astra High 复核准确提交/diff、DDL、Nginx、小程序调用链与证据；`PASS` 前不得部署该敏感增量。
