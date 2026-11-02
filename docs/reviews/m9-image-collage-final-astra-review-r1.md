# M9 图片拼版开发候选最终实现审查

日期：2026-11-02

结论：`CHANGES_REQUIRED`。当前候选存在 1 个 P1 和 2 个 P2，不能授予最终实现 PASS 或据此部署启用 M9。按本轮要求完成审查后暂停，由实施者在恢复时修复、验证并提交受影响增量复审。

## 审查身份及范围

- 独立审查 Agent：`/root/m9_collage_final_astra_high`，角色 `astra_reviewer`。
- 实际模型：宿主标明 `gpt-6-astra`；High effort 由固定审查角色配置指定，无另行运行时推理档位读回接口。
- 强制触发：新增文件写入复用 tenant/store/principal 作用域、数据库幂等，以及 M7/M9/PDF inspector 的共享子进程并发准入。
- 已读取项目 `AGENTS.md`。本轮只读云端源码、差异、必要调用链与哈希；未改实现、未运行迁移、未部署、未操作原站、未读取凭据。只新增这份本地协调审查原件。
- 本结论仅针对开发候选最终实现；HTTP、浏览器、微信真机、生产上线和用户验收不在本次已验证范围。

## 准确版本

以下 HEAD、设计 SHA256、候选 jar SHA256 均由本审查者在云端只读核验一致；后端与小程序工作树当时均干净。

| 项目 | 精确版本 |
| --- | --- |
| 工程/设计 HEAD | `941da0dd296c1184736e71acf220992a6720778c` |
| 设计文件 | `/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-engineering-20261102/docs/design/m9-image-collage-design.md` |
| 设计 SHA256 | `d8ce1432611d107f3a5f9ac9a276a26f1829b4c1bf7410c3219aa627126b4911` |
| 后端 HEAD | `c9f9545889dbf11a04a9fe3b5dc244edd3cf1de0` |
| 后端差异基线 | `cf6cdf0eda42cba969e0221ed4cd564cade7ffc3` |
| 小程序 HEAD | `24786e2bd6bddbbb7116a7b5ae70c7081c70cb4e` |
| 小程序差异基线 | `6de36ca`（仓库中可解析的提交前缀） |
| 后端候选 jar SHA256 | `19c88a35e2874fee9e1542534a65d6a936aa9abc5e3bae9e0be03ae78a716403` |

后端根目录：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-backend-20261102`。

小程序根目录：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-miniapp-20261102`。

## 必要修改

### R1 / P1：真实 app API 路由跳过 multipart 前置准入过滤器

位置：后端 `yudao-server/src/main/java/cn/iocoder/yudao/server/print/miniappdemo/imagecollage/MiniappDemoImageCollageUploadAdmissionFilter.java:45-46`。

`shouldNotFilter` 比较的是 `contextPath + /print/miniapp/demo/image-collages`。芋道通过 `YudaoWebAutoConfiguration.java:50-77` 的 `RequestMappingHandlerMapping.setPathPrefixes` 给 app Controller 添加 `/app-api`；默认值见 `WebProperties.java:21`。这个前缀不是 Servlet contextPath。因此实际请求 `/app-api/print/miniapp/demo/image-collages` 会返回 `shouldNotFilter=true`，完整跳过第 52-77 行的开关早拒绝、声明长度上限、容量 2 准入和剩余磁盘检查。

影响：设计要求的 multipart 解析前防护没有覆盖实际公开路由。渲染器仍会在后续检查开关，故这不是功能开关允许生成文件的绕过，但关闭状态的请求仍可到达 multipart 解析，开启时也缺少应用层上传并发/磁盘防护。不能用待配置的 Nginx 限流替代已经承诺的这层防护。

修正：使用框架配置的 app API 前缀与 contextPath 构建精确匹配路径，或明确按正确的完整 Servlet 请求路径注册过滤器。确保它位于任何可能触发 multipart 解析的过滤器及 DispatcherServlet 之前。

必要验证：对实际 `/app-api/print/miniapp/demo/image-collages` 路由验证关闭开关、超限声明长度、第三个并发上传和低磁盘检查均在解析/调用下游前拒绝；保留相邻路由行为并回归原 PDF/单图上传。至少加入一个带框架前缀的过滤器用例，避免只测试省略前缀的人工路径。

### R2 / P2：启动时清理 M9 scratch 没有绑定到存储 OS 锁

位置：后端 `.../imagecollage/MiniappDemoImageCollageProcessRenderer.java:47-64,204-207`；锁实现位于 `.../file/MiniappDemoFileService.java:73-95`。

M9 renderer 的 `@PostConstruct initialize()` 直接扫描并删除共享 `image-collage-scratch/task-*`，其构造依赖只有 properties 与进程内 admission，没有 FileService/OS 锁服务依赖，也没有锁持有校验。现有 `.worker.lock` 是另一个 Bean 的初始化逻辑，不能从依赖图保证它先于 M9 清理成功。若第二个实例先初始化 renderer，可能先删除第一个实例正在渲染的任务目录，之后才在 FileService 取锁时失败。进程内 Semaphore 无法保护两个 JVM。

影响：不满足已审设计“持有单实例 OS 锁后才能回收遗留 task”的恢复边界，存在启动顺序导致活动输入被误清理的风险。本次为源码推断，未在实际服务上启动第二实例复现。

