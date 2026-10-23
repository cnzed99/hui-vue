# CORE-03 A 批：订单创建与本人查询契约

2026-10-23。本文描述本批源码和迁移合同；构建、DDL、部署及独立 HTTP 验收由主进程执行，需查看对应证据。本文编写时本 agent 未执行这些操作。

## 范围

实现真实数据库订单创建、本人详情、本人分页列表和本人事件查询。当前恰好一个报价、一个文件项、一条 ORDER_CREATED 事件。`source=ADMIN_DEMO`、`simulation=true`、创建主体为实际登录 STAFF，不能冒充客户或终端。

不创建支付、余额流水或打印任务，不执行取消、退款、补打，不提供这些动作的假成功接口。CORE-03/04/05 的完整范围继续见 `core03-implementation-plan.md` 与原任务书。本批不修改已有 file/quote 服务，不延长其过期时间。

## 配置、授权与接口

`print.orders.enabled` 默认 false，关闭时报 HTTP503/code1510030000。没有任何默认业务价格、默认留存期或自动支付设置。菜单权限种子由主进程独立迁移，本次 SQL 不含权限/业务数据 INSERT。

全部路径有 `/admin-api` 前缀：

|方法/路径|权限|参数|结果|
|---|---|---|---|
|POST `/print/orders`|print:order:create|body 严格 `{quoteId}`；Idempotency-Key 必填；不接受 URL query|原子创建或返回原订单详情|
|GET `/print/orders/{id}`|print:order:query|小写 UUID，不接受 query|本人订单详情|
|GET `/print/orders`|print:order:query|仅 pageNo/pageSize|本人订单摘要分页|
|GET `/print/orders/{id}/events`|print:order:query|仅 pageNo/pageSize|本人订单事件分页|

quoteId/id 为小写标准 UUID 字符串。幂等键 ASCII33–126，长度1–128，不含空格；数据库仅保存其 SHA-256。POST 多出任何字段（金额、页数、owner/store/tenant、状态等）均拒绝。body 的 JSON 字段顺序不会改变幂等语义。

分页缺省 pageNo=1/pageSize=20。pageNo 范围1–1,000,000，pageSize范围1–100；必须无符号、无空白、无前导零的十进制整数字符串。0、负数、小数、科学计数、重复参数、空值、额外参数（含 sort/storeId/userId）均400。订单固定 `created_at_ms DESC,id DESC`；事件固定 `aggregate_version ASC,id ASC`。OFFSET 用 long 计算，边界不会 int 溢出。offset 分页不承诺多个请求间数据静止；并发新建可移动页边界。total 是 long；前端不能把超出 JS 安全整数范围的 long 当作可精确计算的数值，届时应升级字符串或游标合同。

全部数据范围由 PrintScopeService 的当前实际 tenant/store/user 决定，SQL 显式绑定三者。权限 query 不等于门店全部数据权限；本批只允许查看本人订单。其他 owner/其他门店/不存在的 id 都404。事件接口先验证当前 owner 的订单存在，不泄漏他人事件数量。框架认证/权限拒绝可能是 HTTP200+code401/403，客户端须检查两层结果。

## 响应结构

成功使用 CommonResult：`{code:0,data:...}`。所有金额均精确十进制字符串，无浮点与默认两位舍入。

摘要 summary：

```text
{
 id:UUID, orderNo:string,
 source:"ADMIN_DEMO", simulation:true, currency:"CNY", totalAmount:string,
 orderState:"OPEN", paymentState:"UNPAID", refundState:"NONE",
 deliveryMode:"NONE", deliveryState:"NOT_APPLICABLE", printState:"NOT_CREATED",
 createdByActorType:"STAFF", createdByActorId:long, createdAtMs:long
}
```

详情为摘要加：

```text
items:[{
 id:UUID, quoteId:UUID, fileId:UUID, fileSha256:string,
 amount:string,
 quoteSnapshot: PrintQuoteSnapshot
}]
```

