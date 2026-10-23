# B 批：受权模拟收款细化合同（待主进程审查）

日期：2026-10-23。本次仅设计，未修改 Java、SQL 迁移、数据库、权限或运行配置。A 批现状由主进程报告：33 项 HTTP、3 个 PDF 及应用账号事件删除拒绝验证已通过，backend d95a448 / root b41e4eb；本文件不代替该运行证据。

## 1. 目标及范围

从真实 A 批订单开始，受权员工显式执行一次完整模拟收款。**一个事务内**保存成功付款事实、两条模拟账务分录、每文件项唯一 QUEUED 打印任务，并将订单支付状态置 SUCCEEDED、打印状态置 QUEUED、版本从1置2。创建排队任务不是终端接收，更不是实机出纸。

首轮仍仅单文件/单报价，source=ADMIN_DEMO、simulation=true、actor=当前实际STAFF、无配送。金额直接取订单不可变精确金额，不向前端索取、不从当前价格profile重算、不舍入。本批无退款、取消、补打、会员余额/赠送/充值、真实支付网关、外部匿名回调、真实设备或新增消息中间件。

这是一条同步受控的“模拟成功”应用命令；不能把它描述为已经完成真实支付网关的下单、验签、退款、查询补偿和对账，也不宣称完整 CORE-04 完成。

## 2. 环境、身份和作用域门控

写操作同时满足：

1. `print.orders.enabled=true`，`print.payments.enabled=true`；后者默认 false。
2. `print.payments.mode` 必须显式等于 `DEMO`，不提供自动默认或请求覆盖。生产/未知/未设置模式拒绝。
3. 已登录员工拥有独立权限 `print:payment:simulate`，通过 PrintScopeService 的唯一有效门店映射。reader 不获得该权限，admin 也需显式菜单/权限seed；禁止用super_admin替代设计。
4. 当前订单 tenant/store/owner 与实际scope一致，created actor是本人STAFF，source=ADMIN_DEMO、simulation=true、currency=CNY；客户端不可指定/覆盖这些条件。
5. 继续现有仅loopback后端/反代/SSH隧道演示部署，控制器不匿名开放。loopback不是身份凭证，不信任任意 X-Forwarded-* 来跳过鉴权；未来改变部署拓扑时须重新审查受信代理和来源规则。

这条员工命令不接受外部网关事件或用户输入的付款流水号，因而不引入伪造“回调来源”。后续真实回调及模拟终端另有专用认证/验签契约，不能复用这个命令当匿名回调。

写操作关闭后仍可在订单查询权限下查看本人历史订单。若增加付款详情 GET，仅依赖 orders query 开关及真实scope，允许查看已保存的历史模拟付款，不因禁用新收款而丢失历史展示。

## 3. API 合同建议

### POST `/admin-api/print/orders/{id}/simulate-payment`

- 权限：`print:payment:simulate`。
- path id：小写UUID；query必须为空。
- Header `Idempotency-Key`：ASCII33–126，1–128字符，不能含空格；只存SHA-256。
- body：严格空JSON对象 `{}`。不接受amount、paidAmount、currency、status、simulation、tenantId/storeId/ownerId、fileId、targetTerminal或故障注入字段。
- 响应：CommonResult.data为下述PaymentReceiptView。成功的业务状态是SIMULATED provider的SUCCEEDED，不代表真实资金到账。

### GET `/admin-api/print/orders/{id}/payment`

建议为本批增加只读历史付款详情，权限 `print:order:query`，query为空。先验证本人订单，再查询本人付款记录；未付款返回明确404/RECEIPT_NOT_FOUND，跨owner/门店统一ORDER_NOT_FOUND，不泄漏付款情况。模拟收款写开关关闭后仍可读取历史；订单整体查询开关关闭时按订单disabled拒绝。

```text
PaymentReceiptView = {
 id: UUID,
 orderId: UUID,
 provider: "SIMULATED",
 state: "SUCCEEDED",
 simulation: true,
 currency: "CNY",
 amount: 精确十进制字符串,
 actorType: "STAFF",
 actorId: long,
 createdAtMs: long,
 jobIds: [UUID]
}
```

