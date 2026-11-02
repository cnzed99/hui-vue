# 一期完成度与剩余门槛审计（2026-11-02）

结论：**PARTIAL**。历史进度报告曾估算一期优先范围约 **94%**、系统整体约 **80%**；本审计没有可重算的需求分母和权重，**不将这两个历史估算提升为当前已核实完成度**。当前进度以五条已验证新系统开发打印链、取消/模拟退款分支及 R01–R14 剩余门槛为准；这不是原系统全部功能等价验收、微信发布、真实支付或真实出纸验收。

## 基线与证据规则

- 工程基线：`361103669070383671d7b7a207b9a18e53044f64`；审计分支 `docs/phase1-completion-audit-20261102`，工作树 `/home/ubuntu/print-system/runtime/worktrees/phase1-completion-audit-20261102`。本批只读取证、编写文档和 JSON，不改业务、迁移、开关、部署或推送。
- 当前范围以云端 `AGENTS.md` 和路线图为准：一期同时包含小程序、配套服务端和后台；纯后台页面/报表/运营扩展延后，原代码、入口和总目标不删除，不插入下一期开发占用本期闭环。
- 本地 `D:/work/后台/打印后台/打印系统研发任务书.md` 仅作总需求源，SHA-256 `2fe127a3c6a3d5c457e25000e8e22b72c6900f347301068a09df6003a07ffd71`。其旧“一期仅后台、小程序后续”顺序已被 AGENTS 覆盖；任务书 M0–M5 与本表小程序 M0–M10 是不同编号体系。未发现/未核验的云端任务书副本不猜测为权威现状，不把本地旧验收表勾选当最新完成证据。
- `PASS` 仅指该行写明的已验证开发范围；`PARTIAL` 表示已有结果但范围未全；`NOT_RUN` 表示尚未执行；`BLOCKED_EXTERNAL` 表示列明外部前置阻挡执行，绝不表示已完成。源码/编译、HTTP运行、H5浏览器、微信设备、实物和用户验收分别记账。
- 本文的证据 ID、准确路径、SHA-256、是否随基线 Git 跟踪，以及服务/分支快照，完整列于 [机器审计记录](../../evidence/plans/phase1-completion-audit-20261102.json)。历史运行记录只证明当时候选，本批仅核实引用存在、摘要、当前部署文件和只读健康；未重跑业务写入或冒充全量回归。

## M0–M10 逐项核对

| 批次 | 状态 | 权威证据 ID | 已验证范围与剩余边界 |
| --- | --- | --- | --- |
| M0 | PASS | RULES, ROADMAP, ARCH, WECHAT, PRODUCT | 规则、四份文档和工程基线完成；不等于业务或外部验收。 |
| M1 | PASS | M1_REVIEW, ROADMAP | 首页/文库/我的、导航与普通 H5/MP 编译通过；微信设备未验收。 |
| M2 | PASS | M2_REVIEW, ROADMAP | 只读目录、文件选择壳与清晰 fallback 完成；不是完整上传打印。 |
| M3 | PASS | ROADMAP, M4_RUNTIME, M4_BROWSER, STAGED_RUNTIME | 受限 PDF、服务端报价、持久化订单/模拟支付完成；历史 M4 闭环与最新旧链路回归支撑，不冒充真实资金。 |
| M4 | PASS | M4_RUNTIME, M4_BROWSER, M4_REVIEW | 配套后台唯一任务、租约/fencing、不可变模拟回执、本人结果恢复完成；44项运行、10项浏览器证据。 |
| M5 | PARTIAL | M5_ORDERS, M5_ORDERS_BROWSER, M5_ORDINARY_REVIEW, M5_PROFILE_REVIEW, M5_PROFILE_BROWSER, TEXT_RUNTIME, TEXT_BROWSER, TEXT_RESTART, TEXT_REVIEW | 订单中心、自定义文字闭环及普通首层页面完成；照片/证件/扫描/压缩包选取后业务、个人中心权益仍缺。 |
| M6 | PASS | LIB_RUNTIME, LIB_BROWSER, LIB_REVIEW | 3个原创演示文库条目、详情预览、去打印完成；26项API、17项浏览器；收藏/真实分享/真实门店文库及本店筛选未完成。 |
| M7 | PASS | IMAGE_RUNTIME, IMAGE_BROWSER, IMAGE_REVIEW | 单 JPG/PNG 转 A4 PDF 和共用模拟闭环完成；H5 7项通过；不能推定原高级参数一致或微信设备通过。 |
| M8 | PASS | CANCEL_RUNTIME, CANCEL_BROWSER, CANCEL_REVIEW | 未付款取消、领取前全额模拟退款、领取后人工核实完成；47项API、11项三端浏览器；真实退款未接入。 |
| M9 | PARTIAL | COLLAGE_RUNTIME, COLLAGE_BROWSER, STAGED_REVIEW, STAGED_DEPLOY, STAGED_RUNTIME, STAGED_EDGE | H5拼版、逐张 staged HTTP、并发准入/重放验证通过；native=false。微信上传连接行为、开发者工具/真机及原选图后布局未验收。 |
| M10 | PARTIAL | M10_SUMMARY, M10_CURRENT, M10_VERSION, M10_ARTIFACTS, M10_WINDOW | 配置/离线编译门禁 PASS；真实发布 NOT_READY，设备 NOT_RUN。主协调报告独立 Sol High 最终产品 PASS，准确绑定下述提交；基线内尚无该最终审查记录文件。 |

