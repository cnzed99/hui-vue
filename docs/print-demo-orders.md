# CORE06 演示订单只读入口

状态：前端源码已实现；子任务未运行 Vite 构建、部署或真实浏览器验收。后续结果由主流程记录。

## 范围
- 工作台增加“演示订单”辅助入口 `/print/demo-orders`，明细 `/print/demo-orders/:id`。
- 使用正常登录守卫、现有真实用户权限 `print:order:query` 和已验证门店上下文。后端再次执行租户、门店、员工隔离。
- 原 53 入口映射不变。此入口不是 P21 电脑订单或 P23 手机订单，也不算原站完整页面复刻。
- 仅 GET 列表、明细和事件；无创建、取消、退款、补打、支付或打印操作。
- 来源仅 ADMIN_DEMO，simulation=true 显示“模拟”。支付、打印、退款、配送状态分别显示；没有成功支付/物理出纸承诺。
- 金额保持服务端十进制字符串；分页数量及员工 ID 检查 JS 安全整数范围。未知状态显示未知状态原值。
- 列表分页 pageNo/pageSize 保留在 URL；详情返回列表保留分页。详情 API 不带分页查询参数，事件独立分页。
- 错误不转为空列表：disabled、403、401、not-found、invalid-input、network、service、invalid-response 独立展示。401 保留共享刷新登录机制。
- 身份/门店/权限/路由变化清空旧结果，generation 阻止旧异步响应回填。
- 报价快照按“文件 1、文件 2”序号分组展示页数、打印面、份数、纸张、单价、金额、阶梯是否命中和价格版本；文件/报价标识放入折叠“记录标识”，来源只显示“后台演示”。接口未提供文件名，不虚构。

## 本批文件
- `src/print-admin/orders/api.ts`：真实接口及 CommonResult/DTO 校验。
- `src/print-admin/orders/view.ts`：权限上下文、分页参数、状态和北京时间展示。
- `src/print-admin/orders/DemoOrders.vue`、`DemoOrderDetail.vue`、`OrderState.vue`、`orders.css`。
- `src/print-admin/routes.ts`：受保护路由与 resetRouter 保留名。
- `src/print-admin/PrintWorkbench.vue`：有查询权限时的辅助入口。
- `src/print-admin/PrintAdminShell.vue`：标签标题跟随当前页，不误标工作台。
- `scripts/check-print-orders.cjs`：隔离契约检查，不访问网络/数据库。

## 已执行检查（云服务器）
- 12 个打印前端 TS/Vue 文件：TypeScript transpileModule 语法检查、Vue SFC parse/compileScript/template 静态检查通过。不是完整 TS 类型检查或应用构建。
- `node --max-old-space-size=256 scripts/check-print-orders.cjs`：26 项隔离契约检查通过。使用人工声明样本和 mock request，仅验证前端解析/请求形状，不代替真实 HTTP 或浏览器验收。
- `git diff --check` 通过；原 menu.ts、permission.ts、preview、环境变量未改。
- 未安装依赖、未运行 Vite、未重启、未部署、未触碰后端或数据库。
- 本批没有中间构建文件/本地源码副本；无需清理源码或必要依赖。检查时系统盘剩余约 47GB，高于 10GiB 保留线。

## 主流程待验收
1. 串行真实构建并部署；未登录直接访问列表/详情必须进入登录，登录 redirect 保持。
2. 默认 orders disabled 显示未启用而不是暂无数据；无 query 权限隐藏工作台入口，直接路由显示无权限。
3. 专用演示测试环境启用后检查真实空列表、真实演示数据、长订单号、精确金额、分页/详情返回、事件分页。
4. 503/断网/404/参数异常显示不同状态，不残留旧记录；401 刷新/重登沿框架机制。
5. 身份、门店或权限变化后旧详情不可见，旧响应不可回填。
6. 检查原站外壳 168px 侧栏、42px 页签和原 53 菜单没有被辅助演示入口替代。


## 主流程部署记录（2026-10-23）
Vite cloud 真实构建通过，2734个部署文件逐项校验匹配；当前后端恢复且真实认证6项通过。实际浏览器访问 /print/demo-orders 未登录正确跳到 /login?redirect=/print/demo-orders。仅验证未登录路由保护，未验证登录后订单页面、空/禁用/异常状态及视觉一致性。构建临时目录已清理；保留前版静态资源快照。浏览器预览所用SSH隧道断开后已恢复，服务器应用本身保持正常。

## B 批只读状态适配（2026-10-23）
- 支付 SUCCEEDED 显示“模拟收款成功”；打印 QUEUED 显示“等待打印（排队中）”；事件 PAYMENT_SIMULATED 显示“模拟收款已记录”。
- 支付、打印、事件使用独立字典。未来未知打印 SUCCEEDED 不会套用付款成功文案，而明确显示未知打印状态。
- 列表/详情/事件保持真实查询与 simulation 标签。无支付按钮、无新增写接口或支付详情接口；本批仅适配已冻结状态。
- 原 26 项隔离检查保留，新增 12 项状态合同/模板绑定检查，共 38 项；这类检查不代替浏览器或真实后端验收。
- 本批子任务未 build/deploy/restart，无临时产物生成；下一次发布由主流程统一执行。
