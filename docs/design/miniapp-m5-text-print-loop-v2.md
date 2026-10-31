# M5 自定义文字打印闭环设计 v2

日期：2026-10-31
状态：已按 Astra High 初审意见修订，待增量复审

## 目标与边界

- 把小程序“自定义文字”从本地页面接入新系统已有的：私有文件 → PDF解析 → 服务端权威计价 → 订单 → 模拟支付 → 配套后台唯一任务/回执 → 小程序结果恢复。
- 不修改计价算法、价格表、订单/支付状态机、权限模型或打印防重复规则；生成的 PDF 必须走现有 `MiniappDemoFileService` 和现有报价链路。
- 不接微信登录、真实支付、真实打印机、对象存储或原“云途智印”系统；资金和打印继续明确模拟。
- 本批固定 A4、黑白、普通正文排版；`14px/H1/H2/H3` 仍明确为待接入，不把未实现排版冒充可用。

## 服务端接口

新增精确接口：

```http
POST /app-api/print/miniapp/demo/text-files
Content-Type: application/json
Authorization: Bearer <demo-session>
Idempotency-Key: <1..128 printable ASCII>

{"content":"用户明确输入的文字"}
```

- JSON 必须只有 `content` 一个字段；禁止 query、未知字段、空白文本、NUL 与除换行/制表符外的控制字符。
- 统一换行到 `\n`，制表符渲染为四个空格；不做 NFKC 等会改变用户正文的规范化。
- “同文”定义为：只把 `CRLF` 与孤立 `CR` 统一为 `LF`，并把制表符展开为四个普通空格后的完全相同 Unicode code point 序列；不做 NFKC、trim 或其它会改变正文的规范化。幂等、正文摘要和渲染均使用这一结果。
- 同时限制不超过 10,000 Unicode code points、60 KiB UTF-8；Nginx `client_max_body_size 64k`。
- Controller 继续使用 `sessions.requireForWrite(authorization, origin, host)`；请求/响应正文不写访问日志；响应 `Cache-Control: no-store`、`Pragma: no-cache`。
- 失败使用独立、稳定的文字生成错误码：禁用/输入非法/超限/不支持字符/渲染并发满/渲染不可用。文件保存阶段继续使用既有文件错误码。

### 正文不落日志的异常边界

- 当前芋道全局 `GlobalExceptionHandler` 会在部分 `HttpMessageNotReadableException` 和普通异常路径中调用 `ServletUtils.getBody(request)` 并写 API 错误日志，因此本接口不得依赖全局兜底。
- Controller 必须局部处理：`HttpMessageNotReadableException`、`MiniappDemoTextException`、`MiniappDemoFileException`、`MiniappDemoSessionException`、`DataAccessException`、`TransactionException`，全部只返回固定错误码，不拼接正文、JSON解析消息、字符值或底层异常消息。
- Controller 还必须局部处理 `Exception.class` 作为本接口安全兜底；只记录固定事件名与异常类型枚举，不记录异常消息、原始堆栈、请求对象或正文，并返回固定 `RENDER_UNAVAILABLE`。预期异常在离开 service 前转换为上述固定异常，避免正文出现在异常消息中。
- 用包含唯一哨兵的畸形 JSON、不支持字形和文件阶段失败请求验证：systemd journal、API access log、API error log 均不得出现哨兵正文；异常响应也不得回显正文。

## 确定性 PDF 渲染

- 新增 `MiniappDemoTextProperties`、`MiniappDemoTextPdfRenderer`、`MiniappDemoTextFileService` 与 Controller。
- PDFBox 固定使用当前锁定版本 `3.0.8`；不增加第二套 PDF 库。
- 当前云端字体来源为 Debian `fonts-wqy-zenhei` 的 `wqy-zenhei.ttc`，许可证为 GPL-2 + Font embedding exception / M+ FONTS License。部署时复制为项目控制的只读运行资产：
  - 路径：`/home/ubuntu/print-system/runtime/fonts/wqy-zenhei.ttc`
  - SHA-256：`79c18ebe7b811951e8311bad7103ebeae8c337ed9988ea69e8a78a66cfe029b9`
  - TTC 字体名：`WenQuanYiZenHei`
- 启用文字打印时，启动阶段必须核对绝对路径、普通文件、非符号链接、文件哈希和字体名；任一不符则服务拒绝启动，不能回退到不支持中文的 Helvetica。
- 固定渲染版本 `TEXT_A4_WQY_V1`、A4尺寸、边距、字号、行距、按字体实际宽度换行。HTML/Markdown 按普通文字，不执行脚本或富文本。
- 遇到字体不支持的 code point 明确拒绝，不能静默替换为方框。
- 固定 PDF 元数据和 trailer document ID；ID 由渲染版本、字体哈希和规范化后正文摘要派生。相同输入同版本必须生成字节完全相同的 PDF，作为沿用现有文件幂等校验的前置验收门槛。
- 渲染前先完成有界排版：每形成一行都检查单调时钟截止时间；计算到需要第21页时立即拒绝，绝不先创建或渲染第21页。
- 只有排版页数 `1..20` 才创建 PDF。保存使用自定义有界输出流，同时限制最多 2 MiB 并在每次写入检查单调时钟截止时间；达到任一边界立即抛固定异常并丢弃全部字节，不进入文件表。
- 整个同步排版与PDF生成的应用内截止时间固定为5秒，低于 Nginx 15秒读取超时。不开异步后台渲染；请求线程在同一调用栈中完成或失败。

