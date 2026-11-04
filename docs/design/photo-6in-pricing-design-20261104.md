# 六寸照片纸文件、计价与订单闭环设计（Astra High 审查稿）

日期：2026-11-04  
状态：`REVISION_2 / DESIGN_REVIEW_REQUIRED / NOT_IMPLEMENTED`

## 1. 用户确认规则与本批范围

用户已确认：

- 单张照片和证件照拼版按每张六寸相纸计费；
- 图片等比完整显示，不裁切，允许留白；
- 六寸照片拼版最多 9 张图片占一张六寸相纸；
- 份数乘相纸张数；
- 开发演示初始价为 `1.00 CNY/张`，并允许具备权限的管理员在配套后台修改。

本批把普通照片、六寸照片拼版和现有证件照服务端文件接入既有“报价→订单→模拟支付→后台处理→唯一模拟回执→小程序结果恢复”链路。真实资金、对象存储、微信身份、真实打印机和正式上线不在本批，继续保持 `NOT_RUN`。

## 2. 六寸文件与张数语义

六寸相纸物理尺寸为 `152mm × 102mm`。普通照片规格采用当前新系统明确规则：

| 规格 | 照片目标框 | PDF 页/相纸 |
| --- | --- | --- |
| 3 寸 | `89mm × 63mm` | 每张上传图片 1 页 |
| 4 寸 | `102mm × 76mm` | 每张上传图片 1 页 |
| 5 寸 | `127mm × 89mm` | 每张上传图片 1 页 |
| 6 寸 | `152mm × 102mm` | 每张上传图片 1 页 |
| 六寸拼版 | 整张六寸纸的网格 | 1–9 张图片合成 1 页 |
| 证件照拼版 | 已审 1/2/3 寸排版 | 1 页 |

普通照片在目标框内 `contain`：保持宽高比、居中、白底，不裁切、不拉伸。横图使用横向相纸，竖图使用旋转后的纵向页面；照片目标框随页面方向交换宽高。小于六寸的目标框在相纸中央留白。

六寸拼版布局固定：1 张为 1×1，2 张为 2×1，3–4 张为 2×2，5–6 张为 3×2，7–9 张为 3×3。每格等大、白底、等比完整显示并保留格间距，不按人脸或主体自动裁切。

单张模式一次允许 1–30 张图片，生成 1–30 页 PDF；拼版模式一次允许 1–9 张图片，生成 1 页 PDF。生成后的 `page_count` 是权威相纸张数/份。`copies` 取 1–99，最终相纸张数为：

`totalSheets = page_count × copies`

## 3. 文件来源模型与渲染边界

扩展 `print_miniapp_demo_file` 的来源约束：

- `DOCUMENT / NULL`：保留现有 A4 文档链；
- `ID_PHOTO / ONE_INCH|TWO_INCH|THREE_INCH`：保留现有证件照；
- `PHOTO / PHOTO_3IN|PHOTO_4IN|PHOTO_5IN|PHOTO_6IN|PHOTO_6IN_COLLAGE`：新增照片链。

`MiniappDemoFileKind` 与 `MiniappDemoFileVariant` 同步扩展。所有仓储读取、幂等重放和订单时文件复核必须同时绑定 `source_kind + source_variant`，不依赖文件名判断类型。

新增照片渲染 helper，继续遵守当前证件照的进程隔离：Web JVM 不直接解码用户图片；私有任务目录、有限子 JVM、固定参数、禁止 shell 拼接、超时终止、最少 10GiB 磁盘门禁、严格 JPEG/PNG 校验、PDF 最大 2MiB、退出后精确清理。单图最大 2MiB、最大 8,145,600 像素、最长边 8192；单张模式总输入最大 30MiB，拼版沿用不高于现有 12MiB 总输入门槛。具体内存上限须由渲染测试证明，不从客户端传入。若 21–30 页文件超过 2MiB，必须整体拒绝，不能静默降低图片数量、遗漏页面或部分成功。

文档继续使用全局 `maxPages=20`。照片增加独立 `photoMaxPages=30`；PDF 检查 worker 必须根据已冻结的 `source_kind/source_variant` 选择上限，普通文档 21 页仍失败，PHOTO 单张模式允许 21–30 页，PHOTO 拼版和 ID_PHOTO 必须恰好 1 页。报价、快照校验和打印任务张数使用同一来源专用上限，不能把全局 20 直接改为 30。

