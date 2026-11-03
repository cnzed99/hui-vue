# 普通证件照服务端六寸排版文件设计（待 Astra High 审查）

## 目标与边界

把已经通过离线检查的 1 寸、2 寸、3 寸六寸相纸排版渲染器接入新系统演示会话，生成一个受主体隔离、最长保留 1 小时的 PDF 文件。小程序继续展示本地等比例排版，同时明确显示服务端文件生成状态。

本批不实现证件照价格、报价、订单、支付、打印任务或真实打印；证件照文件必须被现有普通 A4 文档报价入口拒绝，不能套用普通文档价格。原“云途智印”系统不参与本批，不上传、不下单、不付款。

## 接口

- 新增精确入口：`POST /app-api/print/miniapp/demo/id-photo-files`。
- `multipart/form-data` 只接受：
  - `file`：一张 JPEG 或 PNG；声明及实际读取均不超过 2 MiB。
  - `specification`：严格枚举 `ONE_INCH`、`TWO_INCH`、`THREE_INCH`。
- 必需请求头：现有演示会话 `Bearer`、`Idempotency-Key`；沿用 `Origin`、`Referer`、`Host` 写请求门禁。
- 不接受查询参数、额外表单字段、重复字段或其它格式；响应关闭请求和响应正文日志，并带 `Cache-Control: no-store`、`Pragma: no-cache`。
- 会话校验复用 `MiniappDemoSessionService.requireForWrite`，身份固定为 `tenant_id + store_id + principal_id`。
- 返回既有 `MiniappDemoFileMetadata`，不返回存储路径、原图、主体标识或服务端临时路径。
- Nginx 只发布该精确 POST；其它方法和相邻路径在代理前拒绝。请求体上限略高于 2 MiB multipart 包络；使用独立每 IP 限速和并发门禁，不放宽其它接口。

新增最高优先级的 `MiniappDemoIdPhotoAdmissionFilter`，在 Servlet/Spring multipart 解析前完成全部准入。该 filter 只匹配精确 app-api 路径，并按以下顺序处理：

1. 校验功能开关、精确 POST、无查询串、唯一十进制 `Content-Length`、无 `Transfer-Encoding`／`Content-Encoding`、合法且唯一 boundary、请求体总长不超过 2,100 KiB。
2. 在读取请求体前取得全局 1 个许可，再调用 `requireForWrite`；随后按 `tenant + store + principal` 取得每主体 1 个许可。任一步失败都不触发 multipart 解析或图片解码。
3. 调用 `MiniappDemoFileService.requireStorageLockHeld()`，并检查文件存储所在 FileStore 的可用空间不少于配置的最小空闲量再加本请求体、渲染 task 和 PDF 的保守峰值预算。
4. filter 自己使用有界流式解析器，只允许一个 `file` 和一个 `specification`，把文件写入存储锁保护下的私有 `0700 id-photo-multipart/task-*`；实际文件字节再限定 2 MiB。解析器包装请求并设置已核验 identity/part 属性，controller 只读取这些属性，禁止再次触发容器 multipart 解析。
5. admission 覆盖“读取请求体 → 渲染 → 文件服务提交/失败 → controller 返回”的整个区间；finally 删除本请求 multipart task，然后释放主体和全局许可。低磁盘、无效会话、并发满额、非法包络都不得创建 multipart 文件或启动 helper。

filter 进入关闭态后拒绝新请求，并等待已取得的许可全部归还；超时或中断时调用 `MiniappDemoFileService.retainStorageLockUntilProcessExit()`。初始化清理遗留 multipart task 前必须先验证文件服务仍持有唯一 OS 存储锁。

## 文件类型与数据库保护

在 `print_miniapp_demo_file` 增量增加：

- `source_kind varchar(24) ascii_bin NOT NULL DEFAULT 'DOCUMENT'`
- `source_variant varchar(24) ascii_bin NULL`
- ENFORCED CHECK：
  - `DOCUMENT` 必须对应 `source_variant IS NULL`；
  - `ID_PHOTO` 必须显式满足 `source_variant IS NOT NULL AND source_variant IN ('ONE_INCH','TWO_INCH','THREE_INCH')`，不能让 SQL `NULL/UNKNOWN` 绕过约束。