receipt及jobIds由本次原子事务固定，重放时保持同一receipt/id/金额/时间/jobIds；不在此响应中放会随着未来打印流程变化的job状态，避免把重复付款响应伪装为新的排队/打印结果。打印当前状态通过订单/后续任务查询展示。前端既校验HTTP又校验CommonResult.code，不把一次200当作已经打印。

### 幂等语义与检查顺序

- 请求身份由 `tenant/store/owner/keyHash` 定义，输入hash固定为版本前缀 + 单个换行 + canonical orderId。方法固定模拟成功，body无业务参数。
- 同key同order成功后重试：返回原receipt，不重新收款、不再验证后来失效的文件、不重新排队；但环境/写开关、当前身份和权限仍必须通过。
- 同key不同order：若请求目标也是本人订单，409 IDEMPOTENCY_CONFLICT；不泄漏其他owner订单或付款。
- 不同key同已付款order：409 ALREADY_PAID，不额外保存第二条付款、账务、任务或订单事件，不把新key绑定成第二次成功。
- 首次执行失败（文件失效/事务回滚等）不持久化receipt或消费key；可修正条件后再试，不给失败请求制造已收款事实。
- 成功提交但响应丢失：重放同key得到原receipt。若有人改换key则明确已付款，调用GET查询；不自动再次收费。

## 4. 新表精确建议

三张表足以支撑本批；不先搭建钱包和多层异步支付尝试体系。每表 tenant_id/store_id/owner_user_id 都来自服务端，id UUID；账户标记均是模拟用途。下面为候选字段，代码开工前由主进程冻结。

### `print_payment_receipt`（成功后不可变）

- id CHAR36 PK；order_id CHAR36。
- tenant_id/store_id/owner_user_id BIGINT。
- provider VARCHAR16，固定SIMULATED；state VARCHAR16，固定SUCCEEDED；simulation BIT=1。
- currency CHAR3=CNY；amount VARCHAR128，复制order.total_amount的精确字符串。
- actor_type VARCHAR16=STAFF；actor_id BIGINT=实际scope.userId。
- idempotency_sha256/input_sha256 CHAR64；created_at_ms BIGINT。
- UNIQUE(order_id)：一个订单本批最多一个成功收款结果，防不同事件/不同key重复。
- UNIQUE(tenant_id,store_id,owner_user_id,idempotency_sha256)：scope内请求幂等。
- UNIQUE(tenant_id,store_id,owner_user_id,id)：供分录/任务复合FK；复合FK(scope,order_id)→order(scope,id)。
- BEFORE UPDATE禁止修改；BEFORE DELETE仅root@localhost维护可删，应用账号不得抹除付款事实。无DELETE API。

不保存真实渠道密钥、银行卡、付款人客户ID或伪造外部流水。只有成功事务才插入该表；它不声称覆盖pending/failed/unknown渠道尝试，后续真实支付应另增attempt/event模型。

### `print_simulated_journal`（成对、追加型）

- id CHAR36 PK；receipt_id/order_id CHAR36；tenant/store/owner BIGINT。
- entry_side VARCHAR8：DEBIT或CREDIT。
- account_kind VARCHAR32：DEBIT固定SIMULATED_EXTERNAL_FUNDS，CREDIT固定SIMULATED_ORDER_RECEIPT。
- amount VARCHAR128、currency CHAR3，均与receipt精确一致；simulation BIT=1；created_at_ms BIGINT。
- UNIQUE(receipt_id,entry_side)，复合FK(scope,receipt_id)→receipt(scope,id)，order_id与receipt归属由服务端验证。
- UPDATE禁止；DELETE仅root@localhost维护；无编辑/删除API。

每次成功**恰好两条**，同币种同金额，一借一贷；服务端用BigDecimal精确核对借贷差为0。它只是可核对的模拟资金分录，不给会员钱包加减款，不建立可提现余额，不代表正式财务收入确认或税务口径。金额字符串是防止静默舍入的过渡表示，不能用字符串字典序比较金额。

