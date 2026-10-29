# 首期已实现后台模块统一激活设计 v3

日期：2026-10-29（Asia/Shanghai）。状态：CONFIRMED_PERMISSION_MATRIX / DESIGN_REPAIR_R1 / NOT_EXECUTABLE / NOT_APPLIED；executionAuthorization=false。本设计仅修复 Astra High 指出的输入/运行输出生命周期和 HTTP 业务成功判定两项阻断，待增量复审，不能作为执行 PASS。用户“同意权限口径”只确认权限能力；advertisingGateTrue、acceptanceRecordRetention、maintenanceWindow、localWriterExclusion 仍为 PENDING_HUMAN。

## 目标与范围

在用户云服务器 /home/ubuntu/print-system 的一次开发演示维护窗口，统一激活 19 个已实现原站入口：P02、P14；P08/P09/P13/P33/P34；P15；P36–P38；P46–P53。完整交付单元为一个后端 JAR、一个前端全文件清单、准确配置、10 张新表和 print_store 的一个增量 ALTER、16 个权限节点、25 条新增 role_menu。P01/P04/P22 及辅助演示链路作为发布回归，不计为本次新增激活入口。

目录仅开放现有只读接口；“管理员配置”不扩展不存在的商品、终端或打印机写 API。当前目录行还受既有 tenant/store/owner_user_id 条件约束；权限确认不授权扩大行作用域。终端 onlineStatus=UNKNOWN、打印机 reportStatus=NOT_REPORTED；不推断在线或有效设备配置。统计保留订单快照、币种、simulation 和空报表边界。广告仅管理草稿，不形成设备投放。支付和打印设备继续明确模拟，小程序、真实资金、实机打印不属于本次结论。原站继续只读。

v3 是新的统一候选，不修改、不串行调用旧 P02/P14 或 ordinary v2 执行器。旧设计/renderer/PASS 仅作为已审模式与证据来源，不继承执行授权。v2 中“ordinary 不激活 P02/P14”的边界由本 v3 的明确范围替代，须取得针对 v3 的新审查。默认命令和 import 均零副作用；真实入口只允许一个，旧入口继续关闭。

## 输入与确认来源

| 输入 | SHA256 |
| --- | --- |
| docs/decisions/ordinary-permission-matrix-confirmed-2026-10-29.json | 58cbfe13f041bc64c97aff7ffcb48498b87ff9787ce0ebc199e5bde1f28fea41 |
| docs/design/p02-p14-activation-v2.md | 2188b6080e3caa88bd04d01e81b4bd7c0a016c5619bee88a99e7c1b634dbb086 |
| docs/design/ordinary-modules-activation-v2.md | db5d54981e30883e1bc14441280dd94ba4125ec8a940b75ae117fae263ac011e |
| docs/directory/readonly-directory-source-v1.md | fd257ddecdf1fc07d4f0ea30598d6abf501b8bfa7d93404dd5a0d300b0ab9291 |
| docs/directory/p34-printer-source-v1.md | 598f739f5b7a4a8c0056e416ce2402d119d006f7087dc289c5284bcacce3d9af |

权限候选机器清单位于同目录 phase1-unified-permission-map-v3.json。它明确记录“能力已确认 / ID待实时基线验证 / 未应用”，不能当 SQL 或自动分配器。

## 权限映射与完整集合

tenant=1，package=1，管理员 role=1（tenant_admin），员工 role=2（print_reader），父菜单候选为既有 2000。以下 ID 是冻结前候选，必须在全物理行实时基线中确认空闲且 permission 未被其他节点占用；冲突时停止并重新审准确计划，不自动换号、不覆盖现存行。不得使用 2009。permission 已与七个相关 controller 的 @PreAuthorize 声明逐项核对。

