# CORE-03 / 04 / 05 订单核心实现方案

编写日期：2026-10-23。性质：只读核对后形成的实施设计，未新增订单代码、表、配置或运行态行为。报价部署与 HTTP 验收由主进程执行，本方案不冒称其结果。

## 1. 核对依据与结论

- `docs/taskbook.md` 的 CORE-03/04/05/06 与 `docs/tasks.json` 同名任务：订单、退款、配送独立状态；支付/退款/余额账务守恒；租约、尝试次数与打印回执；第一条上传→报价→模拟支付→任务→回执→管理端查询链路。
- `docs/confirmed-pricing-decisions.md` 是新确认决策补充；历史任务书及 tasks 中 D08 的“待确认”不能覆盖后续明确确认，也不能因部分确认将整个 D08 关闭。
- `docs/frontend-research.md` §5、§6 以及 `docs/pages-forms.json` P21–P28：订单头、文件明细、来源/配送过滤、操作入口已观察，保存/打印/标记已打印/退款均未执行。G05/G06/G07/G09 仍限制状态、金额、设备和角色一致性的结论。
- `docs/quotes/quote-foundation-v1.md`：当前报价为单文件全页，服务器页数、精确金额、版本快照、有效期及 owner 隔离；GET 的 VALID 仅代表期限。
- `docs/print-scope-contract.md`：当前主体是员工，作用域来自数据库唯一有效门店；不能把员工 ID 当会员/客户或终端 ID。

**实施结论：可以立即开发订单创建及模拟成功链路。** 取消、退款、补打、真实设备、配送和资金规则未确认，只限制相关动作，不阻断成功链路。每一轮仅关闭实际通过的验收项；本方案及首条成功链路不等于 CORE-03/04/05 全部完成。

## 2. 决策分层

### 已确认，可直接使用

1. 第一期为服务端和管理后台；一个商家、一个门店，预留扩展；小程序后续；先演示验收。
2. 数据库、登录、权限、API 和订单持久化是真实实现；外部支付、物理打印使用显式模拟，不能触发真实资金或纸张。
3. 按纸张计费，单双面独立定价；每份双面单独向上取整。单阶梯严格超过阈值时，该组全部纸张使用命中单价。
4. D08 新确认：每个文件独立累计其所有份数，不同文件不合并。策略 PER_FILE_ALL_COPIES。测试价不是业务价格。
5. 生产旧站只读，不用历史订单/客户/资金数据作种子。
6. 任务书已要求支付、退款、打印、配送分开保存；重复请求/回执幂等；结果不明不盲重发；模拟控制必须鉴权、隔离且审计。

### 本文提出的工程设计，可先实现但不得称为旧站规则

- 首条闭环采用 `source=ADMIN_DEMO`、`actorType=STAFF`、`simulation=true`、单个 quote/文件项、不启用配送。ADMIN_DEMO 不能冒充原站电脑/微信/QQ订单来源。
- 一个报价只产生一个订单；订单保存不可变报价/文件参数快照。报价消费关系放在订单项唯一约束，不 UPDATE 已不可变的 print_quote。
- 订单创建只固定价格与参数，不表示收款、打印或取件完成。订单未消费前不得派发打印任务。
- 初期查询沿用本人 owner 隔离。门店全部订单查询另设权限和数据范围；不能因拥有 query 就自动扩大到全店。
- 首条模拟收款按订单精确金额全额成功，不实现部分支付、钱包扣款或真实支付。它是演示适配行为，不能推定真实支付渠道支持同样小数精度。
- 事件、任务及回执都保留 simulation/evidenceKind；“模拟成功”不能渲染为“实机已出纸”。

### 待用户答复，保留未开放