显式零单价测试订单可产生金额0的两条模拟分录，仍执行授权/文件验证和幂等；不由此推定真实零元订单、免费额度、赠送政策。

### `print_job`（本批只排队，后续C扩展执行）

- id CHAR36 PK；order_id/order_item_id/payment_receipt_id/file_id CHAR36；tenant/store/owner BIGINT。
- file_content_sha256 CHAR64，复制已核对的order_item内容摘要。
- source VARCHAR32=ADMIN_DEMO；simulation BIT=1；generation INT=0。
- dispatch_state VARCHAR32=QUEUED；print_result VARCHAR32=NOT_REPORTED；version BIGINT=1。
- attempt_count INT=0；terminal_id可空；lease_owner可空；lease_token_hash可空；lease_expires_at_ms可空；fencing_version BIGINT=0。
- created_at_ms/updated_at_ms BIGINT，初始相同。
- UNIQUE(order_item_id,generation)；复合FK(scope,order_id)→order，FK order_item_id→item，复合FK(scope,payment_receipt_id)→receipt；队列索引(scope,dispatch_state,created_at_ms,id)。
- 本批更新一律拒绝；所有租约字段必须空/0，不能通过支付请求预设终端。DELETE仅root@localhost维护，应用不可删除排队记录。

job引用immutable item即可追溯纸张/份数/页数/价格，不复制PDF文件或大blob。C批再建立job_attempt/job_event、终端身份与可验证租约/回执；不能现在填假接收/队列/出纸时间。

## 5. 同事务算法与锁序

事务隔离 READ_COMMITTED。沿A的顺序扩展：员工映射 → 文件（未来多文件按ID排序）→ 订单 → receipt/journal/job/event写入。B不重新锁quote或从quote重新计价。读取已有订单/项/receipt用于定位与重放时采用非锁定查询；不能先锁order再去锁file而与其他路径倒置。

1. 请求格式、配置、权限检查；进入事务requireCurrent并锁当前staff-store映射，串行化同owner的支付/下单请求，避免同key并发幻影。
2. 无锁读取本人order并确认范围与source/simulation；优先查该scope下keyHash成功receipt。相同order/hash返回原结果；不同order/hash冲突。
3. 若该order已有成功receipt或payment_state=SUCCEEDED，返回ALREADY_PAID；状态与receipt不一致属于存储/状态异常，不能再收一次弥补。
4. 读取恰好一个本人order_item并验证其immutable snapshot/amount/ID与order一致。定位file，按scope+owner锁file；随后按同scope锁order，重新检查版本1及OPEN/UNPAID/NONE/NONE/NOT_APPLICABLE/NOT_CREATED。
5. 锁等待后重读时间。file必须READY、有有效正页数、未到期；content_sha256、page_count与item snapshot相等；item.fileId与file一致。文件元数据不存在/被删/过期/变化均拒绝，业务零写入。
6. 本次支付使用已经创建订单的固定价格，不要求原quote仍在有效期（quote有效性已在下单时消费）；仍必须要求文件当下可用。本批无擅自新增自动取消或付款期限，仅沿用明确文件剩余有效期，不延长它。
7. 生成receiptId/jobId、服务端当前时间，插入receipt；插入成对分录；插入唯一job(QUEUED/NOT_REPORTED)；追加order_event(type=PAYMENT_SIMULATED,version2,实际STAFF)。
8. 再次检查当前时间仍早于锁定file.expires_at_ms，避免写入阶段期间到期；更新order仅payment_state=SUCCEEDED、print_state=QUEUED、version=2，WHERE明确旧状态/version/scope。更新条数必须1，否则全回滚。
9. 事务提交后返回receipt。步骤7–8之间任一点失败，receipt/两分录/job/event/order状态全回滚；不调用外网、不把任务放内存队列。主库即持久化队列，不引入Redis队列或MQ。

