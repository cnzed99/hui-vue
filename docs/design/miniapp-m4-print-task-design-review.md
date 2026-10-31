# M4 小程序订单后台处理、模拟打印与结果查询设计（Astra High 审查候选）

## 范围与边界

- 延续 M3 的 `MINIAPP_DEMO` 独立主体、隔离表和回环环境；不接真实打印机、终端协议、微信能力或真实资金。
- 本批完成：配套管理后台查看门店内小程序订单、为已模拟支付订单创建唯一模拟打印任务、逐步驱动模拟终端状态、写不可变模拟回执、小程序查看任务和结果。
- 不新增纯展示后台页；扩展现有订单页，增加“小程序订单”视图和必要处理动作。员工端继续暂缓，只有已登录管理员且具备服务端权限的账号可操作。
- 取消、退款、补打、部分成功和真实终端适配不在本批；`RESULT_UNKNOWN` 不自动重发或新建任务，只显示人工核实。

## 权限与作用域

- 管理端读取沿用 `print:order:query`；创建任务和驱动模拟状态使用新增 `print:order:process`。Controller 必须 `@PreAuthorize`，页面隐藏按钮不是权限证据。
- 服务端用 `PrintScopeService.requireCurrent()` 获取 staff 的 tenant/store/user；请求体和路径均不能覆盖 tenant/store。列表和动作只访问该 scope 的 `MINIAPP_DEMO` 订单。
- 小程序查询继续由 bearer 映射到 tenant/store/principal；跨 principal 统一 404。管理端不能使用小程序 bearer，小程序不能调用管理端处理接口。
- 首期只有一个管理员/一个门店；多门店仍依赖一名员工恰好一个有效门店映射，歧义或停用均拒绝。

## 订单与任务状态

- 仅 `OPEN/SUCCEEDED/NONE/NOT_CREATED/version=2` 的订单可创建任务；再次创建返回原任务，不生成第二个 job。
- 任务主链：`QUEUED -> CLAIMED -> ACCEPTED -> EXECUTING -> SUCCEEDED`。
- 终态另列：`FAILED`、`RESULT_UNKNOWN`。本批不实现 `CANCELLED`、`PARTIAL` 或从终态返回队列。
- 订单 `print_state` 投影任务状态；创建任务和每次状态变化在同一事务 CAS 更新订单 version，并写下一版本订单事件。支付状态、金额、退款状态不改变。
- 任务创建要求文件仍为 `READY`、未过期，摘要/页数与订单 item 冻结快照一致；不满足时拒绝并保持已支付/未建任务，进入后台可见的“需人工处理”提示，不自动退款。

## 打印防重复与租约/fencing

- 每个订单 item 只有一个逻辑 task，数据库唯一 `(tenant,store,principal,order_item_id)`；稳定 `jobId` 永不因重试变化。
- `CLAIM` 只从 `QUEUED` 进入；生成随机 `lease_id`、递增 `fence`、短租约 `lease_until_ms`。过期 `CLAIMED` 不能直接再次 CLAIM，必须先执行下表的 `REQUEUE_EXPIRED_CLAIM`。
- `ACCEPT/START/COMPLETE` 必须提交 taskId、leaseId、fence、expectedVersion 和稳定幂等键；旧 lease/fence、过期租约、错误版本或错误前置状态拒绝。
- `CLAIMED` 在 ACCEPT 前租约过期，由管理员显式 `REQUEUE_EXPIRED_CLAIM` 恢复为 `QUEUED`；到期统一判定为 `now >= leaseUntil`，该动作不要求旧租约仍有效，保留当前 fence，下一次 CLAIM 再递增 fence。
- `ACCEPTED/EXECUTING` 租约过期由管理员显式 `MARK_TIMEOUT_UNKNOWN` 转 `RESULT_UNKNOWN`；该动作不要求旧租约仍有效。任何 ACCEPTED 之后的超时、文件失效或回执丢失都不得回队列或生成第二任务。
- 每个 task 事件唯一 `(task_id,event_seq)`；每个动作幂等唯一 `(scope,task,idempotency_sha256)`。并发同动作最多一次推进版本。

### 完整动作转换表