修正：把清理与接收渲染请求显式依赖于已成功持有的共享存储锁；可抽出单一生命周期锁服务，或以清晰依赖确保 FileService 取锁完成后才初始化 M9。不要以 Bean 扫描顺序作隐含保证。锁的释放也应覆盖受保护请求/子进程的完整生命周期。

必要验证：用隔离存储根测试第二个实例/初始化者无法在取锁失败前删除第一个实例的活动 task；锁释放后的重启可以清理真正遗留目录。不得对当前服务活动目录做破坏性复现。

### R3 / P2：异步页面动作没有完整落实离页、选择变化与会话变化屏障

位置：小程序 `src/pages/image-collage/index.vue:119-165`。

`generateCollage()` 在首次 `await ensureDemoSession()` 之前没有冻结 generation/选择快照或设置 pending，返回后也不检查页面是否已经卸载或选择是否改变；迟到动作可以重新设置 generation，并使用返回时的选择。`continueToSettings()` 只在 await 前检查 mounted，返回后未再次核对 mounted/generation/同一 generatedFile，存在迟到保存草稿和跳转的窗口。`isCurrent()` 比较的 token 只是本地提交副本，并未比对当前会话；失败后如果 ensureDemoSession 换了主体，代码会直接用旧图片与旧键在新主体下重试，违背设计中“会话变化后清除选择和键并要求重选”。

影响：不能据此宣称迟到响应、会话切换和离页行为已满足设计。服务端已有 owner 校验，未发现因此直接越权读取别人的文件；问题在于前端动作可能跨页面/会话边界继续生效或产生非预期新上传。

修正：在每个异步动作开始前保存 generation、选择/生成文件及会话版本；所有 await 后、上传开始前、写草稿及跳转前再次核验。将 pending 纳入提交禁用，避免会话等待期间重复触发；当前会话改变时废弃原选择、原键及旧回调。上传 handle 的清除也要以该 handle 仍属当前动作为条件。

必要验证：可控延迟下覆盖会话请求返回前离页/删除选择、连续点击、校验完成后继续动作期间离页，以及失败重试时换会话；确认旧动作不发起新的有效上传、不写草稿、不跳转，新主体要求重新选择。正常同一主体原键重试仍须成功。

## 已确认的积极结论与证据边界

- 本批差异没有修改报价算法、订单、支付退款、打印任务或回执实现；拼版只通过 `MiniappDemoFileService.upload` 保存派生 PDF。
- 身份由 `MiniappDemoSessionService.requireForWrite` 校验 token/Origin/Host 后产生；文件仓储 `findOwned` 与 `findIdempotent` 明确包含 tenant/store/principal；文件服务事务内再次锁定并验证会话。未发现新增客户端可注入主体的入口。
- 共享 admission 由一个 Spring Component 提供，Lease 具备原子一次释放；PDF worker 在 `claimNext` 前获取该槽，槽忙时不会为该待处理文件递增 processingAttempts。M7/M9/PDF 子进程终止路径在本次读取的调用链上持有槽至进程结束。
- M9 流式字节限制、有序内容摘要、固定元数据/文档 ID、单页输出、EXIF 变换、APNG 拒绝、子进程超时与受限堆在源码中存在；这不是完整运行证据。
- 已读 standalone 测试源码。17 项主要覆盖 1–9 数量的 A4 单页、重复渲染一致、交换顺序、手动占槽繁忙/释放、10 图、伪格式、像素限制和末尾 scratch 清理。它不等于三入口真实竞争、EXIF 1–8 可视断言、接近上限九图内存峰值或 HTTP multipart 行为测试。
- 实施者报告 M9 17/17、M7 7/7、vue-tsc/H5/mp-weixin 构建通过。本轮确认这些原始输出未持久化，审查者未独立核对原始日志，因此只作为实施者报告，不能升级为独立测试证据。
- Maven 使用 `-Dmaven.test.skip=true` 打包；普通 `-DskipTests` 路径存在既有 `PrintStoreProfileChecks` 缺 Mockito 的报告。未把候选 jar 的存在或哈希等同于全测试编译通过。
- 当前数据库唯一键/外键仅有先前实施者核验摘要；本审查未连接数据库重查。启用前仍需留存当前准确 schema 的无敏感字段核验结果。
- 候选 Nginx/systemd/env 配置未纳入此次版本，不能对其部署正确性授予 PASS。

## 修复后到启用前的最小后续门槛

1. 修复 R1–R3，提交准确增量；保留相关回归原始输出，由 Astra High 复审受影响内容，避免重复整仓审查。
2. 绑定 Nginx 精确路径/全局及每 IP 限额/body-temp、私有 Servlet multipart 路径、全部显式边界和 feature flag 配置。关闭状态验证旧 PDF/单图链路，再按已审方案在开发环境受控启用。
3. 取得真实 HTTP 幂等重放/冲突、跨主体拒绝、三入口争槽时 PDF 保持 UPLOADED 且 attempt 不增、上限图片的内存峰值、失败/重启三层临时目录清理证据；执行一条 H5 多图到既有报价/模拟订单/后台回执的闭环。
4. 微信多文件上传真机、真实资金/对象存储/打印设备、原版选图后外观及用户验收继续保持 NOT_RUN。不能以 mp-weixin 编译通过替代这些项目。

本次没有执行修复、服务重启、部署、数据库变更或原系统写入。