| ID | permission | 管理员 | 员工 |
| --- | --- | --- | --- |
| 2010 | print:store:query | 授予 | 授予 |
| 2011 | print:store:update | 授予 | 不授予 |
| 2012 | print:library-settings:query | 授予 | 授予 |
| 2013 | print:library-settings:update | 授予 | 不授予 |
| 2014 | print:library-category:query | 授予 | 授予 |
| 2015 | print:library-category:create | 授予 | 不授予 |
| 2016 | print:library-category:update | 授予 | 不授予 |
| 2017 | print:library-category:delete | 不授予 | 不授予 |
| 2018 | print:statistics:query | 授予 | 授予 |
| 2019 | print:advertising:query | 授予 | 授予 |
| 2020 | print:advertising:update | 授予 | 不授予 |
| 2021 | print:product:query | 授予 | 授予 |
| 2022 | print:platform-product:query | 授予 | 授予 |
| 2023 | print:library-entry:query | 授予 | 授予 |
| 2024 | print:terminal-directory:query | 授予 | 授予 |
| 2025 | print:printer-directory:query | 授予 | 授予 |

package 合并全部 16 个 ID；管理员合并除 2017 外的 15 个 ID；员工合并 10 个 query ID：2010、2012、2014、2018、2019、2021、2022、2023、2024、2025。新增角色授权共 25 条，分类删除任何角色均关闭。完整旧 package/role 集合及全部物理记录必须保留，以实时集合并集计算；不得用历史数组覆盖。

历史记录的 package 和 role 1 各有 13 个菜单、role 2 有 4 个菜单。仅在实时基线与此完全相同且目标均不存在时，新增后数量应为 package=29、role1=28、role2=14。数量只是附加断言，精确全集、完整物理行和过滤后集合相等才是证明。若任一模块已激活或有部分 DDL/授权，不能减去冲突项后继续；先记录实际状态并重新冻结、审查恢复/续作方案。

## DDL、依赖和逐语句图

不修改以下七份 DDL。它们包含 10 次 CREATE TABLE 和 1 次 ALTER TABLE，共 11 条有业务效果的 DDL；注释不是可执行授权。执行器按已冻结语句逐条执行，不能整文件重定向给 mysql。

| 文件 | SHA256 | 精确效果 |
| --- | --- | --- |
| ops/db/print-store-profile-v1.sql | fb9bc37814852586b7022ac341fa961fdf5fc3159cacbf04237015940275b676 | ALTER print_store 增 profile_version 与 ck_print_store_profile_version；CREATE print_store_profile |
| ops/db/print-library-settings-v1.sql | 635266327c3c77b73277951686b5fc8a60cacd446dc6491f64ad164c7ab8d43f | CREATE print_library_settings |
| ops/db/print-readonly-directory-v1.draft.sql | 62848752c30014c1fee80a0c315168cb1baa4ae44cf6ca9c6acca3ff88e8c9dc | CREATE print_product_catalog_entry、print_library_catalog_entry、print_terminal_directory |
| ops/db/print-printer-directory-v1.draft.sql | 525a5527b5219376ad9bf4798c13667215943aed0adaf3870407395abbdf8dbb | CREATE print_printer_directory，依赖完整 tenant/store/owner/terminal 外键 |
| ops/db/print-library-category-v1.draft.sql | 99ce0b07863e0d360ef9d2c6d46033914fd8b7ee3f460b2195439f6d3359cab5 | CREATE print_library_category |
| ops/db/print-statistics-reports-v1.draft.sql | c0c657e469d9711f3995bf4fa38be5eff758aba235de517a131beda5912386cc | CREATE print_paper_total_report、print_paper_daily_report |
| ops/db/print-advertising-v1.draft.sql | 75717d1bec23a6186a7c03c7f8e8d4a98840dbd56ee822edd77cf888b68ffe8b | CREATE print_advertising_draft |

authoritative 图：G0=准确基线；G1=profile_version ALTER 完成；G2=store_profile 完成；G3=library_settings 完成；G4=product_catalog 完成；G5=library_catalog 完成；G6=terminal_directory 完成；G7=printer_directory 完成；G8=library_category 完成；G9=paper_total_report 完成；G10=paper_daily_report 完成；G11=advertising_draft 完成。每条语句执行前必须等于其前图，执行后以独立观察得到后一图。目录三表和统计双表不能合并为一个不透明阶段；G9 是统计双表之间的必要部分成功检查点。

