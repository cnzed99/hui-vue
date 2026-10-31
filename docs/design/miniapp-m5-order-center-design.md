# M5 小程序订单中心与当前用户隔离设计

日期：2026-10-31  
状态：Astra High 设计审查候选，不代表生产发布或真机验收

## 目标与复刻依据

- 本批只实现小程序“我的订单”列表、订单详情，以及支撑这两个页面的只读服务端接口；不新增纯后台页面。
- 2026-10-31 通过 Codex 只读观察原“云途智印”：`我的 → 我的订单` 使用橙色标题栏；当前账号空态显示中央空箱插画、“暂无订单哦~”与“已经没有更多信息了”。因原账号无订单，未创建真实订单，原订单卡片与详情仍标记未观察。
- 新系统有模拟订单数据时，以现有服务端权威订单、模拟支付和模拟打印回执展示；不得将模拟结果表述成真实付款或实机出纸。

## 本次变更绑定

- 后端基线：`197d29c80fe430b2b0de3d0042c4b367d0367ae2`
- 小程序基线：`dc0da4c171461d47636987fdb8a75dfc40b976ad`
- 不修改数据库结构，不新增后端写入，不改变计价、支付、退款或打印状态机。

## 服务端接口

### 当前用户订单列表

`GET /print/miniapp/demo/orders?pageNo={1..10000}&pageSize={1..20}`

- `Authorization`、`Origin`、`Host` 沿用演示会话校验；必须调用 `MiniappDemoSessionService.require`。
- 仅允许 `pageNo`、`pageSize` 两个查询参数，各恰好一个值，十进制整数且范围固定；重复、缺失、额外参数或非规范数值统一拒绝。
- 客户端不得提供 tenant、store 或 principal。列表查询与总数查询都从会话生成的 `MiniappDemoIdentity` 绑定 `tenant_id + store_id + principal_id`。
- 排序固定为 `created_at_ms DESC, id DESC`；返回 `{list,pageNo,pageSize,total,hasMore}`，列表项复用现有 `MiniappDemoOrderView` 的服务端快照一致性校验。
- 服务层用一个只读 `REPEATABLE_READ` 事务完成 scoped `count`、scoped `list` 与每条 `detail(...)` 映射；`total`、`list`、`hasMore` 必须来自同一数据库快照，不能分别在事务外读取。`hasMore` 由该快照内的 `pageNo * pageSize < total` 得出。
- 分页跨请求不承诺冻结整个结果集；若期间新增订单导致 offset 位移，小程序按订单 `id` 去重。主动刷新必须清空列表并从第 1 页重建。
- `total` 只统计当前身份作用域；不得泄漏其他主体是否有订单。
- 响应 `Cache-Control: no-store`、`Pragma: no-cache`。
- Nginx 精确路径 `/app-api/print/miniapp/demo/orders` 从仅允许 `POST` 调整为仅允许 `GET`、`POST`（按 Nginx 语义 HEAD 可随 GET）；其他方法继续 `deny all`，Authorization 继续原样转发，no-store 头保持不变。

### 当前用户订单详情

- 复用既有 `GET /print/miniapp/demo/order?id=...`。
- 继续通过 `tenant_id + store_id + principal_id + id` 查询；跨主体与不存在订单均返回同一个 `ORDER_NOT_FOUND`，不得形成存在性旁路。
- 支付收据与打印结果继续复用既有当前用户作用域接口；本批不改变它们的状态机或写规则。

## 小程序页面

### `pages/orders/index`

- 从“我的订单”菜单进入；橙色自定义标题栏，标题“我的订单”。
- 空态对齐原系统已观察文案；插画使用本地 CSS 几何图形，避免复制来源不明的原站素材。
- 非空列表卡片显示：文档打印、订单号、创建时间、页数/份数/单双面/纸张数、金额、模拟支付状态、模拟打印状态；点击进入详情。
- 首屏请求 20 条，支持下拉刷新和按 `hasMore` 继续加载；请求失败不伪装成空态，显示“订单暂时无法加载”和重试入口。
- 每次列表请求绑定 `requestGeneration + tenant/store/principal/session` 上下文。刷新、离页、会话 id 或 principal 变化都会递增 generation，使旧列表回调失效；分页追加前还要复核上下文，并按订单 `id` 去重，不能把旧主体或旧刷新轮次的结果混入新列表。

### `pages/order-detail/index`

- 必须由 `id` 参数进入并重新向服务端读取详情；不能只信任列表缓存。
- 订单、模拟支付收据和模拟打印结果的异步请求都绑定同一个详情 `requestGeneration + tenant/store/principal/session + orderId` 上下文；离页、刷新、订单 id 或会话变化后，旧回调不得覆盖当前页面。
- 展示订单号、金额、创建时间、纸张设置、模拟支付状态、模拟打印状态；若存在收据，展示模拟支付/打印回执。
- 详情的订单、支付收据和打印结果是三个独立 scoped 查询，不伪装成数据库原子快照：订单详情成功只证明订单快照；收据或打印结果查询失败显示“暂不可确认”，不得推断为未支付或未创建任务；成功取得的较新 `print-result` 是当前打印状态来源。
- 明示“模拟支付不涉及真实资金”“模拟回执不代表真实打印机出纸”。
- 仅提供返回、刷新等只读操作；本批不新增取消、退款、重打或真实支付按钮。

## 安全与验证要求

1. 后端针对同一会话的分页顺序、边界参数、空页进行验证。
2. 创建主体 A 和主体 B 的演示订单后，A 列表/详情不能读取 B，B 也不能读取 A；跨主体详情与不存在详情返回一致。
3. 列表中的每一项必须经过现有快照、金额、文件哈希一致性检查；不允许另建绕过 `detail(...)` 的弱化映射。
4. 浏览器闭环至少证明：空态、非空列表、详情、返回、刷新、从个人页进入；原微信小程序真机验收保持 `NOT_RUN`。
5. 本批若实质改变身份作用域、状态机或写入规则，必须重新提交 Astra High；当前设计仅新增只读作用域查询。