微信一次只能选择/上传一个文件时，新增数据库持久化的上传会话与有序图片项，不使用仅进程内 Map 作为权威状态。会话冻结 tenant/store/principal、模式、规格、期望数量、渲染版本、TTL；每个 ordinal 冻结图片 SHA-256、大小和私有对象键。跨主体统一 404；同序号重放必须内容摘要一致；封口后不得追加或替换。

完成请求先锁定会话并计算稳定 manifest 摘要，再使用 `photo-file-v1:<sha256(sessionId + manifest + renderVersion)>` 作为内部生成幂等域。渲染和文件落库成功后，以同一稳定键重放必须返回同一 fileId；第二个事务锁定上传会话并只允许从 FINALIZING 设置一次 final_file_id。若响应丢失或进程在文件落库后退出，客户端用原会话查询/重试 complete，服务端从稳定键恢复同一文件并补写 final_file_id，不能换键生成第二文件。并发 complete 最终只能观察同一 fileId。确定未提交与结果未知分别返回；结果未知时小程序保留原会话并查询恢复，不创建新会话盲重试。

## 4. 报价类型、算法与不可变快照

普通文档继续使用现有价格档案 `8cf69353-bc69-5039-ad47-b59e3c791c76`。线上 `print_price_profile` 继续由 `trg_print_price_profile_immutable` 保护，任何既存价格版本都禁止 UPDATE。照片价格采用门店作用域的不可变版本系列：

- 每个 tenant/store 的初始 profile id 独立生成，不能跨门店共用全局主键；首店迁移可预生成并核对一个 UUID，但应用和触发器不硬编码它；
- profile key：`MINIAPP_DEMO_PHOTO_6IN`；
- version：门店 revision 的规范十进制字符串；
- mode：`DEMO`
- currency：`CNY`
- 初始单价：`1.00`
- 无阶梯价；照片纸只允许 `SIMPLEX`

新增 `print_photo_price_binding`：主键 `(tenant_id,store_id)`，包含 `active_profile_id`、正整数 `revision`、`quote_enabled`、`updated_by`、`updated_at_ms`，并以复合外键绑定同 tenant/store 的不可变 profile。`quote_enabled` 初始为 0，是数据库报价门禁；只有正式开放步骤才置 1。报价和后台改价统一锁顺序：先当前 scope/binding，再 active profile；禁止反向锁定。

小程序创建照片报价时只提交严格字段：

```json
{"fileId":"uuid","paper":"PHOTO_6IN","color":"COLOR","copies":1,"side":"SIMPLEX"}
```

服务端不信任客户端页数、张数、单价、总额、来源类型或门店。根据锁定的文件行选择价格档案：

- `DOCUMENT/NULL` 必须是 `A4 + BLACK_WHITE + SIMPLEX|DUPLEX`，沿用普通档案和阶梯算法；
- `ID_PHOTO/*` 或 `PHOTO/*` 必须是 `PHOTO_6IN + COLOR + SIMPLEX`，使用六寸档案，不使用阶梯价；
- 其它组合在读取价格和写报价前失败关闭。

照片金额：`pricePerSheet × page_count × copies`，使用 `BigDecimal` 精确运算。价格范围 `0.01–999.00`，最多两位小数；禁止零、负数、指数、前后空白和超精度。写入新 profile 前统一用 `setScale(2, UNNECESSARY).toPlainString()` 规范化；v2 的单价和总额都必须恰好两位小数。历史 v1 保留其原始十进制表示和既有校验，不把 `1`、`1.0`、`1.00` 的新规范反向套到旧快照。

报价快照升级为 schema v2，新增并冻结：`sourceKind`、`sourceVariant`、`pricingMode=PHOTO_SHEET`。v2 校验器必须重新计算张数和金额，并要求照片字段/纸张/颜色/单双面/价格档案严格一致。历史 schema v1 只继续接受原先的 `DOCUMENT/A4/BLACK_WHITE`，不能借 v1 表示照片。订单创建和后台详情同时从当前文件行读取 `source_kind/source_variant`，必须与 v2 快照一致。

已签发报价、订单和模拟支付永远使用原快照，不因后台改价改变。改价只影响更新成功后新创建的报价。

照片报价的幂等输入摘要只包含稳定业务输入和算法域版本，不包含 active profile id、revision 或价格。事务顺序为：锁 session→按幂等键读取已有报价→锁文件。若已有记录，先按稳定输入摘要核验并直接返回原快照，不读取当前活动价格；只有首次创建才继续锁 binding→检查 `quote_enabled=1`→锁 active profile→计算和插入。这样“响应丢失→管理员改价→原键重试”仍返回原报价，新键才使用新版本。历史 v1 文档报价继续按旧的包含固定普通 profile id 的摘要算法核验，不能静默改写旧行。