每图覆盖相关所有旧/新表的 tables、columns、indexes、constraints、foreignKeys、checks、triggers、引擎、字符集、排序规则、表达式及 CHECK enforcement；精确比较 FK 引用坐标和既有保护约束。bit、date/datetime、JSON 的规范化必须保持类型和语义，不能 stringify 掩盖漂移。新表初始为空；print_store 原数据及 profile_version 的预期默认值由计划核对。DDL 在 MySQL 隐式提交，11 条不能描述为可一起 ROLLBACK；任何部分完成均持久报告并停止。

## 停机前冻结和隔离渲染

停机前完成：只读采集当前 identity、MySQL版本/sql_mode、完整相关 metadata、tenant/package/role/menu/grant 全物理记录（含软删行及唯一性）、候选 ID 冲突、现有完整集合；连续两次一致。采集器只允许固定 SELECT，不提供任意 SQL 入口。此设计未实施采集。

冻结一份独立的 v3 renderer 与 v3 执行入口；旧 P02/P14 和 ordinary 工具不委托、不自动调用。只有设计及该准确隔离动作获 High 审查后，renderer 才能在唯一随机隔离 schema 和准确授权账号下重建所需基线结构，执行 11 句生成 G0–G11。创建前证明 schema 不存在，绑定实例身份/版本/sql_mode；以固定白名单 SQL 和准确标识符处理 FK schema 坐标。基线含未知结构或 renderer 不支持 FK/trigger 等即停止补实现，不绕过、不手写推测图。不得向业务 schema 渲染。结束验证空业务行、精确 owned schema 清除、残留为零；清理未知需报告，不能写完成。此临时 schema 不是账号权限沙箱。

产物优先复用已成功构建且源码可追溯的 JAR/dist；准确源码树、构建原件和产物内容必须一致，失配才串行重建一次。候选 JAR 必含 StoreProfile、LibrarySettings、Directory、PrinterDirectory、LibraryCategory、Statistics、Advertising controllers，保留现有文件/报价/订单/支付模拟 controller。前端冻结全文件相对路径、大小和 SHA；路由/menu 必含本批 19 入口并保留 P01/P04/P22 和辅助流程。真实发布清单不能只绑定 index.html。

冻结运行链准确路径与哈希：systemd unit/drop-ins、start-backend.sh、配置优先级、配置文件、当前 release 指针、旧/新 JAR、nginx site/root 和前端清单；私密文件只记录安全元数据/受控摘要，不回显正文。已知路径仅为候选：/etc/systemd/system/print-backend.service、其 20-manual-demo.conf drop-in、ops/runtime/start-backend.sh、ops/runtime/application-cloud.yaml、runtime/releases/current/backend.jar、/var/www/print-system。实际生效值仍由执行计划重读绑定。

输入/运行输出生命周期：执行计划事前冻结备份程序（候选 ops/backup/backup_verify.py 及本批适配器）的准确版本/SHA、固定命令和依赖、源实例/schema及完整备份对象范围与排除项、加密算法/密钥文件安全读取契约、隔离恢复库的唯一命名/账号范围、旧备份轮换的准确 owned 清单，以及本 run 的独占输出绝对路径和成功条件。备份输出候选为 /home/ubuntu/print-system/runtime/backups/phase1-unified/<runId>/snapshot.aesgcm，恢复证明/输出清单位于同一 run 私密目录；最终计划必须冻结具体 runId 后的准确路径，创建前确认不存在、目录 owner=执行用户/mode=0700且无符号链接，密文和证明普通文件 mode=0600、单链接，以 O_EXCL/O_NOFOLLOW 创建，不覆盖或复用旧 run。成功条件至少包含源 identity 与冻结基线一致、AES-GCM 解密/完整性验证、恢复 identity/准确 metadata/表集/行数及关键摘要一致、恢复库被精确移除且残留为零、输出文件独立重读一致、磁盘余量满足门限。