记录创建/提交的检查时间只能保证该业务决策时文件有效，不能保证文件永不过期。领取时C仍须锁文件再校验；文件随后失效不能伪装已打印。本批不创建文件永久pin、不复制blob、不修改expires；取消/退款补偿规则待确认，不通过超时自动退款或补打。

## 6. A→B 受控迁移方案

当前真实A约束已经只读核对：`chk_print_order_initial`锁OPEN/UNPAID/NONE/NONE/NOT_APPLICABLE/NOT_CREATED/version1；`trg_print_order_immutable`拒绝所有UPDATE；`chk_print_order_event_initial`只允许ORDER_CREATED/version1。item UPDATE与event UPDATE/DELETE guard必须保留。

### 新允许组合

订单仅允许两种组合：

- 初始：OPEN / UNPAID / NONE / NONE / NOT_APPLICABLE / NOT_CREATED / version1。
- 已模拟收款排队：OPEN / SUCCEEDED / NONE / NONE / NOT_APPLICABLE / QUEUED / version2。

新order UPDATE guard：所有字段除payment_state、print_state、version外均使用NULL-safe `<=>`逐列比较、变化即SIGNAL；上述三列只允许从初始组合一次转为成功排队组合，不允许逆转、重复推进或跳到PRINTED。旧快照、金额、身份、orderNo、source/simulation、币种、createdAt、幂等字段及其他状态始终不可改。No-op UPDATE也可直接拒绝，重放通过SELECT处理。

事件仅允许两种组合：ORDER_CREATED/version1或PAYMENT_SIMULATED/version2；actor仍STAFF且等于owner，simulation=1。现event UPDATE拒绝与本机root DELETE维护规则不变，unique(order_id,aggregate_version)不变。原ORDER_CREATED不能改成支付事件。

### 迁移步骤（由root串行执行）

1. 关模拟收款开关、暂停本批写测试、记录旧DDL/trigger摘要与业务行数；空闲≥10GiB，获取迁移/fixture锁，执行同机加密备份恢复验证。验证实际库确为A或完整B状态；未知中间状态fail closed，不用盲目DROP。
2. 增量创建三张新表及不可变/维护DELETE guard，无业务seed。已有同名表必须验证完整列/索引/外键/触发器后才可重入。
3. **先安装新名order保护trigger，再移除旧全禁止trigger。** MySQL支持同表多BEFORE UPDATE trigger；两个同时存在时旧trigger仍拒绝写。新trigger上线后才能drop旧trigger，不出现无保护窗口。
4. 先添加允许A+B两组合的新CHECK，再drop旧仅A CHECK；事件同样先加新CHECK再drop旧CHECK。DDL有自动提交，不能声称所有迁移在单事务内回滚；对每个步骤做前置与后置指纹核验，失败保留至少一个拒绝/保护约束并停止。
5. 检查order immutable字段列表完整、两种状态组合、event append-only、receipt/journal/job唯一性；验证A历史行仍满足且未改写。记录证据，不启用服务。
6. 新代码构建/迁移一致后，由root显式开测试配置，再进行独立验收。主运行默认仍关闭新支付开关。

**防旧迁移回装：** 原 `migrate-print-orders.py` 会寻找缺失的旧trigger，若B后直接重跑可能把全禁止trigger装回。B实施时须经root授权给A迁移增加“B已安装则拒绝降级/提示superseded”的前置检测，或统一版本迁移记录守卫；不能仅靠文档让维护者记住。不得自动用A模板覆盖B保护。

**恢复边界：** 已产生B付款/job后不能直接恢复A CHECK或运行旧代码并称回滚成功，因为A拒绝这些合法新行。优先修复或关闭写入后保留B数据查询；真正恢复备份需明确数据损失窗口与另行恢复授权。演示测试夹具可由root按精确ID先删事件2→job→分录→receipt，再对专用测试订单作受控清理；不能在真实历史订单上改回未付款来“回滚”。

## 7. 错误码建议（1510040xxx）