- 未支付订单能否取消、自动关闭时间；已支付未派发/已领取/已进系统队列/未知结果时允许哪些取消与退款动作。
- 全额/部分退款、按文件退款、已打印部分的计费、优惠/余额/赠送分摊、审核角色与拒绝理由、退款失败补偿。
- 失败/部分成功/回执丢失是否补打，收费与否、补打张数、次数限制、是否生成新单，谁能确认实际结果。
- 手工“更新已打印”允许条件、核实证据与权限；退款中能否操作任务，以及怎样处理支付/取消/打印竞态。
- 舍入/支付最小单位、最低消费、优惠顺序、多阶梯优先级、配送费与装订费；免费额度和显式零单价不能混为同一政策。
- 取件码策略及生命周期、配送方式映射与时间段、已完成定义、订单/文件留存时间及电脑记录“仅当天”适用范围。

这些规则不采用静默默认。前端不显示可执行按钮；后端缺省拒绝该动作并给出稳定未配置原因。配置字段存在不代表业务规则已经确定。

## 3. 原始订单调研映射

|源ID|已观察内容|实现处理|
|---|---|---|
|P21|电脑用户列表，全部/已打印/待退款/已退款/已拒绝退款|后续列表视图映射，不把 URL `OrderStatus=3` 当完整状态枚举|
|P22|管理员打印记录，文件/打印机/计算机/页数/份数/规格/时间/价格|文件项与任务投影，不能从支付状态伪造打印记录；“当天可见”待确认|
|P23|手机全部订单，订单号/取件码/账号/时间/员工过滤|保留来源/履约字段；第一轮只演示 ADMIN_DEMO，不伪造手机来源|
|P24/P25|现场自取/远程自取|后续独立 fulfillmentMode，不能由打印成功直接推导已取件|
|P26/P27|定时/VIP配送|配送领域及规则后续确认；首轮 deliveryStatus=NOT_APPLICABLE|
|P28|保存证件照图片|商品类型不同，不能默认创建打印任务；本轮未实现|
|订单文件明细附属页|单号/取件码/用户/联系/金额/付款/地址/备注；文件规格、打印时间及状态|订单头→文件项→任务/回执分别查询；未采集参数显示未提供，不填假值|
|明细操作入口|批量手工打印、更新已打印、退款、PDF打包及下载|只证明入口存在，业务状态与授权另定；本轮不冒充完整实现|

页面概括状态是组合投影，数据库不复用一个 OrderStatus 字段承载所有事实。原 URL 参数仅保留在未来 UI 筛选适配层。

## 4. 建议表结构与不变量

以下为待实现模型，均有 tenant_id/store_id、创建时间；用户动作另有 actor_type/actor_id、request_id、审计原因。公开 ID 建议 UUID；业务可读 orderNo 由服务器生成并唯一，不能用单号替代鉴权。所有外键/唯一键按 tenant/store 范围设计，原 quote/file/profile 的 owner 继续保留。

### 第一轮订单基础（CORE-03 可先开工）

|表|必要字段/约束|用途|
|---|---|---|
|print_order|id,order_no,tenant/store,owner_user_id,created_by_actor_type/id,source,simulation,currency,exact_total,order_state,payment_state,refund_state,delivery_mode,delivery_state,version,created_at,payment_deadline, idempotency_sha256,input_sha256|unique(tenant,store,owner,idempotency_sha256)；状态更新用 version/行锁；不可变总额字符串与快照相互核对|
|print_order_item|id,order_id,tenant/store,owner_user_id,quote_id,file_id,file_content_sha256,quote_snapshot_json,item_amount,sequence_no,created_at|unique(tenant,store,owner,quote_id)，一个报价最多消费一次；首轮恰好一个 item；后续扩多文件不能合并阶梯组|
|print_order_event|id,order_id,tenant/store,event_type,aggregate_version,actor_type/id,reason_code,request_id,source_event_id,payload_json,simulation,created_at|append-only，记录创建/支付/调度/回执事实；业务事件不含密码、token、文件正文或原站隐私|

**金额表示：** 对外保持十进制字符串；内部 BigDecimal 精确比较和运算。订单复制服务器 quote 金额，不从前端接收 total/paid/refund。数据库不能使用会静默舍入的固定两位小数列。可先沿用受限长度十进制字符串，服务端规范校验；正式结算精度确认后再选择金额整数单位/DECIMAL 迁移。金额变更只能产生明确的新事件/调整记录，不能覆盖历史快照。