## 资源、幂等与文件复用

- 文字渲染单独使用公平信号量，云端 4GB 主机固定最多 1 个并发渲染；拿不到名额返回 429，不排无界内存队列。
- 信号量覆盖字体打开、排版、渲染、保存以及 `PDDocument`、TTC/字体和输出流关闭的完整区间；所有资源在 `finally`/try-with-resources 中释放后才释放信号量。没有后台任务在超时响应后继续占用CPU或名额。
- 生成 PDF 包装为只读 `MultipartFile`，调用既有 `MiniappDemoFileService.upload(identity, file, idempotencyKey)`。
- 不直接写 `READY`，必须经历既有 `UPLOADED → PROCESSING → READY/FAILED`；继续使用独立解析 JVM、96MiB heap、超时、scratch清理、2MiB/20页/1小时、每主体最多3文件、队列8、磁盘至少10GiB等约束。
- 同键同文重放必须返回同一文件；同键异文必须 409；并发同键只产生一条记录。若确定性 PDF 证明失败，停止本方案并改为正式的源文本摘要幂等设计，不用随机键掩盖。
- 文件、报价、订单与结果继续按 tenant/store/principal 三层作用域；跨主体读取维持与不存在相同的 404。

## 小程序

- 新增 `api/demoTextFiles.ts`；提交时把正文复制为不可变快照并为一次用户动作生成一个幂等键，网络重试复用该键。
- 编辑页状态：`idle → submitting → processing → ready | failed`；提交中禁用重复点击。
- 维护页面存活代次和 `sessionId/principalId` 上下文；离开、隐藏或会话变化后，旧响应不得保存草稿或跳转。
- 不把正文写入 localStorage、日志、URL或审查证据。成功 `READY` 后仅保存既有 `PrintDraft` 元数据：`createDraft('SERVER_UPLOAD', displayName, sizeBytes, pageCount, id)`，然后进入现有打印设置页。
- 后续的权威报价、订单、模拟支付、后台处理与回执恢复完全复用现有页面和接口；客户端不计算金额。

## Nginx 与部署

- 只新增精确 `location = /app-api/print/miniapp/demo/text-files`，只允许 POST，64 KiB body，5秒 body timeout，3秒连接、15秒读写超时，请求缓冲开启，复用上传接口的限速/连接区和 no-store 头。Nginx超时只是外层保护，不代替5秒应用截止时间。
- 不开放相邻 `/app-api/`；GET/PUT/DELETE 等方法必须拒绝。
- 字体安装脚本只从当前已存在、哈希匹配的本地资产复制，不联网下载；同时保存许可证副本。安装目标和来源均做绝对路径/普通文件/非符号链接核验。
- 后端与小程序构建继续按标准停四项服务、串行构建、自动恢复并等待健康；部署前先备份数据库和现有 release，发布后只保留一个回滚 release。

## 验证与审查

1. 渲染：中英文、换行、长行、分页；空白/控制符/不支持字形/超限；需要第21页时在创建前拒绝；有界输出超过2MiB立即拒绝；超时/中断后资源和信号量释放且下一正常请求成功；PDF 可被现有解析器读取；同一规范化输入在同进程、跨进程和服务重启后 SHA-256 完全一致。
2. HTTP：同键同文重放、同键异文409、并发同键一条记录、跨主体404、失效会话拒绝、活动文件/并发/大小限制、Nginx方法拒绝。
3. 复用计价：生成 READY 文件后使用现有报价接口，页数只能取服务端文件记录；报价快照、金额算法和价格版本不修改。
4. 隐私故障路径：以唯一哨兵正文覆盖畸形 JSON、不支持字符和达到文件阶段限制的失败；核对 HTTP 响应、journal、API access/error log 均无哨兵。局部 `Exception.class` 安全兜底做源码与针对性控制器检查。
5. 浏览器闭环：输入文字 → 生成/解析 → 设置 → 权威报价 → 订单 → 模拟支付 → 后台唯一任务/模拟回执 → 小程序恢复结果。
6. 真实微信、支付、打印机和外部验收继续为 `NOT_RUN`。
7. 因新增写入口、会话作用域、幂等/并发并复用计价链路，实施前与最终差异均必须由 Astra High 审查；Sol 完成实现与测试。
