# CORE-02 报价基础 v1 契约

状态：源码已构建并发布，迁移通过备份恢复及结构验证，独立合成 HTTP 验收 48 项通过。完整 CORE-02 尚未完成。所有价格示例均为验收夹具，迁移没有写入任何业务价格。

## 当前实现边界

单文件、全页、单单双面选择、正整数份数、每张纸精确定价，允许一个严格超过张数阈值的阶梯。2026-10-23 用户确认 D08：每个文件独立统计，份数计入该文件总纸张数，不同文件不合并。双面每份分别向上取整；命中阈值后该文件全部纸张使用阶梯单价。调用既有 PaperQuoteCalculator，不复制另一套计价公式。

多文件一次提交、页段、彩色/纸型规格矩阵、装订、配送、优惠、多个阶梯及其优先级、订单消费/支付/打印均未实现。不得将单文件报价基础标记为完整计价模块。当前 profile 必须显式 mode=DEMO,currency=CNY；这不代表实际业务价格已确定。

## 配置与权限

- `print.quotes.enabled` 默认 false。
- 开启时 `print.quotes.ttl-seconds` 必须显式指定正整数且毫秒时间不溢出；没有默认 TTL。
- 关闭时报 503 / 1510020000；启用但 TTL 缺失/非法时报 503 / 1510020010。
- 创建要求 `print:quote:create`，读取要求 `print:quote:query`。
- 所有查询使用登录员工实际 tenant/store/user，调用 PrintScopeService 验证唯一有效门店；请求不能选择门店/owner。
- 文件功能和报价功能是分别的开关；报价从真实 DB 中读取本人 READY、未过期文件，不制造页数。

## POST /admin-api/print/quotes

Header: `Idempotency-Key`，ASCII 33–126，1–128 字符，不允许空格。仅保存 SHA-256，不保存明文 key。

请求严格只有四个字段：

```json
{"fileId":"小写UUID","priceProfileId":"小写UUID","copies":2,"side":"DUPLEX"}
```

side 为 SIMPLEX / DUPLEX；copies 为可转换为 Java long 的正整数 JSON number，不接受字符串、小数、零或负数。总张数 long 溢出拒绝，不截断。前端应避免用 JS number 表示超过安全整数的份数。任何额外字段均拒绝，包括 amount、pages、storeId、ownerUserId、pageRanges；请求者提供金额不能影响计价。

服务器在事务内锁定当前员工门店映射，串行化同一 owner 的创建请求；事务 READ_COMMITTED 避免等待锁之前建立的快照导致陈旧幂等读取。随后查询幂等记录、锁定本人文件，使用数据库页数和内容 SHA-256，读取当前门店价格 profile，精确计算，再保存不可变快照。锁等待后重读时间，防止使用等待前的时间接受已过期文件。

幂等范围为 `(tenant_id,store_id,owner_user_id,keyHash)`；输入 hash 来自固定版本前缀、fileId、priceProfileId、解析后的 copies 和 side。JSON 字段顺序不影响 hash。相同 key 和相同输入返回同一报价 ID、创建时间、到期时间、原始价格快照，不重新计价、不延长有效期。冲突检测先于过期检测：同 key 不同输入 409/1510020006；相同输入但原报价已过期 409/1510020007，必须使用新 key。有效报价重放仍校验本人文件 READY 且未过期，文件被删除/过期会拒绝。

报价到期时间为 `min(创建时间 + 显式TTL, 文件到期时间)`。创建过程不会延长文件保留期。

## GET /admin-api/print/quotes/{id}

只返回当前 tenant/store/owner 的报价；其他 owner/其他 store/不存在统一 404/1510020008。返回持久化 JSON 的历史字段，不随 profile 新版本或文件后续变化重新计算。动态 status 根据当前时间为 VALID 或 EXPIRED。

**VALID 仅指报价有效期，不能作为下单授权。** 后续订单必须在事务中重新检查员工权限、文件状态/保留期、报价到期及未消费状态；本阶段尚无订单消费功能。读取已过期报价允许查看历史快照，不能续期。

统一成功响应 CommonResult：

```text
{code:0,data:{
  id: UUID,
  status: "VALID" | "EXPIRED",
  snapshot:{
    schemaVersion:1,mode:"DEMO",currency:"CNY",
    aggregationPolicy:"PER_FILE_ALL_COPIES",
    fileId:UUID,fileSha256:64位hex,
    priceProfileId:UUID,priceProfileKey:string,priceProfileVersion:string,
    pagesPerCopy:int,side:"SIMPLEX"|"DUPLEX",
    copies:十进制字符串,sheetsPerCopy:十进制字符串,totalSheets:十进制字符串,
    simplexPricePerSheet:精确十进制字符串,duplexPricePerSheet:精确十进制字符串,
    tier:null|{strictlyMoreThanSheets:十进制字符串,simplexPricePerSheet:精确字符串,duplexPricePerSheet:精确字符串},
    pricePerSheet:精确十进制字符串,totalAmount:精确十进制字符串,
    tierApplied:boolean,appliedTierThreshold:null|十进制字符串,
    createdAtMs:long,expiresAtMs:long
  }
}}
```