**文件引用：** order_item 引用真实 file；下单前校验 READY、未过期、owner、内容摘要及页数与 quote 一致，锁后再读时间。不得修改 quote 的到期时间或 profile。已消费 quote 在取消规则未确认前不释放为可再次消费；再次打印如何形成新 quote/order 另待确认。

### 模拟收款与任务（第一条成功链路需要）

|表|必要字段/约束|用途|
|---|---|---|
|print_payment_attempt|id,order_id,tenant/store,provider=SIMULATED,simulation,expected_amount,currency,state,provider_reference,request_hash,key_hash,created_at,confirmed_at|同一订单最多一个成功收款结果；同一 attempt 的重复结果不重记；首轮只全额模拟收款|
|print_payment_event|id,attempt_id,tenant/store,provider_event_id,event_hash,result,received_at,processed_at|unique(provider,provider_event_id) 或带租户/商户作用域；同 ID 异 payload 拒绝；原始事件敏感内容不原样记日志|
|print_money_journal|id,tenant/store,business_event_id,account_kind,direction,exact_amount,currency,simulation,created_at|append-only；成功收款在同事务生成成对模拟账务分录、同事件各方向唯一；不是会员钱包余额，不宣称已完成余额业务|
|print_job|id,order_id,item_id,tenant/store,simulation,terminal_id,dispatch_state,print_result,version,generation,created_at|unique(item_id,generation)；首轮 generation=0；单一支付事件只创建一个逻辑任务；补打须另走确认后受控新 generation|
|print_job_attempt|id,job_id,tenant/store,attempt_no,lease_owner,lease_token_hash,fencing_version,lease_expires_at,accepted_at,spool_at,result_at,result,result_evidence_kind|unique(job_id,attempt_no)，一个有效租约；领取重试返回同次尝试，不能新建物理打印意图|
|print_job_event|id,job_id,attempt_id,tenant/store,terminal_event_id,event_hash,event_type,sequence_no,evidence_kind,simulation,created_at|重复 event_id 不推进第二次；同 ID 异内容拒绝；过期租约回执不得无条件覆盖新状态|
|print_simulator_terminal|id,tenant/store,simulation=true,status,capabilities,last_heartbeat_at,credential_reference|独立模拟身份，服务器绑定门店/能力；不接受调用方随意覆盖租户门店；不保存明文凭据|

任务初次可直接在支付成功事务 INSERT 为 QUEUED，数据库表本身即持久化队列。不能“先提交支付成功，再内存发送任务”而无补偿。未来接外部消息队列时再增加同事务 outbox/inbox；不要在当前 2核4GB 上为一条链路先引入额外中间件。

### 完整 CORE-03/04/05 仍需要、规则确认后补齐

- print_refund_request/refund_attempt/refund_event：独立申请/审批/渠道结果；累计退款≤实收-已成功退款-在途预留；原路/余额/赠送分摊另确认。
- wallet_account/wallet_entry/reservation：余额、赠送、充值、扣款与退款分开可核对；同账户行锁或条件更新防超支，不能拿 payment_state 代替账本。
- fulfillment/delivery_record：地址快照、方式、时间段、派送/签收状态与独立事件；不能把模拟打印完成当配送完成。
- 任务失败、未知结果、取消竞态、人工核实、确认后补打，以及实机适配；不会因为成功链路通了就关闭这些验收项。

## 5. 独立状态与动作矩阵

以下名称是新系统工程枚举建议，不声称与原站编码相同。暂不启用的分支只能保留定义，不得产生“实现完成”的统计。

