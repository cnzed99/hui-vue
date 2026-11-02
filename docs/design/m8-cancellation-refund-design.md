# M8 订单取消、模拟退款与人工核实设计

状态：`DESIGN_REVIEW_REQUIRED`  
范围：新系统独立演示订单；不调用真实支付、退款或打印设备。  
基线：后端 `e2efa424a860b532b39861d68c96e9743e9278e1`，小程序 `d022b43bc55414e7f7dcf5bee595b57dacca6c01`，管理后台 `fc776725b78f611fc17028033094a98c376879bd`。

## 1. 用户已确认规则

1. 未付款订单可以取消。
2. 已模拟付款且打印任务尚未领取，可以取消并全额模拟退款。
3. 打印任务已经领取或打印结果不明时，转人工核实；不自动退款、不自动重打。
4. 退款只作用于新系统模拟资金，不调用微信支付，不表示真实资金到账。

本设计把“尚未领取”严格解释为：

- 没有打印任务；或
- 任务同时满足 `state=QUEUED`、`version=1`、`eventSeq=1`、`fence=0`、`leaseId=NULL`、`leaseUntil=NULL`。

任务曾经领取后即使通过 `REQUEUE_EXPIRED_CLAIM` 回到 `QUEUED`，其 `fence>0`，仍必须转人工核实。

## 2. 本批明确不做

- 不实现真实支付或真实退款。
- 不在人工核实后自动决定退款、重打或拒绝；人工结论与后续动作尚无用户规则。
- 不允许现有后台 `print:order:process` 权限直接处理退款或核实结论。
- 不修改既有金额算法、阶梯价、文件计价范围或打印防重复规则。
- 不删除、覆盖或改写既有付款收据、付款账本、任务动作和打印回执。

## 3. 服务端权威结果

客户端只能请求动作，不能自行判断资格。服务端在事务锁内返回以下三种结果之一：

| 当前权威状态 | 动作结果 | 订单变化 | 任务变化 | 资金证据 |
| --- | --- | --- | --- | --- |
| `OPEN/UNPAID/NONE/NOT_CREATED/v1`，无任务 | `CANCELLED_UNPAID` | `CANCELLED/UNPAID/NONE/NOT_CREATED/v2` | 无 | 无退款 |
| `OPEN/SUCCEEDED/NONE/NOT_CREATED/v2`，无任务 | `CANCELLED_REFUNDED` | `CANCELLED/SUCCEEDED/SUCCEEDED/NOT_CREATED/v3` | 无 | 全额模拟退款收据和反向账本 |
| `OPEN/SUCCEEDED/NONE/QUEUED/v3`，任务为首次排队且 `fence=0` | `CANCELLED_REFUNDED` | `CANCELLED/SUCCEEDED/SUCCEEDED/CANCELLED/v4` | `QUEUED/v1 → CANCELLED/v2`，不生成打印失败回执 | 全额模拟退款收据和反向账本 |
| 任务曾领取，或处于 `CLAIMED/ACCEPTED/EXECUTING/SUCCEEDED/FAILED/RESULT_UNKNOWN` | `MANUAL_REVIEW_REQUIRED` | 不变 | 不变 | 不自动退款 |

任何不符合上述组合的记录视为存储不一致，不猜测修复。

## 4. HTTP 契约

### 4.1 用户端读取

`GET /app-api/print/miniapp/demo/cancellation?orderId=<uuid>`

- 必须有效演示会话、允许的 Origin/Host，并按 tenant/store/principal 隔离。
- 返回服务端计算的 `capability` 和已有 `cancellation`：

```json
{
  "capability": {
    "action": "CANCEL_UNPAID | CANCEL_AND_REFUND | REQUEST_REVIEW | NONE",
    "reason": "固定白名单原因",
    "expectedVersion": 2
  },
  "cancellation": null
}
```

- `capability` 只用于显示；POST 必须再次在锁内复核。
- 跨主体和不存在订单统一 404；响应 `no-store`。

### 4.2 用户端写入

`POST /app-api/print/miniapp/demo/cancellations`

请求体只允许：

```json
{"orderId":"uuid","expectedVersion":2}
```

同时要求 `Idempotency-Key`。响应返回：

