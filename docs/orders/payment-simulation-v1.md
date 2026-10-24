# B 批实现交接：模拟收款与持久化排队

日期：2026-10-23。实现范围与已审查 `payment-simulation-plan.md` 一致：受权员工模拟收款、成对模拟分录、唯一排队任务。源码与迁移静态就绪，实际构建、迁移和HTTP结果由主进程统一执行并记录，本文不代替运行验收。

## 1. 代码与接口

源码5类位于 `server.print.payment`：Properties、Exception、View、Repository、Service；控制器 `controller.admin.PrintPaymentController`。没有修改A已有订单Java、file/quote Java、前端或权限seed。

- `POST /admin-api/print/orders/{id}/simulate-payment`：`print:payment:simulate`；body严格 `{}`，query为空；Idempotency-Key ASCII33–126且长度1–128；小写UUID。新建完整模拟成功事务或重放同一成功receipt。
- `GET /admin-api/print/orders/{id}/payment`：`print:order:query`；query为空；查询本人历史receipt。模拟写开关关闭不隐藏历史，orders整体查询开关仍有效。
- 默认 `print.payments.enabled=false`；写入还要求显式 `print.payments.mode=DEMO`，以及 `print.orders.enabled=true`。配置不是请求字段，不允许body覆盖。
- scope由真实登录员工唯一有效门店解析；所有DAO操作显式tenant/store/owner；source=ADMIN_DEMO、simulation=true、currency=CNY、actor=实际STAFF。当前服务仅部署于受控loopback/SSH隧道，仍必须认证授权；没有匿名回调。

CommonResult.data：

```text
{id,orderId,provider:"SIMULATED",state:"SUCCEEDED",simulation:true,
 currency:"CNY",amount:精确字符串,actorType:"STAFF",actorId:long,
 createdAtMs:long,jobIds:[UUID]}
```

jobIds稳定，响应不把QUEUED或未来执行状态当作付款receipt的一部分。新订单查询可显示paymentState=SUCCEEDED、printState=QUEUED；不能将其渲染为已经打印完成。金额仍是BigDecimal精确值的原字符串，没有按分舍入，也没有钱包余额可用性。

## 2. 重试、锁序与事务

- 输入hash：`simulated-payment-v1` + 单个换行 + canonical orderId；key只保存SHA-256。
- 同key同单成功后重试返回原receipt/jobIds，不检查后来文件是否失效、不再收款；环境、feature、身份、权限仍校验。
- 同key不同本人order：1510040004；不同key同已支付order：1510040005，无新增收款/事件/任务。
- 原quote到期不阻断合法已建订单的模拟付款；付款必须重新验证当前本人文件READY、未过期、正页数、文件摘要和页数与订单item快照一致，不延长文件期限。
- READ_COMMITTED；锁顺序映射→file→order→新记录写入。定位order/item/key的读取先不加order行锁，避免倒序。历史GET也在owner映射锁内读取，避免与该owner收款提交交错读取半套数据。
- 同事务插入receipt、DEBIT/CREDIT分录、QUEUED/NOT_REPORTED job、PAYMENT_SIMULATED/version2事件，再将order仅payment/print/version改成SUCCEEDED/QUEUED/2。
- 写入前、状态更新前、返回前检查锁定文件expires；若到期或任一点存储失败则全回滚。事务内核对receipt/分录/job/事件一致性后返回，不使用内存队列或外部网络调用。
- 撤回/退款/补打不存在假成功路由；文件后续到期不能因曾付款自动续期、退款或补打。C的领取仍需独立重验文件并处理未知结果。

## 3. 数据库与迁移

SQL：`ops/db/print-payments-v1.sql`。三表50列：print_payment_receipt 15列、print_simulated_journal 12列、print_job 23列。字段以SQL为准；全部模拟记录的UPDATE拒绝、DELETE仅USER()=root@localhost维护可用。无业务seed、权限seed或运行开关变更。

唯一性：receipt的order_id唯一、scope/key唯一；journal的receipt/side唯一；job的item/generation唯一（首轮generation0）。所有金额/身份都是服务端原订单值。账务两条同额、同币种一借一贷，内部精确检查净额0；属于模拟分录，不是完整钱包/正式财务结算实现。

`print_job`只允许QUEUED/NOT_REPORTED/version1/attempt0/fencing0，terminal/lease字段为空；没有假接收、系统队列或出纸结果。job只引用item和file摘要，不复制PDF或公开存储路径。

主进程调度：

```sh
python3 ops/db/migrate-print-payments.py --apply
```

不带`--apply`仅输出NOT_APPLIED，不连接DB、不备份。实际应用时：>=10GiB余量，独占fixture锁，检查A/B已知保护状态、新表列类型/nullable/字符集及已有trigger/CHECK合同，先执行加密备份恢复验证，再增量建表和受控替换保护。