本 run 的随机密文及恢复结果在持锁、入口/backend关闭且基线复核通过后才生成，属于运行输出，不要求事前 encryptedSha256 或 expectedProof 逐字等于尚不存在的结果。先写 durable BACKUP_RESTORE_INTENT，再运行一次备份/恢复；其实际密文 SHA、路径/大小、恢复证明摘要、临时 schema 实际身份和清理结果必须独立核验并追加 durable OBSERVED（文件和父目录 fsync）后，才允许第一条业务 DDL。输出路径/成功条件来自事前计划，实际内容 SHA/证明来自本 run 的观察；缺失、未知或不符立即停止。本契约明确替换旧 source-only RuntimeAdapter.backup_verified 中把未来密文 SHA 和整份 expectedProof 当事前精确输入的错误契约，实施时须同步修正后再审，不能靠填假哈希满足它。

私密 session ledger 同样是运行输出：最终计划事前冻结独占绝对路径 /home/ubuntu/print-system/runtime/private/phase1-unified/<runId>/sessions.jsonl、准确父目录及 owner/mode，父目录 mode=0700、每级非符号链接；ledger普通文件 owner=执行用户/mode=0600/单链接，以 O_EXCL/O_NOFOLLOW 创建并 fsync 父目录，禁止覆盖、沿用旧 run 或以路径推定归属。以后只追加，使用受控 O_APPEND/O_NOFOLLOW 描述符并校验文件身份，每条含 runId/planSha；登录前 INTENT/基线、关联后的准确 access/refresh ID和安全摘要、撤销前后结果逐条 fsync，未知结果保留，不重放。token值只在内存。ledger内容及其后续摘要不属于事前输入，不要求尚未生成的 ledger SHA；此规定替换任何旧 source-only 预设 ledger 内容/摘要的契约，准确运行观察不能倒填为已审输入。

## 单窗口执行、持久日志与故障边界

1. CLI 完整验证用户确认、run UUID、candidate/plan/review 的原始字节 SHA、全部 DDL/执行器/产物/配置绑定和固定私密凭据契约；review 必须固定 Astra/high 并明确 PASS_MIGRATION_EXECUTION。独立重读 review 普通文件，拒绝符号链接/异常链接数/路径逃逸；三适配器的完整 canonical plan 一致。所有验证先于 journal、端口和服务动作。默认/缺参/版本不符零动作。
2. 在任何服务动作或状态恢复前取得 runtime/locks/fixture-mutation.lock 的 O_NOFOLLOW 非阻塞独占锁，贯穿本次运行。争锁失败零 stop/start、零 DB、零恢复；锁失效停止。新 journal 私密或脱敏，O_EXCL/O_NOFOLLOW，逐行含 runId/planSha，INTENT/OBSERVED/UNKNOWN 写入并 fsync 文件与父目录，不覆盖历史 run。
3. 锁内重读数据库/权限/产物/配置基线；固定入口清单证明 backend 48080 仅 loopback，无其他代理和本机并行写入者。保存本运行原服务/PID；先停 nginx 并证明 18080 listener 消失/请求排空，再停 backend 并证明 inactive、原 PID退出、48080消失、应用 DB连接为零。锁不约束 Web 请求，隔离由入口和 backend 关闭实现。
4. 在关闭状态使用冻结的 AES-GCM 备份适配器创建本次完整备份，并在准确隔离恢复库核 identity、metadata、行数/关键摘要和恢复成功；精确清理恢复库并证明移除。备份/密码不进 Git；轮换旧备份只能限于已审 owned 清单。至少 10GiB 可用空间。备份失败零业务 DDL。
5. 按 G0–G11 逐条 DDL：先 durable INTENT，执行一次，独立读回完整图再 OBSERVED；超时/连接丢失或图不符记 UNKNOWN/PARTIAL，不自动重放。不得逆向 DDL 或删除新表恢复。原表原约束完整保留。
6. G11 通过后，在一个 SERIALIZABLE 事务中锁定并复核 tenant/package/menu/role/grant 的完整相关范围和物理行；插入 16 个节点、package 全集合并集、25 条精确 grants。准确菜单全部列、sort/type/status/parent、creator/updater/time、grant 物理 ID 在执行计划冻结，不依赖隐含默认值。DML 逐条 rowcount 检查；提交前全物理读回等于旧记录加准确新行，仅 package 授权集合字段发生批准更新。任何冲突/软删冲突/重复/共享package/全集变化 ROLLBACK 并停止。COMMIT 先 INTENT，ack未知记 UNKNOWN，不重试。
7. COMMIT ACK 后用独立新连接精确读回 identity、G11、16节点全部字段、package全集、两角色全集、25 grants 与旧记录摘要。旧集合/物理行完整保留；失败保持入口关闭。
8. 一次安装冻结 JAR、前端与广告 gate 配置，保留 run 专属旧 JAR/指针/配置/前端 archive+manifest；每个安装步骤独立 durable INTENT/OBSERVED，partial/unknown 停止。前端旧资源保留至发布验收结束。启动 backend 一次，证明新 PID、运行 JAR SHA、loopback48080、健康状态、准确生效 gate/config/前端清单。
9. 完成下述受控会话、缓存、HTTP/浏览器和 SQL读回后，清理本次所有 access/refresh/浏览器状态，只有全项通过才写 READY_TO_REOPEN。启动 nginx，独立核 18080 和发布资源与清单，写最终状态。发布不是生产实机验收。