- `originalResult`：本次冻结的 outcome、原因、订单/任务版本前后值和发生时间；
- `currentOrder`：取消或人工申请后的当前订单视图；
- `refundReceipt`：仅自动模拟退款时存在，包含原付款关联、`SIMULATED/DEMO/SUCCEEDED`、币种、金额、`simulation=true` 和两条反向账本；
- `reviewSummary`：仅人工核实时存在，包含冻结的任务状态、版本、fence 和申请时间，不包含处理结论。

网络结果未知时，小程序先 GET 恢复；若未找到记录，才使用原幂等键和原 expectedVersion 重试。不得换键猜测重发。

页面显示人工结果时必须使用明确文案：`申请已登记，原任务尚未被停止，当前未退款`，不得把人工申请描述为取消成功。

## 5. 幂等与并发

- 事务隔离：`READ_COMMITTED`，但所有业务判断必须基于 `FOR UPDATE` 锁定行。
- 锁顺序：有效演示主体 → 本人订单 → 已有取消记录/幂等记录 → 订单任务 → 原付款收据及原付款账本 → 新退款证据。
- 处理顺序统一为：锁定本人订单后，先检查已有同键记录并按 input 哈希重放；没有同键记录时必须满足 `expectedVersion == currentOrder.version`，否则不写任何记录并返回 `STALE_VERSION`。不对付款、建任务或领取竞态设置例外。
- 与付款并发：取消先完成则付款失败；付款先完成则旧 expectedVersion 返回 `STALE_VERSION`，客户端刷新后由用户重新确认“取消并模拟退款”。
- 与建任务并发：取消先完成时建任务拒绝；建任务先完成时旧 expectedVersion 返回 `STALE_VERSION`，刷新后服务端 capability 才显示首次 `QUEUED/fence=0` 的“取消并模拟退款”。
- 与领取并发：取消先完成时任务为 `CANCELLED`、领取拒绝；领取先完成时旧 expectedVersion 返回 `STALE_VERSION`，刷新后 capability 才显示“申请人工核实”，再次确认后登记 `MANUAL_REVIEW_REQUIRED`。
- 同幂等键、同 canonical input 即使订单版本已经因原成功动作变化，也先返回同一冻结结果；同键不同 input 固定冲突。
- 同订单已有记录但使用不同幂等键时，不依赖数据库唯一键异常：固定返回 `CANCELLATION_ALREADY_RECORDED`，包含已有 cancellationId/outcome，客户端随后 GET 恢复；不新增第二条记录。
- 每订单最多一条取消/人工核实记录、最多一张模拟退款收据、最多两条反向账本。
- 人工核实记录不改变订单 version、任务 version、任务状态或 fence，避免破坏后续合法任务动作的 `orderVersion = taskVersion + 2` 约束。

## 6. 数据库增量

### 6.1 新增不可变表

1. `print_miniapp_demo_cancellation`
   - 记录 owner、order、幂等/input 哈希、outcome、固定原因、订单版本前后值、任务快照/变化、simulation 和时间。
   - `(tenant_id,store_id,principal_id,order_id)` 唯一；幂等键唯一约束为 `(tenant_id,store_id,principal_id,idempotency_sha256)`，不同主体可安全复用相同键。
   - 若首次 QUEUED 任务被取消，记录 `taskVersionAfter=2`、`taskEventSeqAfter=2`，并对 `(taskId, taskEventSeqAfter)` 唯一；该表就是用户取消任务的不可变动作证据，不伪装成后台员工动作。
2. `print_miniapp_demo_refund_receipt`
   - 关联 cancellation、order、原 payment receipt；固定 `provider=SIMULATED`、`mode=DEMO`、`state=SUCCEEDED`、`simulation=1`。
   - 金额和币种必须与原付款及冻结订单完全相同；每订单唯一。
3. `print_miniapp_demo_refund_journal`
   - 固定两条：`DEBIT/DEMO_STORE`、`CREDIT/DEMO_CUSTOMER`；金额和币种与退款收据一致。

三表均增加禁止 UPDATE/DELETE 的触发器；既有不可变触发器保持不变。

### 6.2 复合关联与精确 CHECK

