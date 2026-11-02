# M9 微信小程序逐张上传适配设计

状态：`REVISED_FOR_ASTRA_HIGH_REVIEW_R2`

范围：只补齐 M9 图片拼版在微信小程序端的上传适配；保留已经部署的 H5 重复 `files` multipart 接口，不修改计价、订单、支付退款、打印任务、防重复或回执规则。

## 事实与问题

- 当前 H5 通过一次 `multipart/form-data` 请求提交 1–9 个同名 `files` part，闭环已部署验证。
- uni-app 官方文档明确：`files[]` 多文件上传只支持 App/H5，微信小程序的 `uploadFile` 只支持单文件，多文件必须重复调用。
- 微信官方维护的 `miniprogram-file-uploader` 说明单次上传约有 10MiB 限制；M9 已冻结单张 2MiB、合计 12MiB，因此用 `wx.request` 手工拼成单个 multipart 仍不能覆盖冻结上限。
- 当前 MP-WEIXIN 构建把 `files` 原样传给 `wx.uploadFile`，没有平台转换。现状只能算 H5 已验证、微信适配 `NOT_RUN`。

## 目标与非目标

目标链路：

`创建临时上传会话 → 按选择顺序逐张单文件上传 → 服务端确认全部 part → 一次提交拼版 → 既有 READY/报价/订单/模拟支付/任务/回执闭环`

非目标：

- 不改变既有 PDF 字节确定性、有序输入摘要、服务端权威计价或最终文件幂等语义。
- 不新增数据库表，不把原图写入长期对象存储，不接真实微信登录/支付/打印。
- 不宣称微信开发者工具或真机已验收；没有 AppID、合法 HTTPS 域名和真机证据时继续标记 `NOT_RUN`。

## 协议

所有端点继续只接收服务端演示会话，主体只由 `MiniappDemoSessionService.requireForWrite` 得出。客户端不得提交 tenant/store/principal。

### 微信原生请求通道

- 现有 H5 契约不变：Host 必须等于部署配置，签发和所有写入口的 Origin 必须精确等于 `allowedOrigin`。
- 微信原生请求不能伪造浏览器 Origin。新增独立配置 `wechatNativeEnabled=false` 和 `wechatAppId`；只有开关开启、Origin 为空、Host 精确匹配，且微信自动携带的 Referer 精确匹配 `https://servicewechat.com/{wechatAppId}/{devtools或数字版本}/page-frame.html` 时，才允许签发及写入。
- `wechatAppId` 只允许正式 AppID 格式并从部署环境提供，不写入仓库；未配置时启动校验保持原生通道关闭。当前没有 AppID，所以开发部署只能用 H5/HTTP fixture 验证，微信开发者工具和真机继续为 `NOT_RUN`。
- `MiniappDemoSessionService.issue/requireForWrite` 同时接收 Origin、Referer、Host；所有演示写 Controller 均传入 Referer，不仅限于拼版端点，从而使后续报价、订单、模拟支付、取消等原生闭环使用同一通道规则。
- Referer/Origin 只证明请求通道，不是用户身份凭据；tenant/store/principal 仍只来自数据库中有效且未过期的 bearer token。Nginx 必须覆盖 Host 并透传原始 Origin/Referer，不能接受客户端自报 channel header。
- HTTP 验证覆盖：H5 正确/错误/空 Origin；原生开关关闭；原生正确/错误 AppID Referer；原生非空 Origin；错误 Host。MP 构建验证不手工设置 Origin/Referer，并保留“真实平台是否自动携带预期 Referer”为真机 `NOT_RUN`。

### 1. 创建或恢复临时上传会话

`POST /app-api/print/miniapp/demo/image-collage-upload-sessions`

- Header：`Authorization`、符合上述 H5/微信通道契约的 Origin/Referer/Host、最终文件使用的 `Idempotency-Key`。
- JSON 仅允许渲染版本和有序 manifest，拒绝未知字段、查询参数和非 JSON：

  ```json
  {"renderVersion":"IMAGE_COLLAGE_A4_PORTRAIT_GRID_V1","files":[{"index":0,"sizeBytes":1234,"sha256":"64位小写十六进制"}]}
  ```