|维度|首条链路使用|后续保留|禁止混淆|
|---|---|---|---|
|订单生命周期|OPEN|取消/关闭/完成规则确认后另定|OPEN 订单可以同时已支付、打印未知；不能用一个状态覆盖所有事实|
|文件处理|复用 READY|既有 UPLOADED/PROCESSING/FAILED/DELETED|文件 READY 不等于打印完成|
|支付|UNPAID → PENDING → SUCCEEDED，provider=SIMULATED|FAILED/UNKNOWN/CLOSED、部分支付若获确认|模拟成功不是渠道实收；支付成功不能把文件标为已打印|
|退款|NONE|REQUESTED/REVIEWING/PROCESSING/SUCCEEDED/FAILED/REJECTED，部分/全部汇总另定|发起退款不是退款完成；退款中不自动补打|
|任务派发|QUEUED → LEASED → ACCEPTED → SPOOL_ACCEPTED，最终 CLOSED|BLOCKED/EXPIRED/UNKNOWN_DELIVERY|租约、接收、进入系统队列均不是纸张成功|
|打印结果|NOT_REPORTED → SUCCEEDED，明确 evidenceKind=SIMULATED_TERMINAL_REPORT|FAILED/PARTIAL/UNKNOWN|实机出纸结论只能来自实机或经授权人工核实，不可从 ACK 推断|
|配送方式/状态|NONE / NOT_APPLICABLE|现场/远程自取、定时/VIP配送及独立领取/派送/签收状态|打印成功不等于配送/取件完成|

首条成功路径：订单 OPEN/UNPAID/NONE/NOT_APPLICABLE → 模拟收款 SUCCEEDED → job QUEUED → 终端领取/接收/队列确认 → 模拟结果 SUCCEEDED。管理端显示“模拟支付成功、模拟打印成功”，不擅自设置“客户已取件”或“原站订单已完成”。

|动作|立即实现的前置条件|事务结果|尚待确认边界|
|---|---|---|---|
|创建订单|真实员工范围、create 权限、本人有效 quote、本人可用文件、未消费|order+item+创建事件一次提交|多文件合单及额外业务字段后续|
|创建模拟支付尝试|DEMO 开关、身份/权限、order OPEN、未支付、文件可用、显式演示期限内|唯一 attempt，支付状态 PENDING|真实支付、拆分支付/余额后续|
|模拟成功回执|合法模拟来源、event 去重、金额币种等于服务器订单、正确状态|支付成功+成对模拟账务+唯一 job+事件同事务|不提供任意 paidAmount 覆盖入口|
|领取任务|绑定正确模拟门店终端、能力匹配、未领取、文件可用、订单已模拟支付|原子租约与 attempt，返回一次意图|实机能力未知仅影响真实适配|
|接收/队列/结果回执|同一 terminal/attempt/fencing token，合法顺序，事件去重|独立推进派发和结果状态，追加事件|冲突/乱序不覆盖最终事实|
|查询/审计|自身范围或另获门店全部查询权限|不写业务状态|展示不能靠读接口触发派发或标完成|
|记录待核实问题|维护权限、操作者、原因与相关 attempt|只追加核实备注/事件|不自动收费退款、不发补打、不自称实物已成功|
|取消/退款/补打/手工标已打印|本轮默认未开放|明确拒绝，无状态/账务/任务副作用|等待 D09 动作矩阵与 D08 金额规则|

## 6. API 建议合同

路径为新系统候选接口，并非旧 ASPX 接口。所有响应采用 CommonResult，既校验 HTTP 又校验业务 code。写入接口隐藏数据库详情，权限、非法状态、幂等冲突和过期应有不同稳定错误码；具体编码在实现时分配独立范围，避免与 file/quote/scope 冲突。

### 员工管理端

1. `POST /admin-api/print/orders`，权限 `print:order:create`，Idempotency-Key 必填；body 严格 `{quoteId}`。不接受金额、页数、tenant/store/owner、支付/打印结果。演示 source、simulation、actor 由服务端决定。
2. `GET /admin-api/print/orders/{id}`，权限 `print:order:query`；返回 header、items、payment summary、refund summary、jobs、delivery、simulation、可用动作及不可用原因。金额字符串；未采集的客户/地址/取件码为空，不合成真实身份。
3. `GET /admin-api/print/orders`，同 query 权限，分页、固定排序白名单、orderNo、创建时间、独立状态筛选。首轮本人范围；各原页面菜单参数映射留在后续 SVC-05/UI，不能提前宣称 P21–P28 完整复刻。
4. `GET /admin-api/print/orders/{id}/events`，审计查看权限；按序列分页，隐藏敏感信息。
5. `POST /admin-api/print/orders/{id}/payment-attempts`，`print:payment:simulate`，Idempotency-Key；body 只允许明确模拟渠道选项或为空，amount 从订单获取。
6. `POST /admin-api/print/demo/payment-attempts/{id}/simulate-success`，独立 `print:demo:control` 权限、DEMO 环境/开关、受限来源；生成或重放唯一模拟网关事件，经同一支付应用服务入账。不能直接 UPDATE 订单状态。调用者不能指定任意金额、门店或付款人。