quoteSnapshot 与既有报价返回结构一致，包含 schemaVersion、价格版本、完整基价与单阶梯、PER_FILE_ALL_COPIES、页数/份数/纸张、精确价格/合计、创建/到期时间。不从当前 profile 重新计算，不改变原过期时间。数据库保存原 quote JSON；输出按既有 typed snapshot 解释版本1。内部 idempotency/input hash 和路径不会返回。

列表返回 `{list:[summary],total:long,pageNo:int,pageSize:int}`，不在列表重复下载文件摘要和完整价格快照。

事件返回：

```text
{list:[{
 eventId:UUID,orderId:UUID,type:"ORDER_CREATED",
 actorType:"STAFF",actorId:long,simulation:true,aggregateVersion:1,createdAtMs:long
}],total:long,pageNo:int,pageSize:int}
```

所有 actor ID 均来自登录作用域。订单号使用服务器生成的 `PD` 加大写 UUID 去连字符，长度34；不是原站订单号格式，也不是访问凭据。订单创建状态不能被显示为已支付、已打印、已取件或完成配送。

## 事务、重试与文件校验

使用 READ_COMMITTED。锁顺序：当前员工门店映射 → quote → file → order/item/event 插入。成功重试先在映射锁之后做不加行锁的幂等查询，直接读取原订单；不倒置新创建的锁序。

1. 先检查 feature、请求/键、身份/权限/门店映射。
2. 在同一 owner 的映射锁下查 `(tenant,store,owner,keyHash)`：输入 hash 等于 `SHA256("order-v1\\n"+quoteId)` 时返回原单，否则409/1510030008。这里的 `\\n` 表示实际换行字节，不是两个字面字符。
3. 对首次创建锁本人 quote。若已被其他订单消费，409/1510030004，优先于后来到期；不同 key 不能重复消费报价。唯一 quote_id 索引为最终并发保障。
4. 检查 quote 未过期、支持的 schema1/DEMO/CNY/PER_FILE_ALL_COPIES、快照 file/profile/time 与 quote 列一致，正整数计数及非负十进制金额合法。金额直接复制服务器快照，不接受客户端覆盖，不复制一套定价算法。
5. 锁本人 file，等待后再读时间。quote 仍有效；file 必须 READY、有正页数、未过期，文件 ID、内容 SHA-256 和数据库页数与快照一致。
6. 同一事务插入 order、item、ORDER_CREATED event。任何异常全部回滚，失败创建不消费 quote。没有支付或打印副作用。
7. 成功创建后，同 key 同 quote 的重试即使原 quote 到期、文件被删或到期也返回原单及原创建时间。这是已创建订单的读取重试，不代表文件仍可支付或派发。当前身份、feature 和权限仍须通过。

quote 和 profile 原记录均不 UPDATE。消费证据在 order_item 的唯一 quote_id；未开放取消/退款/释放消费，不会将原 quote 恢复可重复下单。

**文件生命周期边界：** 本批只保证创建时 DB 状态/摘要/页数/到期检查，不承诺订单永久持有 PDF，不修改/延长 file.expires_at_ms。已有文件删除/清理可使后续文件不可用。后续模拟支付与任务领取必须分别重新校验文件，并与清理/删除协调；失效时不能产生支付成功或任务副作用。详见实施方案文件生命周期章节，不能把本批订单创建成功当作这部分已完成。

## 表与不可变约束

- `print_order`：身份/来源/模拟标记/精确总额/多维初始状态/version/幂等摘要/创建时间。unique订单号、owner幂等键、scope+id。
- `print_order_item`：复合 owner/order 外键，quote_id 全局唯一且引用 print_quote(id)，文件内容摘要、不可变 quote JSON、精确金额和sequence1。对旧 quote 表不新增列或索引。
- `print_order_event`：复合 owner/order 外键，ORDER_CREATED、实际actor、simulation、version1；unique(order_id,aggregate_version)。

所有表 BEFORE UPDATE 触发器拒绝更新。事件 DELETE 触发器仅允许 `USER()='root@localhost'` 的本机维护登录，应用账号无法删除事件；不用 definer 的 CURRENT_USER() 判断。数据库管理员仍可按授权维护合成夹具，这不是抵抗数据库超级管理员篡改的审计系统。