所有既有上传、ZIP、文字、文库、单图及多图生成都显式写入 `DOCUMENT/NULL`；证件照生成写入 `ID_PHOTO/<specification>`。不能依赖数据库默认值掩盖调用方漏传。

`MiniappDemoFileRow`、仓储查询和幂等重放同时绑定 `source_kind + source_variant`。证件照服务把外部幂等键域分离为固定长度的 `id-photo-file-v1:<sha256(specification + LF + external-key)>`：

- 同主体、同规格、同键、同图片重放返回同一文件；
- 同域键但生成内容不同返回幂等冲突；
- 不与普通文件、ZIP、图片或其它生成接口的相同外部键碰撞；
- 跨主体始终查不到对方文件。

普通报价仓储读取文件时同时读取 `source_kind`、`source_variant`。普通报价服务必须在价格档案、金额计算和报价写入之前拒绝任何非 `DOCUMENT/NULL` 文件，返回固定的“该文件类型尚未开放计价”错误，不生成报价快照。

数据库再增加 `BEFORE INSERT` 报价保护触发器：只有恰好存在一条 `id + tenant_id + store_id + principal_id` 全部等于 NEW 行、且为 `DOCUMENT/NULL` 的文件才允许写入 `print_miniapp_demo_quote`。文件不存在、主体不匹配、非 `DOCUMENT`、variant 非空或查询结果不是 1 均用固定 SQLSTATE 失败关闭。该触发器用于防止应用回滚到不认识 `source_kind` 的旧 JAR 后，把已存在的证件照文件错误套入普通文档价格。后续正式开放证件照计价时，修改此保护属于 Astra High 强制审查范围。

迁移只新增列、约束和触发器，不删表、列或数据。迁移前核对当前表结构、约束、触发器和行数并做验证备份；应用回滚时保留新列、CHECK 和触发器，不执行破坏性回滚。不能先启动会查询新列的新代码。固定启用顺序为：

1. 构建新 JAR，但不启动；证件照开关保持关闭，Nginx 不存在新入口。
2. 关闭小程序文件、归档、图片、拼版、报价、订单和支付等相关写入口，排空请求；停止 Nginx 和旧后端，确认旧进程、监听和数据库连接已退出。MySQL/Redis 仅在迁移窗口按受控方案保持或恢复。
3. 做可验证备份；应用新增列、显式 CHECK 和报价保护触发器。每个 DDL 都有前置结构断言和执行后读回。由于 MySQL DDL 可能逐条提交，任一步失败都停止后续步骤。
4. 迁移失败时不删除已经成功新增的列、CHECK 或触发器；保持新入口关闭，验证剩余 schema 对旧 JAR 向后兼容后恢复旧后端及旧 Nginx。若向后兼容验证不通过，保持入口关闭并人工处理，禁止破坏性回滚。
5. 迁移完整后，以新 JAR 启动但仍不开放新 Nginx 入口；验证普通文件队列/读取、普通报价、旧闭环及证件照应用/数据库双重拒价。
6. 新 JAR 启动或验证失败时，停止新进程、恢复旧 JAR；保留新增 schema 和触发器，再验证普通 DOCUMENT 文件及报价可用。即使已经存在 ID_PHOTO 行，旧 JAR 也只能读取/清理它，数据库触发器仍阻止普通报价。
7. 只有全部验证通过后才加入精确 Nginx 路由并开启证件照功能；开放后回滚必须先关闭新路由、排空请求，再切旧 JAR，且绝不移除计价保护。

## 渲染与资源边界