reader 仅新增明确 query 权限；不得因菜单存在拥有创建/支付/设备/核实权限。admin 也须显式分配最小权限，禁止给 super_admin 替代权限设计。按钮由服务端返回 allowedActions 等能力投影，点击后服务端仍重新鉴权并检查状态。

### 模拟终端协议

建议独立 `/internal/print-simulator` 路由，不作为匿名公共回调，也不将真实 terminal 冒充员工绕过 PrintScopeService。独立认证过滤链建立模拟服务主体，SimulatorScopeResolver 根据服务器 terminal 表得到 tenant/store；令牌/HMAC 凭据仅服务器 mode600 存储且绑定 audience、terminal、有效期，接口配置关闭即拒绝。首轮仅 loopback，仍必须鉴权，不能把 loopback 视为身份。

- `POST /jobs/claim`：终端身份及 claim 幂等键，服务器选本终端可领任务；body 不允许覆盖 store/tenant。返回 jobId、attemptId、fencingVersion、租约及授权文件能力。
- `POST /jobs/{id}/events`：eventId、attemptId、fencingVersion、类型和仅模拟结果；eventId+内容 hash 去重。类型至少 RECEIVED / SPOOL_ACCEPTED / SIMULATED_RESULT。拒绝过期/错终端/错 attempt/跨店/伪造签名。
- `POST /heartbeat`：只更新已认证模拟终端；在线/离线展示来自显式演示阈值，不能推断真实设备协议。
- 文件访问必须绑定 job/attempt/terminal、短有效期、范围与内容 hash；不能直接下发不鉴权的磁盘路径或公共文件 URL。

成功链路实现需要明确上述受控身份，不把“模拟”当作跳过权限和来源校验的理由。故障注入和终端注册仅测试身份可用，独立于业务 API。

## 7. 事务、幂等与故障边界

### T1 创建订单

1. 校验开关、登录、权限、scope 和输入；对 owner 的订单请求加事务内锁或等价并发控制，统一跨服务锁顺序，避免 quote/file 顺序倒置。
2. 先查 order 的 `(tenant,store,owner,keyHash)`：相同输入返回已有订单；不同输入拒绝。已经成功创建的订单重试，不因原 quote 后来过期而再创建或失去查询能力。
3. 对首次创建，锁 quote 与其本人文件；锁等待后重读当前时间。quote 未到期、未被其他订单消费；文件 READY、未过期、摘要及页数与 snapshot 一致。任何失败不消费报价。
4. 仅复制服务器不可变报价和文件参数，创建 order、item 和 event 同事务提交。quote 唯一消费索引作为并发最终保障；同 quote 不同请求键并发，只允许一个订单，另一个明确 QUOTE_ALREADY_USED，不能再生成第二个。
5. 不发支付、不创建打印任务、不把订单标已完成。响应丢失后同 key 可重放。

### T2 模拟支付和账务