- manifest 必须为 1–9 个连续 index；每项大小 1..2MiB、合计不超过 12MiB。服务端按 M9 相同公式计算 `manifestDigest = SHA-256(ASCII(renderVersion) || uint32_be(count) || 每项 uint64_be(sizeBytes) || SHA-256(fileBytes))`。
- 服务端生成不可猜测 UUID `uploadId`，TTL 固定 15 分钟。
- 内存索引键为 `tenantId/storeId/principalId/SHA-256(idempotencyKey)`。只有同主体、同键、同 renderVersion 和同 manifestDigest 才返回同一个活动会话；数量、顺序、大小或摘要任一不同立即返回幂等冲突。
- 只有 manifest 完全一致且临时会话已经完成时，重放才直接返回同一 `DemoFileMetadata`，客户端不得重复上传。
- 服务重启后临时索引可丢失；同键允许创建新临时会话，最终提交仍由既有文件数据库唯一键判定同响应或内容冲突。

响应：

```json
{"code":0,"data":{"uploadId":"uuid","state":"OPEN","fileCount":2,"uploadedIndexes":[0],"expiresAtMs":0,"result":null}}
```

`state` 仅允许 `OPEN|COMMITTING|COMPLETE`。不得返回原图路径、摘要、幂等键或主体标识。

### 2. 逐张上传

`POST /app-api/print/miniapp/demo/image-collage-upload-sessions/{uploadId}/parts/{index}`

- `index` 为 0..fileCount-1；精确单文件 `multipart/form-data`，字段名固定 `file`，不允许文本字段、额外 part 或查询参数。
- 每张真实字节 1..2MiB；服务端以 64KiB 流式复制到私有临时文件并计算 SHA-256，真实大小和摘要必须精确匹配该 index 的 manifest；不能信任 MIME、文件名或声明长度。
- 同会话同 index、相同字节重放返回成功；同 index 不同字节返回 409，客户端必须取消该会话并以新最终键重新开始。
- 每个会话串行化 part 写入；不同会话受全局容量限制。写入使用同目录临时文件、`fsync`、原子重命名，禁止半文件成为已上传 part。
- 响应只返回 `uploadedIndexes`、总数、过期时间和状态，不返回摘要或磁盘信息。

### 3. 提交拼版

`POST /app-api/print/miniapp/demo/image-collage-upload-sessions/{uploadId}/complete`

- 请求体必须为空，不接收查询参数或客户端主体字段。
- 只有拥有者可提交；缺任一 index 返回 409，并给出已上传 index，不进入渲染。
- 在会话锁内把 `OPEN` 原子变为 `COMMITTING`，按 0..N-1 构造只读 `MultipartFile`；复用 M9 渲染服务和原最终 `Idempotency-Key`。
- 渲染器仍从实际临时文件独立复算有序输入摘要，并要求与 manifestDigest 相同；PDF 确定性和最终文件数据库唯一键完全沿用 M9，不得把 uploadId 纳入 PDF 或最终幂等摘要。
- 成功后状态为 `COMPLETE`，立即删除原图临时文件，只在内存保留最小结果到 TTL；重复 complete 返回同一元数据。
- 渲染繁忙、超时或临时存储不可用时恢复为 `OPEN` 并保留已上传 part，允许原会话显式重试；格式、像素、维度或内容永久无效时标为终止并清理，客户端需重新选择。
- 两个并发 complete 只能有一个进入渲染；另一个返回确定的 `COMMITTING`/冲突响应，不能启动第二个子进程。

### 4. 取消

`DELETE /app-api/print/miniapp/demo/image-collage-upload-sessions/{uploadId}`

- 仅拥有者可取消；`OPEN` 立即删除；`COMMITTING` 只标记取消请求，待当前渲染出口清理，不强杀共享渲染进程。
- 取消为尽力而为。客户端离页后即使网络请求未送达，TTL/重启清理仍必须回收。
- `COMPLETE` 只清理临时会话，不删除已经生成的演示 PDF；PDF 继续走既有 1 小时到期语义。

## 隔离、并发与容量