M10 最终独立审查来源为 `COORDINATOR_REPORTED_REVIEW_RESULT`：`sol_reviewer` / GPT-6.1 Sol High，绑定小程序 `0a1a37f95f07199d93d522cd0fdec24468a5a9ec`、工程 `361103669070383671d7b7a207b9a18e53044f64`，结论 PASS，仅配置门禁及离线编译范围；未伪造审查文件路径。本基线 `M10_VERSION` 的“主协调待收口”是较早记录，不将其计划诊断 PASS_TO_FIX 冒充最终产品审查，也不改写原记录。

## 五条打印链及取消分支

五条链分别是 **文档 PDF、自定义文字生成 PDF、原创演示文库去打印、单图片生成 A4 PDF、多图片拼成单页 A4 PDF**。它们均已在新系统隔离开发环境贯通：受限文件/解析 → 服务端权威报价及冻结快照 → 订单 → 明示模拟支付 → 配套后台处理 → 同一唯一任务的模拟回执 → 小程序查询/恢复。对应证据分别为 M4_RUNTIME/M4_BROWSER/STAGED_RUNTIME、TEXT_RUNTIME/TEXT_BROWSER/TEXT_RESTART、LIB_RUNTIME/LIB_BROWSER、IMAGE_RUNTIME/IMAGE_BROWSER、COLLAGE_RUNTIME/COLLAGE_BROWSER/STAGED_RUNTIME。

后台读取与处理权限分开；每订单一个稳定任务，动作使用 expectedVersion、leaseId、fence 和幂等键，回执序号为1且审计不可变。过期 CLAIMED 显式恢复并换 fence；ACCEPTED/EXECUTING 超时只能进入 RESULT_UNKNOWN，不能自动重排或创建第二任务。上述为 M4 已审已验证规则的描述，本审计不重新设计规则。

M8 分支：未付款可取消；模拟已付款但未建任务、或首次 QUEUED/fence=0 且从未领取，可取消并记录全额反向模拟退款；曾领取、执行、成功、失败或未知结果只能登记不可变人工核实快照。后台只读查看取消/退款/核实记录；不自动核实、不自动停止原任务、不自动退款、不自动重打；取消后不再建/推进任务。模拟退款不代表真实资金到账。

## 按系统维度核对

| 维度 | 状态 | 权威证据 | 实际边界 |
| --- | --- | --- | --- |
| 服务端 | PARTIAL | ARCH、M4_RUNTIME、TEXT_RUNTIME、LIB_RUNTIME、IMAGE_RUNTIME、CANCEL_RUNTIME、STAGED_RUNTIME | 五条开发链、隔离/持久化/权威报价/模拟资金/唯一任务已有运行证据；其余类型、权益及外部适配仍未全。 |
| 管理后台 | PARTIAL | M4_BROWSER、CANCEL_BROWSER、ARCH | 必需订单处理、模拟回执和取消记录已贯通；纯后台剩余目标延后但保留；不可按页面数标全。 |
| 小程序 | PARTIAL | ROADMAP、各链浏览器记录、M10_CURRENT | H5开发闭环已验证；普通MP编译不等于微信运行；照片/证件/扫描/压缩包选后参数、权益及原生渠道未完成。 |
| 开发部署 | PASS | STAGED_DEPLOY、STAGED_RUNTIME、STAGED_EDGE、JSON liveSnapshot | 当前 M9 staged 开发部署及摘要/服务/健康核验通过；M10未部署；不是正式上线。 |
| 外部集成 | BLOCKED_EXTERNAL | WECHAT、M10_CURRENT | AppID、备案HTTPS/控制台、真实登录/支付退款/订阅/存储/打印端与设备/用户验收仍缺；下表逐项列门槛。 |