- cancellation → order 使用 `(tenant_id,store_id,principal_id,order_id)` 复合外键；可选 task 关联使用 `(tenant_id,store_id,principal_id,task_id,order_id)`，禁止跨主体或同主体跨订单串联。
- refund receipt → cancellation、原 payment receipt、order 均使用带完整 owner 和 order 的复合外键；每个 owner/order、cancellation 和原付款各最多一张退款收据。
- refund journal → refund receipt/order 使用完整 owner/order 复合外键，并以 `(tenant_id,store_id,principal_id,refund_receipt_id,side)` 唯一，精确要求一条 `DEBIT/DEMO_STORE` 和一条 `CREDIT/DEMO_CUSTOMER`。
- cancellation outcome CHECK 采用互斥 NULL 分支：
  - `CANCELLED_UNPAID`：无 task/refund，订单版本 `1→2`；
  - `CANCELLED_REFUNDED` 无 task：订单版本 `2→3`；
  - `CANCELLED_REFUNDED` 首次 QUEUED：订单版本 `3→4`、任务 `QUEUED/v1/event1/fence0 → CANCELLED/v2/event2/fence0`；
  - `MANUAL_REVIEW_REQUIRED`：必须有任务快照、任务状态/版本/fence 前后完全相同，订单版本前后相同，且不允许 refund 关联。
- 生成退款前必须在同一事务内验证原付款收据、原 `DEBIT/DEMO_CUSTOMER` 与 `CREDIT/DEMO_STORE` 两条账本、冻结订单金额和币种完全一致。
- 所有金额沿用规范十进制字符串并按既有 verifier 比较，不转换为 `double/float`；取消记录、退款收据、两条反向账本及订单/任务状态必须在同一事务提交或全部回滚。

### 6.3 扩展保护约束

- `print_miniapp_demo_order.chk_pmdo_state` 仅增加第3节列出的三个取消终态组合；保留所有原组合。
- `print_miniapp_demo_order_event.chk_pmdoe_type` 增加 `ORDER_CANCELLED_UNPAID` 和 `ORDER_CANCELLED_REFUNDED`，actor 仍为本人 `MINIAPP_DEMO`。人工核实不增加订单聚合版本，因此不写 order event。
- `print_miniapp_demo_print_task.chk_pmdpt_state/lease` 增加无租约、`fence=0` 的 `CANCELLED` 终态。
- 既有 `print_task_action` 表和后台 actor 约束不放宽；用户取消任务由 cancellation 表记录。
- 迁移必须先核对历史数据全部满足旧约束，再在同一受控窗口替换 CHECK。MySQL DDL 自动提交，必须有数据库备份、逐步验证，以及“无 M8 写入时撤回兼容迁移”和“已有 M8 写入时保留 schema 前向修复”两套恢复步骤；未获 Astra High 最终审查不得执行。

## 7. 读取兼容

- owner 的 cancellation GET、订单列表/详情/确认页聚合读取，以及管理后台列表/详情新增字段，均在一个只读 `REPEATABLE_READ` 快照内读取订单、capability、取消记录、退款收据/账本和任务快照并做组合校验，禁止撕裂读取。
- 不存在取消记录是成功响应中的明确 `cancellation:null`；401、404、超时、非 JSON、解析失败或响应代次过期都不能被客户端当作“没有执行，可以换键重发”。
- 订单 DTO/解析器增加 `orderState=CANCELLED`、`refundState=SUCCEEDED` 和 `printState=CANCELLED` 的精确合法组合，不接受任意字符串。
- `getForOwner`：取消且无任务时返回 `null` 打印结果，而不是因 `order.version>2` 报存储异常；取消任务时返回 `CANCELLED` 且无打印回执。
- task view 只要求 `SUCCEEDED/FAILED` 必须有对应打印回执；`CANCELLED` 是无回执终态。
- `requireTaskOrder` 区分正常打印链和已取消链；取消任务只能与 `orderState=CANCELLED/refundState=SUCCEEDED/printState=CANCELLED`、相同 owner/order 和正确版本关系共存。
- 管理订单详情/列表增加取消 outcome、模拟退款收据和人工核实徽标，但不得把人工申请当作退款成功。

## 8. 权限与前端

- 小程序：现有订单详情显示服务端 capability；对应按钮为“取消订单”“取消并模拟退款”或“申请人工核实”。动作前明确二次提示模拟边界；结果未知时进入恢复态。
- 小程序把待确认请求以 `principalId + orderId + expectedVersion + idempotencyKey` 绑定保存；页面隐藏/重新进入时先恢复，切换会话或 principal 时废弃旧主体响应，绝不把旧响应写入新主体页面。
- 订单列表、详情、确认页同步展示取消/退款/人工核实状态；取消后不得继续支付、创建订单或进入打印操作。
- 管理后台复用现有小程序订单列表和详情，不新增演示专页。
- 具有 `print:order:query` 的管理员可只读查看取消、模拟退款和人工核实记录；本批没有人工处理写操作。
- `print:order:process` 继续只处理正常打印任务，不获得退款或人工核实处置能力。未来新增人工结论/退款写权限时必须另行确认并再次 Astra High 审查。