- 复用已审离线排版算法：152×102 mm、1 寸 5×2、2 寸 2×2 并顺时针旋转、3 寸 2×1；300 DPI 嵌入上限、固定 JPEG 质量降级、PDF 最大 2 MiB。
- Web JVM 不直接解码用户图片。新增独立 helper main 和 `MiniappDemoIdPhotoProcessRenderer`，沿用现有图片渲染进程边界：绝对 JAR、私有 `0700` task 目录、无 shell 拼接、固定参数、有限 stdout、超时终止、进程退出后精确清理。
- 子 JVM 固定 `-Xmx192m`、SerialGC、headless、最长 15 秒，并复用全局 `MiniappDemoProcessAdmission`；忙时返回固定 429，超时或执行环境不可用返回固定 503。
- 在分配大像素缓冲前识别 JPEG/PNG 并校验宽、高、总像素；拒绝动画、多帧、透明图、非 RGB、畸形元数据和超限文件。最大宽 2400、高 3394、总像素 8,145,600。
- helper 只输出一行固定成功收据；父进程校验退出码、收据、普通文件且非符号链接、PDF 大小及 PDF 基础结构，再交给既有文件服务。
- 生成 PDF 进入既有私有对象目录和异步 PDF 检查队列；继承每主体活动文件数、全局队列、至少 10 GiB 空间门禁、1 小时过期和失败清理。
- `MiniappDemoIdPhotoProcessRenderer` 构造时依赖 `MiniappDemoFileService` 的 `requireStorageLockHeld` 和 `retainStorageLockUntilProcessExit`。初始化必须先核验 OS 锁，再创建/核对私有 scratch 和清理遗留 task；每次 `beginRender` 在增加活动计数前再次核验锁。
- renderer 使用独立 lifecycle monitor、`stopping` 和 `activeRenders`。`@PreDestroy` 先置 stopping、拒绝新渲染，再等待在途渲染至“渲染超时 + 5 秒”；未排空或被中断时保留文件服务 OS 锁直到进程退出。每次结束在 finally 中终止仍存活子进程、关闭流、归还全局 process admission、精确清理本 task、减少活动计数并唤醒停机线程。
- 双实例重叠启动时，未取得文件服务唯一 OS 锁的新实例必须在任何 scratch 清理前失败；旧实例的活动目录不能被触碰。关闭、超时或异常不得留下用户原图或未引用 PDF。

## 小程序交互

- 当前本地排版预览保留，不能把 CSS 预览冒充服务端 PDF 或真实打印效果。
- 页面增加用户主动点击的“生成服务端排版文件”按钮；按钮旁明确提示“仅上传到新系统演示环境，最长保留 1 小时”。不自动上传个人照片。
- 点击后先取得当前演示会话，再通过 `uni.uploadFile` 发送本地图片和规格。页面代次同时绑定 `sessionId + principalId + 本地选择创建时间 + specification`；离页、重新选择、会话变化或旧请求迟到时，不得覆盖当前状态。
- 成功后只在当前页面内存保存 `fileId` 和状态并轮询到 `READY/FAILED/DELETED`；不把证件照 `fileId` 持久化为普通打印草稿，也不调用普通报价接口。
- READY 时显示“服务端六寸排版文件已生成，计价规则待确认”；底部按钮继续禁用报价/下单。失败、会话过期、限流和网络错误有明确可重试状态。

## 验证门禁

实现完成但部署前至少验证：

1. 三种规格的合成 JPEG/PNG 生成一页、尺寸和布局符合离线基准，重复输入字节稳定且 PDF 不超过 2 MiB。
2. 畸形、透明、多帧、超维度、超像素、超字节、额外字段、重复字段和非法规格全部固定失败；临时目录无残留。
3. 无效会话、并发满额和低磁盘在 multipart 解析前拒绝，不启动解码、不创建 multipart/task；同主体幂等、冲突、跨主体 404、Origin/Referer/Host、方法和 Nginx 相邻路径门禁通过。
4. 新 `source_kind/source_variant` 约束生效；既有普通文件明确为 `DOCUMENT/NULL`。
5. CHECK 的 ID_PHOTO NULL 反例、触发器的文件不存在/主体不匹配/非 DOCUMENT 反例全部失败关闭；普通文档报价和既有闭环不回归，证件照文件通过应用和数据库两层均不能生成普通报价，包括旧 JAR 回滚探针。
6. 模拟迁移中途失败和新 JAR 启动失败：旧服务可在保留已成功增量 schema/触发器的前提下恢复，相关写入口在健康验证前保持关闭。
7. 双实例启动失败不能清理活动实例目录；在途渲染停服能排空，超时/中断路径保留 OS 锁并最终清理自己的 task。
8. 小程序 H5/微信构建、合成图片页面流、离页/会话切换/迟到响应和零意外报价请求通过。
9. 标准安全构建窗口结束后四服务恢复、健康端点 200、磁盘剩余不少于 10 GiB，scratch 精确清理。

真实微信开发者工具/真机、真实个人照片效果、真实支付退款、真实打印机、裁切偏差和外部用户验收继续标记 `NOT_RUN`。