## 当前部署与 Git 核验

工程 HEAD、工作树 HEAD 和运行版本分开记录，不把主目录或最新文档 HEAD 当成部署源。当前运行仍是 M9 run `20261102T142140Z-1bdbec28`；`staged=true/native=false` 来自 STAGED_DEPLOY 与运行门禁，并未读取秘密环境文件。Astra High 最终关键差异 PASS 绑定工程 `e4f9fec2258d4ff9f8a8d48f074becb7f33ac850`；实际部署工程是 `09de3d2fa6f67f0d1e052cc70e5c472a60348847`，后者验收脚本窄修单列，不能改绑 Astra PASS。部署后端 `71e22c43606a620bc8ab9f1d100ff3630013e6ad`、部署小程序 `b44e5fa2ab6a404519654f595342cb958981ea40`；M10小程序 `0a1a37f95f07199d93d522cd0fdec24468a5a9ec` 只是未部署的新候选。

| 活动文件 | 本批实测 SHA-256 |
| --- | --- |
| `/home/ubuntu/print-system/runtime/releases/current/backend.jar` | `204c17310c99f2e2fa6dde8b0127d76ef5e9073028d1718760cdbb6987780ffc` |
| `/var/www/print-system/index.html` | `33acc866e5674acb0bee7639280addc05cbd4e264f5fe752aa89b2a002411095` |
| `/var/www/print-system/miniapp/index.html` | `d37af00986584012604c688f432d1b23795f90c115d021f80f16169de49e5391` |
| `/etc/nginx/sites-available/print-system` | `e728449cb2596ab88ec8951f52a4bee6ae315de0497c3585d14e521c2a1292e7` |
| `/etc/nginx/sites-enabled/print-system` | `e728449cb2596ab88ec8951f52a4bee6ae315de0497c3585d14e521c2a1292e7` |

采样 UTC：`2026-11-02T15:05:11.409280+00:00`。四服务 print-backend/mysql/redis-server/nginx 均 active；后端按标准恢复脚本检查 `/admin-api/system/auth/get-permission-info`：HTTP200 且业务 code=401，小程序入口 HTTP200。早先 `/actuator/health` 虽 HTTP200 却包装 code404，明确拒绝作为健康证据，原探测记录保留。磁盘可用约 39.0 GiB，高于10GiB门槛。本批未停止或重启服务。

M9 keepalive 门禁是单条 HTTP/1.1 连接 create＋9 part＋complete，11次200、0重试、4.184秒后 READY；混合 H5/part 准入证明 per-IP=1、global=2 及关闭后释放。**这不证明 uni.uploadFile 实际复用连接，更不证明微信平台验收。**

证据可携带性限制：CANCEL_RUNTIME、CANCEL_BROWSER、STAGED_RUNTIME、STAGED_EDGE、STAGED_SUMS 在本基线工作树缺失，实际位于工程根 `/home/ubuntu/print-system/evidence/`，JSON 逐个注明 `LIVE_SERVER_ONLY` 和准确 SHA，不能声称 Git clone 已含全部原件。历史 M9 SHA256SUMS 的5个非自身条目全部匹配；自身条目误写空文件 SHA，无法自校验。审计记录当前清单实际 SHA，不修改历史清单，不用该自条目证明完整性。

## 剩余事项、可执行前置和严格通过条件