A 批 CHECK 约束明确限制初始状态：OPEN/UNPAID/NONE/NONE/NOT_APPLICABLE/NOT_CREATED/version1，以及单项sequence1和初始事件。**下一批 B 的状态迁移必须用新的审核迁移替换初始状态 CHECK/订单 UPDATE guard，只放开经过状态机控制的状态列与 version 等必要字段，继续保护身份、金额、幂等、创建时间和 item 快照；不得简单删除全部保护来实现支付更新。** 事件维持 append-only，下一阶段允许的新事件类型/actor/version 也要显式迁移，不改写已有事件。

## 错误码

|code|HTTP|含义|
|---|---|---|
|1510030000|503|订单功能关闭|
|1510030001|400|请求、UUID、幂等键、分页或额外参数不合法|
|1510030002|404|本人报价不存在|
|1510030003|409|报价已到期|
|1510030004|409|报价已被订单消费|
|1510030005|404|本人文件不存在|
|1510030006|409|文件非READY、无有效页数或已过期|
|1510030007|409|文件摘要/页数/ID与报价不一致|
|1510030008|409|同幂等键关联不同报价|
|1510030009|404|本人订单不存在|
|1510030010|503|数据库/事务/已保存订单快照异常|
|1510030011|403|加锁时当前员工门店映射失效|
|1510030012|409|报价快照版本或字段不支持|

原有 scope 1510000xxx 和框架权限码不重定义。数据库异常不回显 SQL、密码、token 或文件路径。

## 迁移、构建和验收交接

- 源码：`src/backend/yudao-server/src/main/java/cn/iocoder/yudao/server/print/order/`，以及 `controller/admin/PrintOrderController.java`。
- SQL：`ops/db/print-orders-v1.sql`；必须通过 `ops/db/migrate-print-orders.py --apply` 同时安装触发器，不能只执行建表 SQL。
- 默认不带 --apply 仅输出 NOT_APPLIED，不连接数据库、不备份。--apply 由主进程调度，要求≥10GiB、fixture-mutation锁、预迁移加密备份恢复验证，检查已有列/触发器合同，再增量迁移、核验列/唯一键/触发器，写 evidence/database/print-orders-v1.json。没有权限seed、业务数据seed、开关或重启。
- Python AST 和默认 NOT_APPLIED 已检查；Java/Maven、迁移、HTTP 均交由主进程统一执行，不以静态源码检查替代运行结果。
- 独立验收应覆盖并发相同key一次创建；不同key同quote一次消费；同key不同quote冲突；原quote到期后重放；非READY/过期/被删/摘要或页数变化；跨owner/store；非法金额/状态/分页输入；稳定排序；事件实际actor；DB UPDATE/应用DELETE事件拒绝；无支付/任务副作用。
- 清理仅精确测试 ID：本机root删除 event → item → order，之后才能删除其 quote/profile；不全表清空，不解除触发器，不删未知记录。凭据仅服务器私有文件/内存，日志脱敏。

本批未生成临时 PDF、SQL明文备份、编译缓存或安装包；完成时检查剩余空间。后续完整范围和未确认规则继续保留，不以 A 批通过关闭全部 CORE-03。


## Verified runtime increment (2026-10-23)
Migration: 44 columns, 6 unique indexes and 4 immutable/append-only triggers verified after encrypted backup/restore. Backend packaging and immutable release authentication passed. evidence/acceptance/order-http-smoke.json contains 33 passing order checks and 3 PDF fixture checks: first-create concurrency, quote consumption, scope denial, pagination, snapshot integrity, file invalidation and actual process restart. All generated fixture rows/files/sessions were removed, and the loopback acceptance process stopped. order-event-guard-smoke.json proves the application database account cannot delete an event; exact root maintenance cleanup succeeded. This verifies the order foundation only; simulated payment, task dispatch, refunds, delivery and frontend rendering remain outside this evidence. Normal demo order feature remains disabled.