价格使用 BigDecimal 乘法和 toPlainString，不浮点、不科学计数、不默认按分舍入。例如 `0.123 * 3 = "0.369"`，支付结算精度仍待明确。JSON long 数量输出字符串以避免浏览器精度丢失。pagesPerCopy 来自数据库 INT。

## 表及不可变性

`print_price_profile` 字段：id CHAR36 PK、tenant_id BIGINT、store_id BIGINT、profile_key VARCHAR64、version VARCHAR64、mode VARCHAR16、currency CHAR3、simplex_price_per_sheet VARCHAR64、duplex_price_per_sheet VARCHAR64、tier_json JSON NULL、created_at_ms BIGINT。唯一 `(tenant,store,profile_key,version)`；作用域唯一 `(tenant,store,id)`；门店复合外键。价格字符串非负普通十进制，长度最多 64，不能带正负号/科学计数/空格；profile_key/version 必填，version 不能空白。配置非法 fail closed。

没有阶梯须 SQL NULL。单阶梯必须是且仅是以下三个字段的 JSON 对象：

```json
{"strictlyMoreThanSheets":10,"simplexPricePerSheet":"0.15","duplexPricePerSheet":"0.15"}
```

阈值为非负 long JSON 整数；单价为上述十进制字符串。数组、多级、额外字段、JSON null 均拒绝。聚合规则由代码固定为用户确认的 PER_FILE_ALL_COPIES；不得通过请求或 profile 覆盖。

`print_quote` 字段：id CHAR36 PK、tenant_id/store_id/owner_user_id BIGINT、file_id/price_profile_id CHAR36、idempotency_sha256/input_sha256 CHAR64、snapshot_json JSON、created_at_ms/expires_at_ms BIGINT。幂等唯一 `(tenant,store,owner,idempotency_sha256)`；价格 profile 复合作用域外键。报价快照包含已使用的版本/基价/阶梯/文件内容摘要，不能修改旧快照。

迁移安装两个 BEFORE UPDATE 触发器拒绝任何 profile 或 quote UPDATE。新增价格须 INSERT 新 ID、新 version；不更新旧版本。无修改/删除 API。数据库管理者仍可显式删除专用验收记录；被报价引用的 profile 受外键保护。此处不是抵御数据库超级管理员篡改的审计系统。

## 错误码

|code|HTTP|含义|
|---|---|---|
|1510020000|503|报价功能关闭|
|1510020001|400|请求/key/类型/数量范围不合法|
|1510020002|404|当前门店价格版本不存在|
|1510020003|409|价格格式或规则未支持|
|1510020004|404|本人文件不存在|
|1510020005|409|文件未 READY 或过期|
|1510020006|409|幂等键输入冲突|
|1510020007|409|原报价已过期|
|1510020008|404|本人报价不存在|
|1510020009|503|数据库/事务/快照存储异常|
|1510020010|503|TTL 未显式设置或非法|
|1510020011|403|锁定时员工门店映射已无效|

框架认证/权限拒绝可能 HTTP200 且 CommonResult.code 为 401/403，客户端须同时检查两者。scope 的既有 1510000xxx 错误保持不变。

## 迁移与验收

`python3 ops/db/migrate-print-quotes.py` 默认只输出 NOT_APPLIED。root 调度 `--apply` 后：检查至少10GiB空闲、获取夹具迁移锁、预检已有列与触发器、先执行已验证的同机加密备份恢复工具、仅增量建表、安装缺失触发器、验证列/唯一键/触发器并记录 evidence/database/print-quotes-v1.json。不会启用服务、添加业务价格或重启。不得绕过迁移只执行 SQL 而遗漏不可变触发器。

独立 HTTP 验收应显式创建测试 profile；验证 3页双面2份=4张、11张命中全部阶梯、不同文件6张各走基本价、同文件6张2份=12张命中、十进制无舍入、输入金额/页数拒绝、本人文件状态和有效期、他人/他店隔离、幂等并发/冲突/过期、新价格版本不影响旧快照、DB UPDATE 被拒绝。验收清理先删精确测试 quote，再删其 profile，不删除其他业务记录；测试文件沿既有受控清理流程处理。

运行证据：evidence/acceptance/quote-http-smoke.json，48 项通过；首轮 31 项保留为 quote-http-smoke-v1.json。采用临时 loopback 实例、6 秒报价有效期和 20 页文件上限，均为测试配置。正常演示报价开关仍关闭，测试价格/文件/会话与临时实例已清理。实际备份为同机备份，不宣称异地恢复。订单消费、完整规格矩阵、多文件请求、优惠及结算仍未实现。