- `uploadId` 只作不可猜测定位符；每个操作都重新校验当前演示会话的 tenant/store/principal 与会话所有者。不存在、过期、已取消和跨主体统一返回 404，避免枚举。
- 临时根目录固定 `image-collage-upload-scratch`，根/会话目录 `0700`、文件 `0600`，拒绝符号链接；文件名只由服务端 index 生成。
- 单会话总真实字节仍为 12MiB。全局最多 4 个活动临时会话、每主体最多 1 个，理论原图占用上限 48MiB；创建前再次要求系统盘至少 10GiB 可用。
- 独立 `ScheduledExecutor` 每分钟清理过期 OPEN 会话；COMMITTING 只标记过期并在出口清理。新服务显式依赖 `MiniappDemoFileService` 完成初始化，启动时先调用 `requireStorageLockHeld()`，确认同一实例 OS 锁已持有后才创建/清理本命名空间；不得清理其它 scratch。
- 创建、part、complete、cancel、过期清理和关闭都进入同一会话同步机制，并维护统一活动操作计数。服务关闭先置 stopping、停止清理调度器、拒绝新操作，再等待全部 part/complete 有界退出；依赖销毁顺序保证该服务先于文件服务销毁。
- 关闭等待超时或被中断而仍未排空时，必须调用现有 `MiniappDemoFileService.retainStorageLockUntilProcessExit()`，不得让下一实例取得锁并删除活动目录；只有确认活动计数为零才允许正常释放共享锁。
- part 上传不占 M7/M9/PDF inspector 共享渲染槽；complete 在全部 part 安全落盘后才进入既有 M9 渲染并取得原共享槽。
- 活动会话、主体槽和全局槽的获取/释放必须覆盖成功、重复、冲突、取消、过期、异常与关闭出口；不得出现计数泄漏或重复释放。

## Nginx 与解析边界

- 保留现有 H5 `/image-collages` 13MiB 路由。
- 创建、complete、cancel 请求上限 16KiB；单 part 路由 `client_max_body_size 3m`，使用本批私有 Nginx body-temp。
- 扩展现有 M9 预解析准入 Filter，使其只匹配原 H5 精确路由和合法 `/image-collage-upload-sessions/{UUID}/parts/{0..8}` POST；两类请求共享容量 2 的上传槽。part 在 multipart 解析前要求合法 Content-Type、正 Content-Length 且不超过 3MiB，并按本请求上限检查磁盘至少还剩 10GiB；成功、异常、取消出口都只释放一次。
- 新 parts 路由在 Nginx 请求体缓冲前同时复用原 M9 全局连接区（全局 2）和每 IP 连接区（每 IP 1）；该边缘预算与 H5 `/image-collages` 共享，非法/不存在 uploadId 也不能绕过并占满 body-temp。后端容量 2 是第二道限制，不能替代边缘连接限制。
- 为逐张路由新增独立每 IP `60r/m`、`burst=12 nodelay` 限速区，足够一次 create + 9 part + complete；客户端仍串行上传，429 保留原会话和原键显式重试。不得复用现有 `10r/m, burst=2` 的 H5 上传限速。
- Spring 的全局 multipart 限制不放宽；单 part Controller 再核对恰好一个 part、名称、提交文件名和真实大小。非法 UUID/index/相邻路径不进入预解析准入。
- 外部只经 Nginx，后端继续只监听 `127.0.0.1:48080`。未知方法、相邻路径、非法 UUID/index 都拒绝。
- 不记录 Authorization、最终键、原文件名、原图内容、摘要或临时路径；访问日志只保留模板化路由/状态，不把 UUID 写入业务日志。

## 小程序适配

- H5 继续使用一次 `uni.uploadFile({ files })`，不回归成逐张协议。
- MP-WEIXIN：
  1. 用 `FileSystemManager.getFileInfo({digestAlgorithm:"sha256"})` 顺序取得每张真实大小和摘要，生成有序 manifest；摘要失败即停止，不读取整组图片到 JS 堆；
  2. 用 `uni.request` 提交 manifest 创建/恢复会话；
  3. 只有服务端返回 manifest 完全一致的 OPEN 状态才按选择顺序串行调用 `uni.uploadFile({filePath,name:"file"})`；
  4. 每次成功后校验 HTTP 状态和统一响应 envelope，再上传下一张；
  5. 全部确认后用 `uni.request` 调 complete；
  6. 上传进度按 `(已完成张数 + 当前单张进度)/总张数` 聚合；提交渲染阶段继续显示服务端处理状态。