当前原小程序观察阻挡仅来自主协调本轮 Windows Computer Use 只读观察：微信显示“需在手机上完成登录”，随后微信已关闭。分类为 `CURRENT_COORDINATOR_OBSERVATION_UNARCHIVED`；无仓库截图/日志，本审计未复现，**不是永久可复现证据，不是新一轮调研完成**。须用户重新完成手机登录后再继续只读观察。原系统上传、下单、付款、充值、绑定、反馈、注销等会产生数据的动作，仍须具体操作授权；已有新系统研发授权不自动覆盖原系统写入。

下表“需授权”区分规则确认、外部资源与原系统写入；已有授权下的只读/文档/新系统普通工作可继续，不重复设无依据审批。每项机器字段在 JSON `remaining` 中完整对应。

| ID / 剩余范围 | 状态 | 可执行前置 | 用户授权/外部资源/原系统数据 | 严格完成判定 |
| --- | --- | --- | --- | --- |
| R01 原小程序继续观察 | BLOCKED_EXTERNAL | 用户完成手机登录，恢复已授权只读入口 | 需用户登录；微信会话；任何后续原系统写入另行授权 | 按入口/页面/状态保存当次只读证据；等待登录不算调研完成。 |
| R02 照片/证件/扫描/压缩包选取后闭环与原拼版参数 | PARTIAL | R01；确认选后尺寸、布局、裁切/处理、格式/解压/计价规则 | 未确认规则需用户确认；原选图若上传/生成数据须具体授权；安全测试素材 | 对照原参数并在新系统形成服务端权威参数/处理/报价/订单/模拟支付/后台/结果及主要失败态；来源选择壳不通过。 |
| R03 会员/红包/我的文库/服务中心权益 | PARTIAL | 观察并冻结优惠、充值、核销、收藏分享、账号/支持行为及契约 | 规则确认；原购买/充值/绑定/反馈/注销须具体授权；所需外部渠道 | 新系统真实权益API、持久化及隔离证据；资金明确模拟；静态余额、空态和按钮不通过。 |
| R04 发票/链接/Word及其余输入类型 | PARTIAL | 补齐来源/解析矩阵、格式、抓取及转换限额 | 规则确认；原选择/提交若写入须授权；微信文件权限/安全样本 | 每个已确认来源到受限解析和共用完整闭环；不把PDF成功等同所有格式支持。 |
| R05 新小程序身份/AppID/域名 | BLOCKED_EXTERNAL | 主体/AppID、备案HTTPS、TLS/DNS、request/upload/download白名单、隐私类目 | 用户/平台提供资源；秘密只入服务端受控存储；不写原系统 | 核实归属、控制台和真实TLS；真实登录 code2Session/绑定；严格编译加实际鉴权执行。 |
| R06 微信开发者工具/真机/原生上传 | NOT_RUN | R05，原生门禁启用方案及应有审查 | 需工具/手机/网络及开关授权边界；不写原系统 | 实际客户端1图/9图上传、连接准入、重试/过期/错误/身份与完整结果证据；HTTP fixture不能替代。 |
| R07 真实门店文库/收藏/分享/筛选 | PARTIAL | 明确门店/跨店、搜索分类排序、收藏分享作用域 | 规则确认；自有内容/微信分享资源；原收藏分享若写入需授权 | 新系统持久化、权限及跨作用域验证；3条原创演示文库不代表完整业务文库。 |
| R08 真实支付/退款/对账 | BLOCKED_EXTERNAL | 商户/APIv3/证书/HTTPS回调/对账；Astra High设计与最终审查 | 另行真实渠道授权及资源；不写原系统 | 实际收付、回调验签幂等、退款账务/对账证据；模拟收据不通过。 |
| R09 订阅消息/对象存储 | BLOCKED_EXTERNAL | 模板/用户同意；桶、短期最小权限凭证、受控访问/生命周期 | 资源配置及作用域确认；不写原系统 | 实际投递授权、上传下载与归属/失效/生命周期验证；适配器名不通过。 |
| R10 真实终端/打印机 | BLOCKED_EXTERNAL | 协议、身份/心跳/回执/恢复契约、现场窗口、Astra High审查 | 另行真实设备授权及打印端/机型资源；不写原系统 | 分机型实际出纸、回执语义、防重/未知/离线恢复证据；模拟成功不通过。 |
| R11 全量回归/视觉/容量/用户验收 | NOT_RUN | 修复既有Mockito测试依赖、后台完整vue-tsc资源问题；确认样本/视觉/容量目标 | 正式用户验收及容量目标需确认；受控构建资源；不写原系统 | 分开记录完整测试、固定视觉对照、容量故障与用户签认；历史类型OOM/测试失败不算通过。 |
| R12 延后纯后台/报表/运营 | PARTIAL | 先收口一期小程序及配套闭环；保留总需求清单 | 本期前插需用户另行明确要求；原系统写入仍需授权 | 按需求/API/权限/持久化/视觉验收，不按页面数；延期不删除。 |
| R13 M10独立产品审查归档 | PARTIAL | 主协调已报告Sol High PASS及准确双提交 | 文档归档无需新授权；结果尚未归档本基线 | 保存原独立结论与准确版本；不篡改较早记录、不替代外部验收。 |
| R14 云端原始证据可携带归档 | PARTIAL | 只归档已脱敏且精确匹配的现有运行原件 | 文档归档无需新授权；仅现有服务器证据 | 5个LIVE_SERVER_ONLY引用获得可恢复的持久化原件；未来清单排除自条目，历史不重写。 |