| 动作 | 前置状态 | 额外条件 | 后置状态 | 回执/完成数量 |
| --- | --- | --- | --- | --- |
| `CREATE_TASK` | 订单 `NOT_CREATED/v2` | 已付；文件 READY、未过期、摘要/页数一致 | `QUEUED` | 无回执 |
| `CLAIM` | `QUEUED` | 文件仍 READY/未过期；生成新 lease；`fence=fence+1` | `CLAIMED` | 无回执 |
| `REQUEUE_EXPIRED_CLAIM` | `CLAIMED` | `now >= leaseUntil`；不要求旧 lease 有效 | `QUEUED` | 无回执 |
| `ACCEPT` | `CLAIMED` | leaseId/fence 匹配且未过期；文件仍 READY/未过期 | `ACCEPTED` | 无回执 |
| `START` | `ACCEPTED` | leaseId/fence 匹配且未过期 | `EXECUTING` | 无回执 |
| `SUCCEED` | `EXECUTING` | leaseId/fence 匹配且未过期；`receiptSeq=1` | `SUCCEEDED` | 全量计划份数/张数完成 |
| `FAIL` | `ACCEPTED` 或 `EXECUTING` | leaseId/fence 匹配且未过期；`receiptSeq=1` | `FAILED` | 完成份数/张数固定为0，记录模拟失败原因 |
| `LOSE_RECEIPT` | `ACCEPTED` 或 `EXECUTING` | leaseId/fence 匹配；可在租约有效时主动注入 | `RESULT_UNKNOWN` | 不写完成回执，完成数量为未知/null |
| `MARK_TIMEOUT_UNKNOWN` | `ACCEPTED` 或 `EXECUTING` | `now >= leaseUntil`；不要求旧 lease 有效 | `RESULT_UNKNOWN` | 不写完成回执，完成数量为未知/null |
| `ABORT_FILE_UNAVAILABLE` | `QUEUED` 或 `CLAIMED` | 锁文件后确认已失效/过期/摘要不符 | `FAILED` | `receiptSeq=1`，完成数量0，原因 `FILE_UNAVAILABLE_BEFORE_ACCEPT` |

- task 创建时 `version=1/eventSeq=1`，订单从 version2 到 version3；以后每个首次成功状态动作都使 `task.version+1`、`eventSeq+1`、`order.version+1`，并在同一事务保存动作结果。重复幂等重放不得增加任何 version、eventSeq 或 receiptSeq。
- `receiptSeq` 本批每个 task 最多为1：只允许一个终态回执；`LOSE_RECEIPT/MARK_TIMEOUT_UNKNOWN` 没有回执。终态后所有新动作拒绝，原幂等键仍可返回其历史动作结果。

## 模拟器动作与回执

- 本批不暴露匿名终端接口。管理员页面的“模拟终端”动作调用受 `print:order:process` 保护的管理端 API，并以 `actor_type=STAFF_SIMULATOR`、当前 userId、`simulation=true` 记录；不能标成真实设备回执。
- 动作采用上表全集。UI 按当前状态只显示合法下一步，并在每次动作前说明模拟边界；超时恢复和文件失效终止均为管理员显式动作，本批没有后台自动扫描器。
- 只有写回执的 `SUCCEED/FAIL/ABORT_FILE_UNAVAILABLE` 携带 `receiptSeq=1`；数据库唯一 `(task_id,receipt_seq)` 和动作幂等共同防重复。`LOSE_RECEIPT/MARK_TIMEOUT_UNKNOWN` 不产生回执，也不占用 receiptSeq。
- `SUCCEED` 回执固定 `result=SIMULATED_SUCCESS`，包含 jobId、文件摘要、计划份数/张数、完成份数/张数、时间和 simulation 标志；本批成功只能是全量完成。
- `LOSE_RECEIPT` 不伪造完成回执，只写 `RESULT_UNKNOWN` 事件和原因 `SIMULATED_RECEIPT_LOST`。`FAIL` 写明确失败回执，但不自动补打或退款。

### 文件与清理器边界

- `CREATE_TASK`、`CLAIM`、`ACCEPT` 均在事务中按 scope 锁定文件行，重新检查 `READY`、未过期、摘要、页数和冻结快照；等待锁后重新取当前时间。
- 文件在 QUEUED/CLAIMED 期间失效时不能进入 ACCEPT；由 `ABORT_FILE_UNAVAILABLE` 终止为模拟失败，完成数量0，不自动退款。
- 当前“模拟终端”只演练打印计划和状态机，**不会打开 blob、不会传输文件、不会声称终端已收到或物理打印**，因此本批不增加 blob 占用锁。ACCEPT 后文件自然过期不改变任务，也不能成为自动重排理由；任何成功仍只叫“模拟成功”。真实终端接入前必须另行设计受控文件读取和清理器占用保护。

### 动作幂等优先级

1. Controller 完成认证，服务端重新解析管理员 tenant/store/user 及 `print:order:process` 权限。
2. 事务按 scope 锁 task 后，先按 `(scope,task,idempotency_sha256)` 查已保存动作；canonical 输入摘要覆盖 action、taskId、expectedVersion、leaseId、fence、receiptSeq、结果/原因与完成数量。
3. 同键同摘要直接返回不可变 `ActionResult`，不再检查当前 version、租约是否到期、文件状态或任务是否后来推进；同键异摘要返回冲突。
4. 只有不存在动作记录时才检查当前状态、expectedVersion、lease/fence、文件及时间，然后原子推进。
5. 响应同时返回 `originalActionResult`（该动作当时 before/after state、task/order version）和 `currentTask`（事务内当前快照）。历史 CLAIM 重放不得把旧 `CLAIMED` 冒充为现时状态。

## API 候选

管理端：