1. 锁 order 和 payment_attempt，验证当前范围、simulation、状态、演示期限以及文件可用。订单已存在后引用其价格快照，不重新套用新 profile。
2. 请求创建尝试的幂等与网关回执 eventId 幂等独立：同 key 返回相同 attempt；同事件重复返回已处理结果；同事件 ID 不同内容拒绝并审计。
3. 模拟事件金额由服务器 expectedAmount 得到；匹配币种与金额，精确比较。真实渠道未来若只支持分、订单含更多小数，应拒绝/等待明确舍入规则，不能临时四舍五入。
4. 成功回执同事务更新 payment state、插入唯一成功事件/成对模拟账务分录、创建每个 item 的唯一 job、追加 order event。事务回滚则全不发生；提交后重放不得再入账、再派发。
5. 单成功支付不变量同时由锁/条件更新/唯一键保障，不只依靠 eventId，因为不同 eventId 也可能描述同一次支付。
6. 发生来源验证失败、金额不符、文件失效或非法状态时，不生成 job，不改为打印成功。模拟回执故意延迟过期的验收可明确拒绝；未来真实渠道已扣款后的过期/取消竞态需保存真实付款事实并进入核实/退款补偿，不能沿用模拟拒绝来丢弃资金事实。

第一轮不扣会员钱包、不充值、不执行退款；这些完整 CORE-04 项保留待实现。模拟账务守恒及支付事件去重可先落地。零金额可在显式测试 profile 中作测试，不由此引入免费额度政策或跳过授权；如何走真实零元订单另行确认。

### T3 打印领取与回执

- 从持久化 QUEUED 任务用行锁/条件更新领取（支持时 FOR UPDATE SKIP LOCKED）；同事务生成单一 attempt、租约、fencingVersion。领取前再验订单已支付、文件未失效、终端范围能力和 simulation 一致。
- 数据库锁不跨网络等待；发送/领取响应只表示意图交付，终端必须持久化 job+attempt 去重，再确认。模拟器也要实现这个契约。
- 回执事件独立事务：校验身份/租约/顺序/事件 hash，写 event 再推进正确维度；同一成功结果不重复增加打印数量/账务/统计。
- 出现领取后无回执、掉线、发送超时，若无法证明终端未执行，置 UNKNOWN/待核实，不自动回 QUEUED。租约过期仅表示执行权到期，不证明没有出纸。
- 迟到回执保存可核对证据，但不能凭过期 token 任意覆盖当前状态。成功后失败、旧 attempt 覆盖新 attempt、同事件异内容应拒绝/挂核实；不要按最后到达者覆盖事实。
- 首轮不做自动补打。确认“未执行可安全重试”与“可能执行需人工核实”是后续不同分支；补打创建新的显式业务意图和 generation，不能复制原任务绕过收费/审批规则。

### 文件生命周期与 60GB 容量保护

当前文件按自身 expires 清理；订单创建不会自然延长文件寿命。订单开发必须补上与既有 DELETE/cleanup 的协调，不能只在接口层读取一次状态：

1. 用同一 file 行锁串行化下单、任务领取与用户删除；删除在事务内查询活动订单/任务引用，存在执行中引用则明确拒绝或挂核实，具体用户可取消条件另确认。
2. 自动清理时先锁 file、检查活动引用并与订单/任务标记联动；不能先删 blob 再发现有任务。未派发且文件到期，任务标 BLOCKED/文件不可用，不继续收费或派发。已派发结果不明则保留订单/任务证据并进入核实，禁止盲补发。
3. 第一轮不擅自延长用户文件保留期。演示等待窗口/租约/下载凭据 TTL 显式测试配置，并不超过文件剩余有效期。需要执行中延后清理时，必须另有明确、有限的保留策略及容量上限；不能用永久 pin 积压磁盘。
4. 每次派发/下载核验剩余时间和 hash；不因“曾有有效 quote”绕过文件有效期。成功演示选择足够但显式的测试 TTL，结束后准确清理自有夹具。
5. 数据库审计快照和已删除文件的历史元数据可保留，不重新生成/复制大 PDF。构建和备份仍由主进程串行调度，操作前≥10GiB；不新增大队列组件或长期明文导出。

## 8. 实施批次与独立验收