## 9. Nginx 与配置

- 只发布精确 GET `/app-api/print/miniapp/demo/cancellation` 和 POST `/app-api/print/miniapp/demo/cancellations`。
- 其它方法在代理前拒绝；POST 请求体上限 1KiB；转发 Authorization/Origin/Host，响应 `no-store`。
- 写入口使用独立或现有低频订单写限流区，不扩大 `/app-api/` 兜底。
- 新功能有独立 enabled 配置，默认开发环境显式开启；没有真实渠道密钥。

## 10. 最少充分验证

1. 未付取消；取消后付款拒绝。
2. 已付无任务全额模拟退款。
3. 已付首次 QUEUED/fence0：任务无回执取消、全额模拟退款，之后领取拒绝。
4. 付款、建任务或 CLAIM 先赢锁时，旧 expectedVersion 均返回 `STALE_VERSION` 且不写取消记录；刷新并再次确认后才执行新 capability。取消先赢锁时，后续付款/建任务/CLAIM 拒绝。
5. 曾领取后重回 QUEUED/fence>0、CLAIMED、ACCEPTED、EXECUTING、SUCCEEDED、FAILED、RESULT_UNKNOWN 均只登记人工核实，不改订单/任务版本，不自动退款或重打。
6. 成功动作后旧 expectedVersion 的同键同 input 仍重放原结果；同键异 input 冲突；同订单异键固定 `CANCELLATION_ALREADY_RECORDED`；服务重启后恢复同一结果。
7. 跨主体隐藏；无效 Origin、无会话、相邻路径和错误方法拒绝。
8. 退款金额等于冻结订单及原付款，反向账本借贷各一条且平衡；付款、退款、取消记录和账本不可更新/删除。
9. 取消后后台不能建任务；人工核实订单仍可按原任务状态继续合法动作，版本 CHECK 不被破坏。
10. 小程序页面恢复网络未知结果，列表/详情/确认页状态一致；后台只读显示人工核实，不出现退款/重打按钮。
11. owner/admin 聚合读取在并发取消提交前后只能看到完整旧快照或完整新快照，不得出现“仍可取消但已有退款收据”的组合。
12. 分阶段发布分别演练“无 M8 写入可撤回兼容迁移”和“已有 M8 写入关闭 POST、保留兼容读取并前向修复”。

## 11. 分阶段发布与兼容回滚

1. 数据库备份并验证后，先应用只增加兼容表/复合键及“旧状态 + 新状态”超集 CHECK 的迁移；此时取消写功能保持 `enabled=false`，旧服务不能产生 M8 数据。
2. 部署兼容读取的后端、小程序和管理后台：三端都能解析旧状态及新取消/退款状态，但 POST 仍关闭。验证所有历史订单读取、任务动作和现有闭环不回归。
3. 确认三端兼容版本生效后，才通过配置开启新的 cancellation POST，并执行受控开发数据验证。
4. 功能开关关闭时只禁止新取消请求；既有取消记录、退款收据和人工核实记录必须继续可读。
5. 尚无任何 M8 写入时，可在确认数据库无新状态/新记录后回退应用并按已审脚本撤回兼容迁移。
6. 一旦存在任一 M8 取消、退款或人工核实记录，禁止自动恢复旧数据库备份、旧 CHECK、旧后端或旧前端解析器；故障处置为关闭 POST、保留新 schema/证据及兼容读取版本并前向修复。
7. 最终迁移与部署演练必须分别验证“尚无 M8 写入可回退”和“已有 M8 写入只能关闭写入并前向修复”两条恢复路径。

## 12. 发布门禁

1. Astra High 设计 `PASS`。
2. Sol 实现后完成构建、隔离迁移候选和并发/账务验证。
3. Astra High 对最终差异、迁移、权限、金额/账本和运行证据再次 `PASS`。
4. 仅在数据库备份、标准全窗口锁和第11节双路径恢复验证齐备后部署开发环境。
5. 真实支付退款、真实打印机、微信真机和外部用户验收继续标记 `NOT_RUN`。