## 下一批顺序与终止判定

1. 立即推进可独立完成的 M10审查归档与既有证据归档；登录恢复后按“观察一批、记录一批、开发一批、验证一批”优先 R02，再 R03/R04/R07。没有原参数证据的地方保留缺口，不加臆造规则，不开启纯后台扩展填充工作量。
2. 同时准备 R05 所需公开配置/资质资源；就绪后跑严格联调编译，再 R06 实际开发者工具/真机，尤其验证 staged 在真实 uni.uploadFile 下的连接/限流/恢复。声明 CONFIRMED 与合成编译只证明声明和语法，不证明平台配置。
3. 各普通UI批次按5–10页或一个可演示里程碑做独立 Sol High；新增后端写入/作用域立即按分级审查；任何计价、资金、权限隔离、防重复规则、破坏性迁移或正式上线按 AGENTS 强制 Astra High。验证针对真实增量，不重跑未变版本的低价值检查。
4. 原功能与必要配套闭环收口后，做 R11 固定基线验收。真实渠道 R08/R09/R10 保持整体目标中的独立外部门槛，资源及授权就绪后实施，不能用演示通过去掉；R12继续延后但不从总目标删除。

一期优先范围“完成”必须有原小程序全部本期业务及必要配套服务端/后台的逐需求证据、明确模拟资金/打印边界、未观察规则收口、主要失败恢复和应有独立审查/用户验收；照片/证件/扫描/压缩包选后闭环或权益仍是壳时不得标记完成。系统整体“完成”还须覆盖保留的纯后台总需求、真实微信/外部集成、真实支付退款/打印以及部署/测试/用户验收门槛；历史 94%/80% 仅是未由本审计验证的报告估算，开发部署 PASS 不等于正式发布 PASS。

本审计模型：继承主协调上下文，实际模型/effort未独立核验；不冒称已切换角色。独立审计复核由主协调收口，实施者自检不代替独立审查。本批未触发新的 Astra High 条件；引用既有关键规则与审查不代表重新批准上线。

## 权威证据索引

相对链接指向本基线Git文件；云端绝对链接仅指向实际服务器原件，后者明确标记 LIVE_SERVER_ONLY。完整 SHA-256 以机器记录为准。