|批次|产物|必须实测的边界|不能据此关闭的任务|
|---|---|---|---|
|A 订单创建查询|3张订单基础表、POST/GET/list/event、单报价消费、权限与审计|有效创建；金额/页数篡改；他人/他店；过期/被删/非READY文件；同key重试/冲突；同quote异key并发；重启仍可查|完整退款/配送/多文件及各原菜单|
|B 受控模拟支付|attempt/event/模拟账务、模拟成功控制器、唯一任务插入|匿名/reader/错来源/关闭开关拒绝；同事件/不同事件重复成功；金额不符；事务失败回滚；收款与job一致；十进制守恒|会员余额/充值/赠送/退款/真实支付|
|C 模拟终端成功|独立模拟身份、队列/租约/attempt/回执/事件|重复领取与结果幂等；跨店/错终端拒绝；收到/队列/模拟结果分别保存；响应丢失；进程重启恢复；明确无实机动作|实机兼容、补打策略、取消竞态全部闭环|
|D 异常与规则补齐|故障注入、未知结果核实路径、获确认后退款/取消/补打/配送实现|断线/超时/乱序/旧租约；取消与执行竞态；退款≤可退；余额不超支；人工动作追溯|缺少实机与真实资金证据的LIVE任务|
|E 管理端连通|同一订单头/文件项/支付/任务/事件及动作能力展示|显示同一真实DB订单、四类路径、模拟标识；不以一次200响应标已打印；刷新重登可查|P21–P28外观和所有业务功能尚需逐页验收|

验收使用人工编写独立预期，至少包含：3页双面2份0.80、单文件11张阶梯1.65、两文件各6张分别1.20（后续合单仍各自阶梯）、同文件6张2份阶梯1.80。订单只取报价结果，不在验收脚本里复制被测算法来“自证”。

每一批保留代码提交/不可变 release 标识、迁移哈希、运行命令、期望与实际、未通过/NOT_RUN、模拟边界；重复回执断言事件/流水/job/attempt 数量不重复。夹具用唯一 run 标记和 ID 白名单，清理顺序为回执/attempt/job/账务/支付/订单项/订单/报价/profile/文件，依据实际外键调整；不能按通配 owner 或全库清空。鉴权凭据只在内存，报告不输出 token、密码、hash 或文件内容。

## 9. 完成判定与接下来可执行的工作

立即可开工 A，并在代码设计中预留 B/C 的真实事务边界与文件引用保护。B/C 使用受权模拟配置可继续，不等取消退款问题答复。新建表/API/模拟主体属于新系统实现，文档已区分它们与原站观察及用户业务确认。

A 通过只代表 CORE-03 的订单创建查询子项；B 通过只代表 CORE-04 的模拟成功收款子项；C 通过只代表 CORE-05 的模拟成功派发/回执子项。CORE-06 还要求成功/失败/重复/断线四类证据和管理页联动。退款、钱包、配送、未知结果与取消竞态等原任务仍继续推进，未确认规则按动作逐项提问，不缩小任务书的完成标准。

本次交付仅本文；未改业务代码、任务书、tasks.json、已确认决策或数据库；未构建/部署/重启。未产生需要清理的 SQL 导出、安装包、测试 PDF 或编译产物。

## 10. 本次核对源文件摘要

- `docs/taskbook.md` SHA-256 `2fe127a3c6a3d5c457e25000e8e22b72c6900f347301068a09df6003a07ffd71`
- `docs/tasks.json` SHA-256 `db183a5e2f3b16679777415647d4ee73bfeaa046f9e10575ddeda140d5cbb4a0`
- `docs/confirmed-pricing-decisions.md` SHA-256 `2beed39b62095706d123d30635ea59a7ac7e9e424a18c94483fc9a4c1383767a`
- `docs/frontend-research.md` SHA-256 `d392f027df5b2c6634551e252841e1a04aae11ad32252478b23168311a55ff36`
- `docs/pages-forms.json` SHA-256 `2dce1ad4aba850a5476497f252fe158716880efb36dce381e92c969f69fa87c9`
- `docs/quotes/quote-foundation-v1.md` SHA-256 `0c7a8c09f0f69b416689b51f2a1f27e70d82011950f93e7e9f5c896d1e138ec3`
- `docs/print-scope-contract.md` SHA-256 `8e4420c116781544ed182570f3609019d73dac6fe8880db7841b239ddd086495`