|code|HTTP|含义/副作用|
|---|---|---|
|1510040000|503|模拟收款写功能关闭；无业务写入|
|1510040001|400|UUID/key/body/query格式不合法|
|1510040002|403|mode不是显式DEMO或当前环境不允许模拟收款|
|1510040003|404|本人订单不存在（含跨owner/store）|
|1510040004|409|同key关联不同输入订单|
|1510040005|409|该订单已收款，不能用新key再次收款|
|1510040006|409|订单状态/版本不允许首次模拟收款|
|1510040007|404|本人订单文件元数据不存在|
|1510040008|409|文件未READY、页数无效、已删除或已过期|
|1510040009|409|订单/item/文件摘要、页数或金额快照不一致|
|1510040010|503|数据库/事务存储失败，全部回滚，不暴露SQL|
|1510040011|403|加锁时员工门店映射失效|
|1510040012|403|订单不是允许的ADMIN_DEMO/simulation/STAFF来源|
|1510040013|404|本人订单尚无付款receipt（历史GET）|
|1510040014|503|已保存receipt与订单/成对分录/job不一致，停止重复收款并告警|

整体orders功能关闭、scope异常和框架401/403复用已有码，不绕过旧门控。错误响应保持输入无关安全文案，不回显幂等键、数据库错误、认证token、密码或私有文件路径。

金额0.123、0.015等作为精确演示数据可以保存、成对核对，不转换成分、不四舍五入；界面明确模拟金额。真实结算精度、最低消费、优惠分摊未确认，因此没有对应真实付款/退款实现。

## 8. 独立验收清单

所有HTTP均对测试实例、明确合成订单和测试价格执行；秘钥只在私有文件/内存。付款开关默认false，验收结束关闭并清理自己精确夹具，不对原站执行任何写操作。

### 成功、不变量与历史

- 一个未支付订单成功后：receipt=1，DEBIT=1，CREDIT=1，借贷金额精确相等；job=1且QUEUED/NOT_REPORTED/attempt0/lease空；order=OPEN/SUCCEEDED/NONE/NONE/NOT_APPLICABLE/QUEUED/version2；order事件恰好created/v1与payment/v2。
- receipt金额等于immutable order total、两分录金额及币种；job文件/摘要/owner来自原item，无额外PDF副本。实际actor是当前员工。
- 0.80、1.65、1.80、0.369等独立字面预期保留精度；0金额测试不跳过授权/文件/幂等，也不声称免费政策已实现。
- 既有quote/profile价格版本及order/item快照未改变。POST成功不修改文件expires；单号/创建时间/幂等数据未变。
- 重启后GET原receipt、订单事件与QUEUED job仍存在；没有真实外部HTTP付款请求或打印动作。
- 主进程当前订单只读UI须适配新的SUCCEEDED/QUEUED状态显示；不能继续仅接受A的UNPAID/NOT_CREATED而误报数据错误，也不把QUEUED渲染为打印完成。

### 并发与幂等

- 6个以上并发同key同order：全部返回相同receipt/jobIds，DB仅一收款、两分录、一job、一支付事件，version只增加一次。
- 两个不同key并发同order：仅一条成功，其他ALREADY_PAID；不同key不能绕过唯一order收款约束。
- 同key分别对两个本人order并发：仅一个order成功，另一个IDEMPOTENCY_CONFLICT；败方完全未支付且无job/分录。
- 两个不同owner可以各自用同一字面key支付自己的order，互不串单；错误owner不能读取/重放他人的receipt。
- 成功响应丢失后同key重试；成功后文件过期/删除、quote到期，同key仍返回原receipt，不产生新资金或任务；换key仍ALREADY_PAID。
- 首次文件失效时失败，key不被消费；重试也不能凭已有请求缓存造成功结果。

### 文件与时间边界

- 非READY、DELETED、过期、无元数据、摘要变化、页数变化逐项拒绝，订单仍初始状态，receipt/journal/job/payment event都0。
- root控制的数据库锁让请求等待到文件过期，释放后仍拒绝；验证锁等待后取时间，而不是用请求进入时间。
- 写入中到期需在最终订单状态更新前重查并回滚；可用受控数据库锁/延迟夹具验证，不在公开API增加sleep或故障注入参数。
- quote已过期但order已合法创建且file仍有效：允许按固定订单价模拟收款；这与“首次下单必须quote有效”是不同边界。
- 本批成功后file继续按原expires失效，不自动补打/退款；后续领取必须再次校验。支付时有效不能替代C批领取时有效性证据。