## 5. 后台价格配置与权限

不新增只用于演示的后台页面。在现有“小程序订单”视图增加“六寸照片纸演示价格”配置卡。

新增权限：

- `print:photo-price:query`：门店管理员和只读员工可查看；
- `print:photo-price:update`：只授予门店管理员。

当前员工端暂缓，但保留 `print_reader` 只读授权设计。服务端接口：

- `GET /admin-api/print/miniapp-demo/photo-price`：无 query，返回当前门店活动档案的 `profileId/profileKey/profileVersion/revision/currency/pricePerSheet`；
- `PUT /admin-api/print/miniapp-demo/photo-price`：无 query，body 严格为 `{"expectedRevision":"正整数","pricePerSheet":"规范金额"}`。

服务端必须通过 `PrintScopeService.requireCurrent()` 取得当前活动用户、唯一门店映射和 tenant/store/user，不接受请求 tenantId/storeId 或只信任租户头。更新事务锁定 binding，使用 expectedRevision 比较；成功时生成新 UUID，向不可变 `print_price_profile` INSERT 新版本（simplex/duplex 两列写同一规范价格），再以旧 revision 为条件原子切换 active_profile_id 和 revision，绝不 UPDATE 旧 profile。冲突返回明确 409，前端刷新后才能重试。写审计日志并返回新活动版本。只读权限不能调用 PUT，隐藏按钮不能代替后端权限校验。

数据库菜单建议使用当前空闲 ID `2010/2011`；执行前必须重新读回确认无冲突。`tenant_admin` 获得 query/update，`print_reader` 只获得 query。权限迁移必须验证其它角色未意外获得 update。

## 6. 数据库保护与兼容回退

本批会放宽现有 `chk_print_demo_file_source` 和替换 `trg_print_demo_quote_document_only`，属于保护约束放宽和关键计价迁移，必须执行 Astra High 最终差异审查和正式迁移审查。

新报价触发器必须同时验证文件主体、来源和档案，不是简单允许全部非文档：

- `DOCUMENT/NULL` 只能与现有普通档案 ID 组合；
- `ID_PHOTO/已知规格` 与 `PHOTO/已知规格` 只能与同 tenant/store 的 `MINIAPP_DEMO_PHOTO_6IN` 系列中、当前 binding.active_profile_id 指向的版本组合；
- 档案必须属于同 tenant/store，且为 `DEMO/CNY`；
- 照片组合还必须要求 binding.quote_enabled=1；关闭时即使应用被绕过，直接 SQL 也必须拒价；
- 任一不存在、主体错配、未知 kind/variant、错误档案或多行均固定 SQLSTATE 失败。

来源 CHECK 明确列出 `source_kind IS NOT NULL`，DOCUMENT 必须 variant IS NULL，ID_PHOTO/PHOTO 必须 variant IS NOT NULL 且位于各自有限集合，不能让 SQL UNKNOWN 通过。迁移采用有限、可读回、不可猜测的 DDL/DML：先确认表、列、约束、触发器、菜单 ID、角色和目标系列不存在；做验证备份；停 Nginx/后端并排空写入；先新增 binding、初始不可变价格版本与权限；再以单个 ALTER 原子替换来源 CHECK；最后替换报价触发器。MySQL DDL 每个隐式提交点都立即读回。旧触发器删除后若新触发器创建或读回失败，保持 Nginx/后端和全部报价写入口停止，不允许在保护缺失状态恢复服务；不执行破坏性逆迁移。

功能受 `PRINT_MINIAPP_DEMO_PHOTO_ENABLED=false` 与 `PRINT_MINIAPP_DEMO_PHOTO_QUOTE_ENABLED=false` 两个应用开关控制，数据库 binding.quote_enabled 是独立第三门禁。迁移后先以应用双关闭、数据库门禁 0 启动兼容 JAR，验证历史 v1、普通报价和旧订单；然后只开启文件生成，保持应用照片报价关闭且数据库门禁 0，验证应用与直接 SQL 双重拒价；最终开放步骤先验证兼容构建和触发器，再在受控窗口把数据库门禁置 1，随后开启应用报价和精确 Nginx 路由。回退先关 Nginx和应用报价，再把数据库门禁置 0并读回。