失败规则：在未发生任何业务 DB/发布副作用且仍持本run锁、可证明全部为零时，才可按审查冻结的零写恢复分支恢复原服务状态。任何 DDL/DML/COMMIT/安装/登录/清理结果 PARTIAL/UNKNOWN 或证据不足时保持入口关闭，输出只读恢复报告；不在 finally 自动开放、不自动回退数据库、不自动重放或猜测旧进程状态。恢复报告严格核每条 journal 的 runId/planSha，journal-only INTENT 即使内存 receipts 为空仍 UNKNOWN；已观察内存记录不抹除 durable INTENT。恢复动作需准确单独方案和相应审查。

## 广告 gate 与私密会话/CacheManager

advertisingGateTrue 尚待确认。候选 print.advertising.enabled=true 只开放现有草稿 GET/PUT；customEnabled=true 服务端拒绝，上传、启用、删除、slot移除、设备下发继续关闭。冻结实际 systemd、drop-in、env、参数和 YAML 的优先级后证明唯一生效 true；未设/false仍关闭。公开只输出此非敏感属性值和配置绑定，不输出其他环境。HTTPS素材只能引用当前账号 active infra_file 图片元数据，不抓取/预览外网、不伪造 infra_file。缺少合法素材时记录素材/slot移除 HTTP=NOT_RUN，可完成空素材惰性文本草稿验收。P53仅后台管理。

凭据固定 server-local 普通文件，owner=执行用户、0600、父目录/文件非符号链接；现有 smoke-admin/reader 文件可在冻结身份契约后复用，无需建账号。token 只在内存，值不进 argv/log/evidence/Git。固定 tenant1、预期两个user/role，OAuth default status0/access1800秒/refresh86400秒，禁止refresh。

每次登录先 durable intent+私密 ledger 基线，随后唯一请求；精确关联本run access/refresh DB ID、用户/客户端与安全摘要，包括响应丢失。无法关联就停止，不重试登录。浏览器须复用受控会话或纳入同 ledger。浏览器工具可用性在停服前验证；若仍被 Work 设置禁用，不可伪造 ACK 或静默删除验收门，需审准确替代技术验收方案并单列用户视觉验收。

CacheManager 复用现有 assignRoleMenu：调用前独立 DB 和 list-role-menus 双读两角色完整集合，证明全集包含于package且 handleTenantMenu 过滤后不变；管理员对自身角色只调用一次同全集赋权 API，调用前后role DML物理行/摘要均相同，只触发既有 CacheEvict。未知响应不重发。两角色真实请求证明权限生效，重启或源码注解不能替代证据。

成功或失败都精确撤销本run会话，正常登出后仅按 ledger 操作准确SQL ID与Redis key；不SCAN/FLUSH/按用户或前缀批删。access、refresh SQL及对应Redis残留均零，浏览器认证清空才写TOKEN_CLEAN。异常保持入口关闭并保留私密ledger供只读定位，不清他人会话和正常审计记录。

