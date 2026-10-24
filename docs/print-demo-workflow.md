# CORE06 辅助后台演示工作流

实施：GPT-6.1 Sol High（父流程指定；最终运行元数据由父流程核验）。Astra：未调用，普通 UI 仅消费冻结 API；没有设计或修改计价、支付、权限隔离或打印防重复规则。

## 范围与配置
- `/print/demo-workflow` 是工作台的辅助入口，不计入原 53 入口复刻完成；不改 menu.ts、orders/*、正常登录或门店 scope。
- 演示边界由用户确认：单 PDF / 2 MiB / 20 页 / 保留 3600 秒；正式经营参数待定。客户端只检查选择文件的扩展名与字节数，PDF 真伪、页数、生命周期以服务端为准。
- 部署构建须显式注入 `VITE_PRINT_DEMO_PRICE_PROFILE_ID`，仅接受小写标准 UUID，没有默认值。该 UUID 必须对应服务端当前门店真实 DEMO/CNY 测试 profile；配置缺失或无效禁用报价，显示待配置。界面固定标记“测试价”，价格及版本只显示真实服务端返回，不内置价格或新增读取 API。
- 服务端 files/quotes/orders/payments 开关、DEMO mode、TTL、权限及测试 profile bootstrap 均交父流程调度；本批未修改或启用。

## 数据流
1. 正常登录和已验证门店上下文下，`POST /print/files` multipart 仅含 file；保存当次操作 key，显示真实 metadata。ISO createdAt/expiresAt 与正整数 pageCount 单独校验。UPLOADED/PROCESSING 显示等待并提供手动 metadata 刷新，无自动轮询。
2. 输入单/双面与正安全整数份数；`POST /print/quotes` 仅 fileId/priceProfileId/copies/side。显示服务端页数、纸张、原始十进制字符串金额、价格版本和 Ms 有效时间，不做金额计算或舍入。
3. `POST /print/orders` 仅 quoteId。显示真实演示订单号、金额与独立支付/打印状态；跳转既有详情与列表。
4. 用户勾选明确模拟确认后 `POST /print/orders/{id}/simulate-payment` body 为 `{}`。显示真实 SIMULATED receipt 与 jobIds。单独提供历史 receipt GET 和已有订单 GET，只有订单返回 QUEUED 才显示当前排队状态。

模拟收款不是真实资金结算。receipt 的 jobIds 不是终端回执或物理出纸证据。终端领取/回执 C 尚未实现，排队不能称已打印。取消/退款/补打未实现，不提供假操作按钮。

## 操作结果与失败
- 当次上传、报价、下单、模拟收款在首次请求前保存 key 和固定输入；共享认证刷新及用户显式“确认本次结果”均使用原 key，不自动新建操作或自动重试。
- 网络、无法识别响应、服务异常或会话中断按结果待确认处理；待确认时锁住会改变输入的控件与另一次演示，保留原 key。下单待确认时，即使报价随后到期仍可重放原下单请求，由既有服务端合同裁定是否已有订单。
- 报价明确过期后允许显式新报价 key；不延长原报价或文件保留期。403、401、开关关闭、解析失败、记录缺失、报价消费、已收款、冲突与网络错误有不同文案；不将失败转为空成功。
- 结果待确认/操作中阻止 SPA 离页，并通过浏览器 beforeunload 提醒保留当前页。操作 key 仅保存在当前页面内存；强行关闭浏览器或忽略刷新提示后不承诺恢复未确认的 key。没有持久化恢复、跨标签协调或新的补打/重试策略，若需这类能力须独立设计审查。
- 身份、门店、上下文状态或所需操作权限变化同步清空结果，generation 丢弃旧响应。不在请求中添加 owner/store/tenant。

## 文件与所有权
新增 `src/print-admin/workflow/api.ts`、`DemoWorkflow.vue`、`workflow.css`、`scripts/check-print-workflow.cjs` 及本文；仅对 routes.ts 添加辅助路由及保留名、PrintWorkbench.vue 添加入口。orders/*、原53映射及后端保持不变。

## 本批静态证据
- 使用云端既有 Node 22 和依赖运行隔离检查，无 HTTP、DB、PDF夹具或构建。
- `scripts/check-print-workflow.cjs`：40 项 API 请求形状、DTO、精确金额、错误分类、配置、页数/时间及 SFC/template 检查通过。人工样本和 mock 不代替服务端或浏览器验收。
- 既有 `scripts/check-print-orders.cjs`：38 项隔离检查通过。
- `git diff --check` 通过。语法/SFC 编译不是完整 TS 类型检查或 Vite 构建。
- 首次命令遇非交互 PATH 没有 node，改用既有绝对路径；随后脚本无法直接解析传递依赖 @vue/compiler-sfc，改为 vue 的公开 compiler-sfc 入口后检查通过。没有应用实现连续两轮修复失败。
- 未安装依赖、build、deploy、restart、调用真实 API、访问业务 DB、seed、启用开关或 Git 提交/推送；无中间构建输出。

## 父流程剩余验收
1. B 最终审查 PASS 后，按授权设置真实测试 profile 与演示参数，并串行构建/部署。
2. 浏览器验收登录跳转、权限不足、配置缺失、503关闭、真实解析等待/失败、报价过期和网络中断；检查当前身份切换旧结果不可见。
3. 在专用演示数据下完整走 PDF→真实报价→真实订单→显式模拟收款→读取订单 QUEUED→既有详情；核对模拟标签与精确金额未舍入。
4. 模拟不确定响应时确认原 key/固定输入，禁止第二次点击并发；报价到期后的下单确认走原 key。检查强制刷新前提示与生命周期边界。
5. 检查 168px 壳侧栏和原 53 导航未被辅助入口替代。尚未完成原站全部页面、正式收付款、退款、终端回执或实机验收。