### 中途故障的真实回滚

仅测“前置校验失败”不足以证明全事务。独立验收至少在两个中途位置强制失败：

1. receipt及第一条分录写入后，第二条分录写入失败。
2. job插入后，订单状态更新或追加支付事件失败。

可由root在唯一测试orderId上创建短生命周期数据库故障trigger，或使用受控测试DataSource；不添加匿名debug API，不用全局失败开关扰动其他任务。fixture-mutation锁串行，finally核验定义/名称后删除自己创建的trigger，未知trigger不删除。响应需为存储失败，断言原order仍version1，receipt/journal/job/payment event均0；去掉故障后同key一次成功。另测提交成功但客户端丢响应，必须重放原receipt，不能再次记账。

### 身份、环境、来源与DB不可变性

- 匿名401、reader缺模拟权限403、错误tenant/visitTenant/无门店/禁用员工按既有机制拒绝；有模拟权限的另一owner仍不能支付/查看他人订单。
- 真实独立第二tenant/store夹具验证跨范围拒绝；不存在tenant不能替代实际隔离验证。
- enabled=false、mode缺失、mode=PRODUCTION/未知分别拒绝；body/query伪造store/user/source/simulation/amount/status/targetTerminal等字段400。
- 当前DB CHECK禁止非DEMO或非simulation订单，不能为了HTTP负例随意移除生产约束。持久化非法来源判断用独立服务测试夹具/仓库替身验证，同时保留DB CHECK拒绝证据；不能把请求多字段400误称为已覆盖服务端源记录校验。
- 应用账号UPDATE订单金额/身份/source/币种/createdAt/key、UPDATE item快照、UPDATE receipt/journal/event、DELETE支付记录/分录/事件均拒绝；允许的状态转换只能初始→SUCCEEDED/QUEUED/version2，不允许越级PRINTED、逆转或重复version++。
- root@localhost可删除精确合成夹具，应用账号不可。验证trigger使用登录USER，不是definer CURRENT_USER；不得把root可维护描述成应用可删除。

## 9. 批次交付与未完成边界

建议root审查后按顺序：冻结合同 → 后端支付领域/受控状态迁移 → 独立事务与并发验收 → 只读UI状态适配 → 再做C的领取/回执。每步记录源码提交、release、迁移摘要、实际预期对照、清理结果；迁移和构建保持串行。

B通过只能关闭“受权模拟成功收款及唯一排队任务”子项。钱包并发扣减、充值/赠送、退款限额及分摊、外部真实渠道/回调、租约领取/终端回执、未知结果、取消竞态、补打收费、配送/取件、全功能外观均未因此完成。尚待确认的资金与业务规则继续登记，但不阻断本批明确授权的成功路径。

本次仅新建本文，无Java/DDL/构建/数据库写入/重启。无SQL导出、测试PDF、依赖包或编译中间产物可清理。所有后续操作仍需≥10GiB磁盘余量，同机加密备份不能冒称异地恢复。

## 10. 本次只读核对文件摘要

- `docs/orders/order-foundation-v1.md` SHA-256 `6cc47f39ce51d7c7d23a0cdf807b0a4b9dd9cf78e444188c6c2d8709b814cf85`
- `ops/db/print-orders-v1.sql` SHA-256 `e4f50ad51e2848b85c21af84ffd738cdcd957d7af49bee4370cb6f03fcbcb3bc`
- `ops/db/migrate-print-orders.py` SHA-256 `aba7578984978f3701c55685868f591dbfdb2bbfe8ddb1385be332f9971cbcb4`
- `docs/confirmed-pricing-decisions.md` SHA-256 `2beed39b62095706d123d30635ea59a7ac7e9e424a18c94483fc9a4c1383767a`