- 页面 generation、提交会话 token 和最终幂等键规则不变。选择变化/删除/会话变化/离页会 abort 当前 UploadTask，并尽力 DELETE 临时会话；迟到回调不得写草稿或跳转。
- 网络响应丢失时保留 uploadId、最终键和 manifest；显式重试先调用 create 恢复状态，只有返回的 manifest 状态一致才跳过已确认 index 或返回 COMPLETE。会话 404/过期则以同最终键、同 manifest 创建新临时会话并重传。
- 只接受服务端返回 `COMPLETE` 且元数据合法，随后仍等待既有文件 `READY(pageCount=1)` 才进入打印设置。
- 微信构建必须静态证明不再把 `files[]` 传给 MP-WEIXIN 的 `uploadFile`；H5 构建仍保留多 part。

## 错误与响应

- 复用统一 CommonResult；新增错误码必须固定、无敏感详情，并区分：输入非法、会话不存在、会话冲突、上传不完整、临时容量忙、临时存储不可用。
- 业务错误只由 HTTP success 回调接收也必须按 `statusCode + code + data` 三者校验；不得把“收到响应”当成功。
- 失败不能自动更换最终 `Idempotency-Key`。只有用户改变选择、会话主体变化或明确重新开始时才生成新键。

## 最少充分验证

### 服务端

- 创建同键同 manifest 重放；同数量换图、换序、改大小/摘要和同键不同数量全部冲突；跨主体/随机 UUID/过期统一隐藏。
- 1–9 张顺序上传；part 相同重放、不同内容或与 manifest 不符冲突；缺 part 拒绝；10 张、单张/总字节超限、额外 part、错误字段、空文件拒绝。
- 两个 complete 竞争只渲染一次；繁忙/超时可原会话重试；永久格式失败清理；取消/过期/服务关闭和重启无残留。
- 逐张协议生成的 PDF 与一次 multipart 接口在相同有序输入、同渲染版本下 SHA-256 完全一致；同键跨 H5/逐张协议的相同内容返回同文件，不同内容由最终数据库比较拒绝。
- 最终键重放、内容冲突、跨主体、READY、报价、订单、模拟支付、唯一任务和模拟回执回归。
- 三层临时目录清理、权限、磁盘余量、会话/global 槽无泄漏；快速串行九张不被旧限速误拒绝；旧 H5 与新 part 在 Nginx 和后端两层都共享全局上限 2、同 IP 上限 1，并以跨 IP 竞争验证；低磁盘、无效 uploadId 和非法相邻路径拒绝且不形成无界 body-temp。
- 用阻塞 part 写入和阻塞 complete 分别触发关闭，证明未排空时保留共享 OS 锁，第二实例不能取得锁或清理活动目录；排空后再正常释放。

### 小程序

- MP-WEIXIN 构建产物只出现单文件 `filePath/name` 上传；H5 仍出现 `files`。
- mock 覆盖 SHA-256 manifest、顺序、聚合进度、part 响应丢失重放、manifest 冲突、会话过期重建、abort/cancel、429 原键重试、迟到响应、会话变化和 complete 重放。
- H5 浏览器闭环回归；微信开发者工具和真机在具备 AppID/域名后单独验收，之前保持 `NOT_RUN`。

## 开关、部署与回退

- 新增 `PRINT_MINIAPP_DEMO_IMAGE_COLLAGE_STAGED_UPLOAD_ENABLED`、`PRINT_MINIAPP_DEMO_WECHAT_NATIVE_ENABLED`，均默认 false；`PRINT_MINIAPP_DEMO_WECHAT_APP_ID` 未配置时原生开关不得开启。M9 H5 原开关独立。
- 先以新开关 false 部署并回归现有链路，再开启逐张路由做 HTTP 闭环；异常时只关闭新开关并清理该命名空间，不回滚 M9 H5。
- 构建继续使用 2核4G 标准停四服务流程，构建后自动恢复 MySQL、Redis、Nginx、后端并等待健康；清理本批 scratch 和构建缓存，磁盘保留至少 10GiB。

## 审查边界

- 本方案新增后端写入、临时权限隔离、并发状态和最终幂等键复用，必须先取得 Astra High 设计 `PASS`，实现后再做 Astra High 最终差异审查。
- 设计/源码/HTTP/mock/微信构建通过仅代表开发候选；微信开发者工具、真机、正式域名、AppID、真实支付、真实打印和外部用户验收继续为 `NOT_RUN`。