| ID | 文件 | 可携带性 |
| --- | --- | --- |
| RULES | [AGENTS.md](../../AGENTS.md) | TRACKED_BASELINE |
| ROADMAP | [docs/plans/miniapp-roadmap.md](../../docs/plans/miniapp-roadmap.md) | TRACKED_BASELINE |
| ARCH | [docs/architecture/miniapp-integration.md](../../docs/architecture/miniapp-integration.md) | TRACKED_BASELINE |
| WECHAT | [docs/integrations/wechat-reserved.md](../../docs/integrations/wechat-reserved.md) | TRACKED_BASELINE |
| PRODUCT | [docs/product/miniapp-replication.md](../../docs/product/miniapp-replication.md) | TRACKED_BASELINE |
| M1_REVIEW | [docs/reviews/sol-miniapp-m1-review-2026-10-31.json](../../docs/reviews/sol-miniapp-m1-review-2026-10-31.json) | TRACKED_BASELINE |
| M2_REVIEW | [docs/reviews/astra-miniapp-m2-catalog-review-2026-10-31.json](../../docs/reviews/astra-miniapp-m2-catalog-review-2026-10-31.json) | TRACKED_BASELINE |
| M4_RUNTIME | [evidence/miniapp/m4-runtime-verification-20261031T083605Z.json](../../evidence/miniapp/m4-runtime-verification-20261031T083605Z.json) | TRACKED_BASELINE |
| M4_BROWSER | [evidence/miniapp/m4-browser-closed-loop-20261031T084158083Z-6b5e450f/report.json](../../evidence/miniapp/m4-browser-closed-loop-20261031T084158083Z-6b5e450f/report.json) | TRACKED_BASELINE |
| M4_REVIEW | [docs/reviews/astra-miniapp-m4-print-processing-review-2026-10-31.json](../../docs/reviews/astra-miniapp-m4-print-processing-review-2026-10-31.json) | TRACKED_BASELINE |
| M5_ORDERS | [evidence/miniapp/m5-order-center-runtime-20261031T090441Z.json](../../evidence/miniapp/m5-order-center-runtime-20261031T090441Z.json) | TRACKED_BASELINE |
| M5_ORDERS_BROWSER | [evidence/miniapp/m5-order-center-browser-20261031T090604388Z-382b7968/report.json](../../evidence/miniapp/m5-order-center-browser-20261031T090604388Z-382b7968/report.json) | TRACKED_BASELINE |
| M5_ORDINARY_REVIEW | [docs/reviews/sol-miniapp-ordinary-batch-review-20261031.json](../../docs/reviews/sol-miniapp-ordinary-batch-review-20261031.json) | TRACKED_BASELINE |
| M5_PROFILE_REVIEW | [docs/reviews/sol-miniapp-profile-more-batch-review-20261031.json](../../docs/reviews/sol-miniapp-profile-more-batch-review-20261031.json) | TRACKED_BASELINE |
| M5_PROFILE_BROWSER | [evidence/miniapp/m5-profile-more-browser-20261031T094620872Z-4559d691/report.json](../../evidence/miniapp/m5-profile-more-browser-20261031T094620872Z-4559d691/report.json) | TRACKED_BASELINE |
| TEXT_RUNTIME | [evidence/miniapp/m5-text-runtime-20261031T103739Z.json](../../evidence/miniapp/m5-text-runtime-20261031T103739Z.json) | TRACKED_BASELINE |
| TEXT_BROWSER | [evidence/miniapp/m5-text-browser-closed-loop-20261031T104234923Z-b7200728/report.json](../../evidence/miniapp/m5-text-browser-closed-loop-20261031T104234923Z-b7200728/report.json) | TRACKED_BASELINE |
| TEXT_RESTART | [evidence/miniapp/m5-text-restart-determinism-20261031T103645Z-6976b28c.json](../../evidence/miniapp/m5-text-restart-determinism-20261031T103645Z-6976b28c.json) | TRACKED_BASELINE |
| TEXT_REVIEW | [docs/reviews/m5-text-print-loop-astra-high-20261031.md](../../docs/reviews/m5-text-print-loop-astra-high-20261031.md) | TRACKED_BASELINE |
| LIB_RUNTIME | [evidence/miniapp/m6-library-runtime-20261031T111307Z.json](../../evidence/miniapp/m6-library-runtime-20261031T111307Z.json) | TRACKED_BASELINE |
| LIB_BROWSER | [evidence/miniapp/m6-library-browser-closed-loop-20261102T035337933Z-0f4c6fb1/report.json](../../evidence/miniapp/m6-library-browser-closed-loop-20261102T035337933Z-0f4c6fb1/report.json) | TRACKED_BASELINE |
| LIB_REVIEW | [docs/reviews/m6-library-final-review-20261102.md](../../docs/reviews/m6-library-final-review-20261102.md) | TRACKED_BASELINE |
| IMAGE_RUNTIME | [evidence/miniapp/m7-image-full-loop-20261102.json](../../evidence/miniapp/m7-image-full-loop-20261102.json) | TRACKED_BASELINE |
| IMAGE_BROWSER | [evidence/miniapp/m7-image-browser-20261102T045720303Z-e1f286d2/report.json](../../evidence/miniapp/m7-image-browser-20261102T045720303Z-e1f286d2/report.json) | TRACKED_BASELINE |
| IMAGE_REVIEW | [docs/reviews/m7-image-final-review-20261102.md](../../docs/reviews/m7-image-final-review-20261102.md) | TRACKED_BASELINE |
| CANCEL_RUNTIME | [evidence/testing/m8-runtime-deploy-20261102T074733Z-f4f527f1.json](/home/ubuntu/print-system/evidence/testing/m8-runtime-deploy-20261102T074733Z-f4f527f1.json) | LIVE_SERVER_ONLY |
| CANCEL_BROWSER | [evidence/miniapp/m8-cancel-refund-browser-20261102T080146610Z-dff29d2c/report.json](/home/ubuntu/print-system/evidence/miniapp/m8-cancel-refund-browser-20261102T080146610Z-dff29d2c/report.json) | LIVE_SERVER_ONLY |
| CANCEL_REVIEW | [docs/reviews/m8-cancel-refund-final-astra-review-20261102.md](../../docs/reviews/m8-cancel-refund-final-astra-review-20261102.md) | TRACKED_BASELINE |
| COLLAGE_RUNTIME | [evidence/miniapp/m9-image-collage-runtime-20261102T112128Z.json](../../evidence/miniapp/m9-image-collage-runtime-20261102T112128Z.json) | TRACKED_BASELINE |
| COLLAGE_BROWSER | [evidence/miniapp/m9-image-collage-browser-20261102T112230056Z-ee38fdcc/report.json](../../evidence/miniapp/m9-image-collage-browser-20261102T112230056Z-ee38fdcc/report.json) | TRACKED_BASELINE |
| STAGED_REVIEW | [docs/reviews/m9-weixin-staged-upload-final-astra-review.md](../../docs/reviews/m9-weixin-staged-upload-final-astra-review.md) | TRACKED_BASELINE |
| STAGED_DEPLOY | [docs/progress/miniapp-m9-weixin-staged-upload-deployed-2026-11-02.json](../../docs/progress/miniapp-m9-weixin-staged-upload-deployed-2026-11-02.json) | TRACKED_BASELINE |
| STAGED_RUNTIME | [evidence/deploy/m9-weixin-20261102T142140Z-1bdbec28/runtime-smoke.json](/home/ubuntu/print-system/evidence/deploy/m9-weixin-20261102T142140Z-1bdbec28/runtime-smoke.json) | LIVE_SERVER_ONLY |
| STAGED_EDGE | [evidence/deploy/m9-weixin-20261102T142140Z-1bdbec28/edge-admission-smoke.json](/home/ubuntu/print-system/evidence/deploy/m9-weixin-20261102T142140Z-1bdbec28/edge-admission-smoke.json) | LIVE_SERVER_ONLY |
| STAGED_SUMS | [evidence/deploy/m9-weixin-20261102T142140Z-1bdbec28/SHA256SUMS](/home/ubuntu/print-system/evidence/deploy/m9-weixin-20261102T142140Z-1bdbec28/SHA256SUMS) | LIVE_SERVER_ONLY |
| M10_SUMMARY | [evidence/miniapp/m10-wechat-readiness-20261102/summary.json](../../evidence/miniapp/m10-wechat-readiness-20261102/summary.json) | TRACKED_BASELINE |
| M10_CURRENT | [evidence/miniapp/m10-wechat-readiness-20261102/current-readiness.json](../../evidence/miniapp/m10-wechat-readiness-20261102/current-readiness.json) | TRACKED_BASELINE |
| M10_VERSION | [evidence/miniapp/m10-wechat-readiness-20261102/verification-version.json](../../evidence/miniapp/m10-wechat-readiness-20261102/verification-version.json) | TRACKED_BASELINE |
| M10_ARTIFACTS | [evidence/miniapp/m10-wechat-readiness-20261102/artifact-checks.json](../../evidence/miniapp/m10-wechat-readiness-20261102/artifact-checks.json) | TRACKED_BASELINE |
| M10_WINDOW | [evidence/miniapp/m10-wechat-readiness-20261102/safe-build-window.json](../../evidence/miniapp/m10-wechat-readiness-20261102/safe-build-window.json) | TRACKED_BASELINE |