- `GET /admin-api/print/miniapp-demo/orders?pageNo=&pageSize=`：当前门店小程序订单列表，查询权限。
- `GET /admin-api/print/miniapp-demo/orders/{id}`：订单、item、支付收据、task、事件与打印回执，查询权限。
- `POST /admin-api/print/miniapp-demo/orders/{id}/print-task`：空 JSON/稳定幂等键，创建或重放唯一 task，处理权限。
- `POST /admin-api/print/miniapp-demo/print-tasks/{id}/actions`：严格 JSON `{action, expectedVersion, leaseId?, fence?, receiptSeq?}` 与幂等键，处理权限。

小程序：

- 扩展现有 `GET /app-api/print/miniapp/demo/order?id=` 返回 task/result 摘要，或新增精确 `GET .../print-result?orderId=`；只能查当前 principal，响应 `no-store`。
- 确认订单页在支付后显示“等待后台生成打印任务”，支持手动刷新；任务创建后显示排队/已领取/已受理/执行中/模拟成功/失败/结果待核实，不能把 ACCEPTED 当成物理完成。

## 数据库候选

- `print_miniapp_demo_print_task`：稳定 jobId、scope/principal/order/item/file 摘要、状态、version、lease/fence、创建/更新时间；唯一 order item。
- `print_miniapp_demo_print_task_action`：动作幂等摘要、输入摘要、before/after state、task version、actor、时间；插入后不可更新/删除。
- `print_miniapp_demo_print_receipt`：task、receiptSeq、result、计划/完成数量、文件摘要、simulation、时间；插入后不可更新/删除。
- 扩展 `print_miniapp_demo_order` CHECK：保留未付 `version=1/NOT_CREATED`、已付 `version=2/NOT_CREATED`；只有已付订单可在 `version>=3` 使用上述打印状态。重复领取会继续递增版本，不能把某个状态硬绑固定版本。服务层以 task version、动作记录和连续 order event 约束合法推进。
- 扩展 order event CHECK，仅增加 `PRINT_TASK_CREATED` 和上表动作事件；event 的 `(order,aggregate_version)` 与 task 的 `(task,event_seq)` 均唯一且不可变。任何取消/退款/补打状态仍不允许。
- 复合外键必须绑定同一个 scope/principal/order/item/task；任务/回执不能与同 principal 的另一订单拼接。

### CHECK 迁移与恢复

- 执行前保存 `SHOW CREATE TABLE print_miniapp_demo_order`、`print_miniapp_demo_order_event` 和仅相关表的结构/数据备份，并记录原 CHECK 名称/定义。
- 增量迁移先创建任务表和可与现有 v1/v2 数据共存的新 CHECK，再移除仅允许 M3 两态的旧 CHECK；未部署新应用前不写 M4 状态。
- 回滚应用可直接切回 M3 release；若尚无 M4 task/状态，删除新增 CHECK 并恢复原 CHECK 后再移除新表。若已经产生 M4 状态，不允许直接恢复旧 CHECK或删除任务数据，须停止写入、保留任务/回执审计并另做 Astra High 数据回退方案。

## 最少充分验证

- 管理权限：无查询/处理权限拒绝；跨租户/门店拒绝；路径或请求体不能覆盖 scope。
- 唯一任务：同键重放同 job；异键并发创建只有一个 task；未支付、文件过期或摘要不一致无 task。
- 状态与 fencing：合法主链成功；旧 lease/fence、错误 version、重复回执拒绝；并发动作只推进一次。
- 恢复：CLAIM 后租约过期必须先显式回 QUEUED，再领取时 fence 递增且旧 fence 拒绝；ACCEPTED/EXECUTING 超时只能进 UNKNOWN。
- 动作重放：ACCEPT/SUCCEED 成功但响应丢失后，原键同输入返回原动作结果，即使当前版本已推进或租约已过期；异输入冲突。
- 文件：QUEUED/CLAIMED 期间文件到期不能 ACCEPT，只能显式失败；ACCEPT 后过期不自动回队列。
- 防重复：成功后任何 CLAIM/START/SUCCEED 不产生第二任务、第二成功回执或第二订单结果。
- 未知结果：ACCEPT 后租约/回执丢失进入 `RESULT_UNKNOWN`；不能自动回队列，小程序与后台都显示“待人工核实”。
- 原子性：task/action/receipt/order状态/订单事件要么全有要么全无；复合外键拒绝 task/receipt 错配订单。
- 页面：后台扩展现有订单页，不新增纯展示页；小程序只显示服务端状态，明确“模拟成功”而非真实出纸。

## 审查请求

请重点检查：管理员与小程序的双向隔离、唯一任务、租约和 fencing、ACCEPT 后不盲重发、事件/回执幂等、订单状态投影、文件过期边界、复合外键和 CHECK 迁移，以及用受权限保护的后台模拟器替代匿名终端是否满足当前开发演示边界。审查者只读，不执行迁移、部署或真实打印。