兼容构建必须在任何新来源行或第一条 schema v2 报价落库前完成构建、哈希保全和读取验证；它必须认识 PHOTO、能读取 v1/v2 快照，并能继续查询、支付、取消/模拟退款、后台处理和恢复既有任务。最迟从第一条新来源或 v2 数据落库开始，禁止回滚到旧 verifier/JAR，即使系统只有 ID_PHOTO v2 而没有 PHOTO 行。回退必须先关闭照片路由、应用双开关和数据库门禁，再使用已保全兼容构建；保留新 CHECK、触发器、binding、档案、权限和数据，不做破坏性逆迁移。

功能关闭后，已有 v2 订单仍可只读查询，并按原规则完成取消/模拟退款；已创建任务继续使用冻结快照和唯一 jobId。支付创建、任务创建以及 CLAIM/ACCEPT/START 前必须重新读取订单 item、文件来源/variant/hash 和 v2 快照并一致校验；文件过期后只校验不可变身份与来源，不重新计价。已领取、执行中或 RESULT_UNKNOWN 不自动重发、不自动退款、不生成第二任务。

## 7. 小程序流程

`photo-method` 开放 3/4/5/6 寸和六寸拼版入口，并把规格写入短寿命、本地一次性选择上下文。`photo-upload` 展示规格、已选图片、相纸张数和份数；提交后使用分阶段上传生成服务端 PDF，不在客户端生成权威页数或金额。

现有证件照预览页生成文件后不再终止，使用用户已经选择的份数创建照片打印草稿。普通照片和证件照都进入共用确认页；确认页从服务端创建 v2 报价，显示六寸相纸张数、单价、总额和模拟边界，再复用现有订单、模拟支付、取消/模拟退款、后台处理和结果恢复。

草稿必须绑定当前演示 session/principal；会话变化、页面隐藏后的旧异步响应、上传结果未知或重新选择规格均不能把旧文件带入新主体。上传会话、文件生成、报价、订单和支付使用彼此分离且稳定的幂等域；结果未知时只查询原域，不生成新键。

## 8. 最少充分验证

设计通过后至少验证：

1. 3/4/5/6 寸物理页框、横竖图、极端宽高比均不裁切；单图 1–30 页，拼版 1–9 张固定为 1 页。
2. 证件照 1/2/3 寸文件均计 1 张；普通照片 N 页、C 份计 `N×C` 张；拼版 C 份计 C 张。
3. 初始价 1.00、管理员插入新不可变版本并切换指针、旧报价不变、新报价使用新版本；并发 expectedRevision 只有一个成功；旧 profile UPDATE 始终被触发器拒绝。
4. 非法价格、错误纸张/颜色/side、错误档案、未知 kind/variant、跨主体/跨门店和快照篡改全部失败关闭。
5. 历史 schema v1 文档报价/订单仍可读取和处理；v1 不能承载照片；只有 ID_PHOTO v2、没有 PHOTO 行时也能在兼容构建中回退运行。
6. `tenant_admin` 可读写，`print_reader` 只读；无权限 PUT 为 403，不能仅依靠前端隐藏。
7. 触发器直接 SQL 负例覆盖报价关闭、半迁移、正式开放与回退四态，以及文档配照片档案、照片配文档档案、主体错配和未知来源；任何保护缺失时服务不得恢复。
8. 分阶段上传覆盖并发 complete、提交成功后响应丢失、文件落库后进程退出、同键换规格/顺序/图片、跨主体及 TTL 到期，始终最多一个 final_file_id。
9. 来源专用页数覆盖照片 20/21/30/31 页、文档 21 页、伪造两页拼版和两页证件照；输出超 2MiB 整体拒绝。
10. 报价幂等覆盖响应丢失→管理员改价→原键返回原金额，新键使用新版本；v2 金额恰好两位小数。
11. 已付款、已领取、RESULT_UNKNOWN、已取消退款的 v2 订单在功能关闭和兼容回退后保持原证据及防重复约束。
12. 小程序完成“照片/证件照→报价→订单→模拟支付→后台唯一任务→模拟回执→结果恢复”浏览器闭环；原系统不访问。
13. 标准构建窗口停后端、MySQL、Redis、Nginx，构建/迁移结束自动恢复并等待健康；磁盘至少保留 10GiB，清理本批 scratch。

## 9. 审查请求

请 Astra High 重点判断：计价单位与快照是否无歧义；价格修改并发和旧报价冻结是否充分；文件来源/价格档案/触发器绑定能否防止旧 JAR 或直接 SQL 绕过；权限隔离是否最小；迁移与回退在已有 PHOTO 行后是否安全；是否存在重复计费、错误张数、跨主体泄漏或重复打印风险。