新order guard名称`trg_print_order_update_guard_v2`：

1. 逐列NULL-safe比较18个不可变字段，任何金额、身份、source/simulation、币种、创建时间、幂等键、其他状态变动拒绝。
2. 仅允许payment UNPAID→SUCCEEDED、print NOT_CREATED→QUEUED、version1→2；重复/逆向/越级更新拒绝。
3. 数据库触发器另检查同事务已有匹配receipt、两分录、一个QUEUED job及PAYMENT_SIMULATED事件，不允许应用只改状态来伪造收款。

迁移先安装新guard和允许A/B状态的新CHECK，核验后才移除旧A全禁止guard和旧初始CHECK，避免无保护窗口；DDL自动提交，不声称整体事务回滚。旧item快照、event UPDATE/DELETE guard继续保留。

旧 `migrate-print-orders.py` 在无副作用预检和获取同一fixture锁后均检查superseded：检测到v2 guard时输出SUPERSEDED_BY_PAYMENT_V2退出，锁内复核位于备份和DDL之前，避免A预检与B迁移交错后把全禁止trigger装回。无`--apply`仍不连接数据库。

2026-10-23事后审查修正：CHECK采用严格tokenizer和表达式AST，保留AND/OR/NOT优先级，仅接受已支持的比较与IS NULL语法；额外括号、ASCII字面量的MySQL charset introducer、bit/hex与整体外层引号转义可规整，未知语法拒绝。trigger仅规整字面量外语法，字符串大小写、空格与括号保持原值。所有旧A与替代B的order/event CHECK均核对constraint schema、table schema、table、CHECK类型、ENFORCED=YES及表达式，在前置、移旧之前和最终替代保护验证时覆盖。

离线回归更新至`ops/acceptance/payment_migration_checks.py`，输出独立`evidence/acceptance/payment-migration-static-v2.json`并绑定两迁移脚本、SQL和测试SHA256，保留旧32项历史报告。包含真实A/B元数据合法等价、simulation=0付款事件的优先级绕过反例、order/event旧/新CHECK的NOT ENFORCED在备份/DDL前拒绝、移旧前拒绝、最终替代保护拒绝，以及A/B受控交错下旧A零备份/DDL退出；测试过程只使用内存元数据与mock，不接触实际业务约束。本次修正仍NOT_APPLIED，须由主进程取得绑定最终文件哈希的Astra复核后再安排实际迁移。

迁移证据将写 `evidence/database/print-payments-v1.json`，记录50列、唯一键、外键、CHECK、7个新trigger、18个不可变字段和空间；这里的“将”不表示本agent已实际迁移。

## 4. 错误码

沿用方案1510040000–1510040014：disabled503、invalid400、环境403、orderNotFound404、幂等冲突409、alreadyPaid409、非法状态409、fileNotFound404、fileNotReady409、snapshotMismatch409、storage503、scopeChanged403、sourceForbidden403、receiptNotFound404、integrity503。原orders disabled、scope及框架401/403保持原码。

只有成功写事务产生业务付款结果；存储异常由控制器安全响应，不回显SQL或敏感值。访问日志关闭请求/响应体记录。历史GET查到不完整receipt/分录/job/event时返回INTEGRITY_FAILURE，不能重收款自动“修复”。

## 5. 静态核对与验收交接

本agent已完成：Python AST、默认NOT_APPLIED、不连接DB的schema解析（15/12/23列、10个新表CHECK、7个trigger、18个不可变order字段）。没有执行Maven、DDL、数据库读写、重启或Git提交。独立HTTP并发、同key/不同key、回滚故障、跨身份、触发器拒绝与重启持久化仍由root实际验证。

回滚验收应在第二条分录或job/状态更新阶段注入仅指定测试订单的数据库失败，确认receipt/分录/job/payment-event全0且order仍v1；移除本次故障后同key可以一次成功。不能只用前置文件失败证明中途回滚。root维护清理须先支付事件→job→分录→receipt，再依原A流程清理订单/报价/file，精确ID范围，无全表删除。

A→B之后已付订单是合法B状态；不能把恢复A约束/旧版本二进制当作无损回滚。C之后也须显式迁移job状态/租约及order状态保护，不能直接删掉全部guard。

本批legacy模块导入产生的一份可再生pyc已准确删除，迁移已设置dont_write_bytecode避免再生成。本批未下载包、生成PDF或保留明文SQL备份；必要源码、迁移、文档和恢复备份保留。

未完成：钱包、充值、赠送、退款、真实支付/外部回调、终端领取/租约/回执、物理打印、取消/补打/配送。B不得称完整支付模块。