HTTP/业务响应统一契约：登录、所有 GET、list-role-menus、CacheManager 同全集赋权、所有写入和正常登出等成功步骤必须同时满足执行计划冻结的预期 HTTP 状态、可解析 CommonResult.code 为整数 0，以及对应端点的准确 data 结构/类型/身份或版本字段；列表还核 list/total，登录还核 token/用户关联，写入和缓存还核预期返回值及独立 SQL 读回。HTTP 200、仅存在 data 或适配器字符串 ACK 均不能单独判成功；非 JSON、结构缺失/错误、code 非零、状态不符一律失败并按已有未知副作用规则处理，不自动重试。预期拒绝用例核冻结的 HTTP 状态和 CommonResult 业务错误码（框架以 HTTP200 包装拒绝时必须 code 非零且精确匹配），并以准确 scoped SQL/版本/物理摘要证明无副作用；认证/授权等预期业务拒绝不得计入成功路径。最终实现只增加一个“HTTP200 + CommonResult.code 非零必须失败，不能进入后续成功阶段”的代表验证，覆盖共用响应判定器，不扩展同义测试套件。

## 最少充分集中验收

复用相同源码/哈希已通过的模型与构建原件，不重复普通历史套件。新增执行器只补一条完整假端口主链、一次真实新增风险失败路径、锁竞争零动作和未知提交/登录/清理不重放；发现实际问题才扩展。必要 FK/CHECK 与故障回滚代表验证仅在授权隔离恢复库，业务库不播故障数据。

- 身份和权限：两角色身份/角色/package全集精确读回；19入口的实际 API按两角色集中GET；每类至少一个伪造scope请求拒绝且无写。目录为空真实显示空，终端UNKNOWN、打印机未上报；禁止用演示假记录证明在线设备。
- P02/P14：管理员两个PUT持久化、员工PUT403且版本/内容不变；两个模块各一次同版本并发（一成功一409、版本只加一次）；P02关店仍可管理并按本run已审值恢复，P14 miniProgramPath仍服务端NOT_CONFIGURED/null；未配置GET零写。写入准确值和最终保留/恢复字段须纳入验收记录留存确认，不能默认覆盖原资料。
- P15：一个run标记测试分类，requestId同载荷重放不增行，名称编辑及一次同版本冲突；员工POST/PUT403，双方DELETE拒绝；非法输入和foreign scope代表拒绝。分类不物理删除，保留规则待确认。
- 目录五页：真实筛选/分页/total/空状态与SQL一致；只读API无新增写入口，不把管理员授权解释为新增配置实现。P34终端关联筛选保留完整scope。
- P36–P38：一次日期查询和CSV代表导出；无纸量上报保持真空表；订单快照/币种/simulation标记与SQL一致，CSV公式防护复用现有证据。
- 广告八位置GET；一个无素材惰性文本草稿管理员PUT/读回及版本冲突；员工PUT403；customEnabled/非法position/scope代表拒绝且SQL不变。合法素材缺失明确NOT_RUN，不为覆盖造假素材。保留版本和审计规则待确认。
- 浏览器同视口核19入口；共用目录/广告组件只挑代表交互，其他位置核映射/禁用态；管理员写入和员工只读、冲突/空/错误态。P01/P04/P22及辅助演示流程回归；P22筛选扩展的真实命中/空结果/倒置时间400在同次会话集中完成。浏览器技术验收与用户外观验收分别记录。
- 各成功写操作用独立SQL精确读回scoped行，响应200不等于持久化通过；会话零残留和运行产物清单核验后才开放入口。测试数据、审计/版本不承诺无痕回滚。

## 审查与执行门

permissionMatrix=CONFIRMED_HUMAN；其余四项= PENDING_HUMAN；executionAuthorization=false。本设计不执行构建、DB、服务、HTTP或登录。准确10新表+ALTER/16节点/25grants、阶段图、单入口、产物配置、备份恢复、会话和失败恢复须由 Astra High 一次集中审设计。实施后在同一最终候选中集中审准确差异、源码/产物/输入SHA、实际隔离图与验证原件，取得PASS_MIGRATION_EXECUTION。可在输入完整时合并设计与最终审查；旧PASS不能改绑v3。审后实质变化只重审受影响增量，不增加机械重复层。

当前实际产出为设计文档，模型 GPT-6.1 Sol；effort按父任务角色记录，未独立核验宿主遥测。尚未调用Astra或取得PASS，尚无真实执行/技术或用户验收结果。
