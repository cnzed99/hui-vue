# M9 图片拼版最终实现增量复审 R2

日期：2026-11-02

结论：`CHANGES_REQUIRED`。原上传路径 P1 已在源码及组件检查层面修复；存储锁生命周期和首次选择后的会话轮换仍各有一个 P2，当前不能授予开发候选最终实现 PASS。

## 身份、范围与版本

- 独立审查者：`/root/m9_collage_final_astra_high_r2`，固定角色 `astra_reviewer`。
- 实际模型：宿主明确为 `gpt-6-astra`；High 由固定角色指令指定，没有另行读取运行时 effort 的接口。
- 强制触发：新增上传写入涉及主体范围、幂等和共享子进程并发，本轮核对上次三项发现的修复及直接调用链。
- 已亲自读取本地项目 `AGENTS.md`、云端 R1 审查原件、R2 材料、精确差异和相关原始证据。未修改实现、未操作服务或原站、未执行数据库迁移、未读取凭据。
- 云端三条工作树在检查时均干净，以下版本与哈希已亲自核验。

| 对象 | 版本 |
| --- | --- |
| 工程 HEAD | `372ac261cc6f877209e62875b6852f78c3239d70` |
| 设计基线 | `941da0dd296c1184736e71acf220992a6720778c` |
| 设计 SHA-256 | `d8ce1432611d107f3a5f9ac9a276a26f1829b4c1bf7410c3219aa627126b4911` |
| 后端增量 | `c9f9545889dbf11a04a9fe3b5dc244edd3cf1de0` → `672cffaaa29df6d900b7d951ceb7447eae716cff` |
| Miniapp 增量 | `24786e2bd6bddbbb7116a7b5ae70c7081c70cb4e` → `9af0efa9344b4b0e82cd15741bea6483c8c10c2d` |
| 候选 JAR SHA-256 | `9c4acda80eb5618ce4e59916a5e0cc171c0d2d10565860951798bc4d21ef3f71` |

工作树根目录：

- 后端：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-backend-20261102`
- Miniapp：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-miniapp-20261102`
- 工程：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-engineering-20261102`

## 必须修正

### R2-A / P2：OS 锁只做瞬时检查，关闭时仍可提前释放

位置：后端 `yudao-server/src/main/java/cn/iocoder/yudao/server/print/miniappdemo/imagecollage/MiniappDemoImageCollageProcessRenderer.java:50-84,161-165`；`.../file/MiniappDemoFileService.java:104-116,139-142,337-340`。

构造器新增对 FileService 的显式依赖，且 `initialize()` 在清理前调用锁检查，解决了原先的 Bean 初始化顺序问题；所读直接依赖中未发现循环。

但 `requireStorageLockHeld()` 只在渲染开始前读取 `FileLock.isValid()`，并没有登记活动请求，也没有持有覆盖复制、子进程及任务目录清理的生命周期租约。`MiniappDemoFileService.stop()` 仅等待自己的 PDF worker 结束，然后执行 `closeLock()`；M9 renderer 没有关闭或排空回调。当应用关闭或排空超时与已进入的 M9 渲染重叠时，锁可在 M9 子进程及 scratch 仍活动期间释放；另一个 JVM 此后可以取得同一锁并回收第一个 JVM 的 `task-*`。Spring 的 Bean 依赖顺序仅保证初始化/销毁先后，无法为没有销毁动作的 renderer 等待在途请求。

这仍属于 R1 报告明确要求的“锁的释放也覆盖受保护请求/子进程完整生命周期”，不是新增业务规则。本项为源码推断，没有对当前服务制造重启或双实例冲突。正常短请求可能在关闭前已经完成，但候选尚没有保证该边界的代码或绑定配置证据。

最小修正：为 M9 的完整受保护操作建立与关闭原子协调的活动登记/租约，关闭先拒绝新工作，再等待或终止并回收所有已登记子进程和 scratch 后释放 OS 锁；超时仍有活动工作时保留 OS 锁直到进程退出。也可以通过 renderer 的显式生命周期与 FileService 锁所有者协调实现，不要求重构无关业务模块。

必要验证：使用隔离存储根让一个实际操作停在“已取得生命周期保护、尚未结束清理”的可控位置，触发关闭并尝试第二个锁所有者；第二方不得在活动操作结束前取得锁或删除标记，结束后才允许接管及遗留回收。当前 `RendererChecks:64-67` 只注入一个抛异常的 Runnable，能证明“检查抛错前不清理”，不能证明真实锁与关闭之间的生命周期保护。

### R2-B / P2：首次选图后会话轮换仍会沿用旧选择上传

位置：Miniapp `src/pages/image-collage/index.vue:44-47,97-100,122-144`，以及会话来源 `src/api/demoSession.ts:32-54`。

当前 `initialize()` 只记录 ready/unavailable，没有将当前选择绑定到所取得的会话。`generateCollage()` 在 L127-L137 仅当已有幂等键、即 `retrying=true` 时比较旧 token。因此流程“会话 A 建立 → 用户选图 → 会话接近过期、ensureDemoSession 新建主体 B → 用户首次生成”会直接把 A 阶段的选择上传到 B，并保留选择，没有按已审设计 L62-L63 清空并要求重选。服务端 `MiniappDemoSessionService.issue:38-39` 为新会话生成新的 principalId/sessionId，因此这确实可能是主体变化，不能当成同一主体 token 无害刷新。

审查者在云端读取此提交的实际 Vue script，用现有 TypeScript 编译器在内存中转译，注入会话及上传替身执行上述顺序，等待事件循环完成后得到：

```json
{"test":"initial_selection_session_rotation","source":"actual_9af0efa_page_script_with_stubs","uploads":[{"token":"B","paths":["/synthetic/selected-under-A.png"],"key":"test-key"}],"selectionCount":1,"state":"uploading","expected":"no upload and require reselection after session rotation","violation":true}
```

其中 A/B、图片路径和请求键均为内存中的合成值；没有网络请求或真实上传。这是对实际页面逻辑的受控执行反例，不是浏览器、微信真机或服务器越权复现。未发现由此读取其他主体文件；风险是违反会话变化后重选的确认流程，产生非预期新主体上传。

最小修正：将选择本身绑定到初始化或选择时的会话 token/版本，与提交幂等键分开保存。每次生成包括首次生成，都在 ensure 返回后验证选择所属会话与当前会话，变化则递增 generation、终止旧 handle、清空选择/键/结果并要求重选。选择器异步回调也需绑定有效页面/选择会话，避免旧回调在会话清理后重新放回旧图片。

必要验证：在可控会话 A→B 下覆盖“首次生成”与“原键重试”，两者均不得上传旧选择；同会话重试仍保留原键；建立会话等待期间离页/删除选择以及继续设置等待期间离页仍须丢弃迟到动作。请保留运行日志，当前提交尚无这些前端运行原件。

## 已关闭或确认的部分

- R1/P1 的前缀问题：过滤器 L42-L57 使用 `WebProperties.appApi.prefix` 与 Servlet context path 精确比较。默认 `/app-api`、额外 context path、相邻路径检查已加入并有通过日志。
- 过滤器顺序为 `HIGHEST_PRECEDENCE + 20`；所读框架 body-cache filter 为 `MIN_VALUE + 500`，DispatcherServlet 在其后。其关闭、声明超限、并发 2 和空间检查在下游调用前执行。代码结构满足此层早拒绝目标；真实容器 multipart、Nginx 路由及原 PDF/单图 HTTP 回归仍待运行。
- M9 renderer 的 `@Autowired` 公共构造器明确选择 FileService 依赖，测试专用 Runnable 构造器为包可见；本轮未发现构造器选择或直接依赖循环问题。
- 前端已修复首次 await 前的 pending/generation/选择快照，上传 handle 按所有者清除；`continueToSettings()` await 后校验 mounted/generation/同一文件；原键重试检测会话变化并清空选择。这些改动有效，剩余问题是首次选择的会话归属。
- 增量仅涉及上述三个后端实现文件、检查文件及拼版页面；未改计价、订单、支付退款、打印任务或数据库结构。

## 已核验原始证据及限制

证据路径均在 `/home/ubuntu/print-system/evidence/`。本轮亲自读取原始日志、核对以下 SHA-256 与退出码，而非仅采用实施者口头报告。

| 证据 | 结果 | SHA-256 |
| --- | --- | --- |
| `build/m9-image-collage-fix-r1/backend-package.log` | BUILD SUCCESS，exit 0 | `d8090ca5ed83a6222057f5f6b9570a80a66ae836a40ce34e311fb27941db8538` |
| `build/m9-image-collage-fix-r1/m7-regression.log` | 7 PASS，exit 0 | `c0ce2746d750e9abc6b8ea8dd1303854a74517b5d76ab2c01182057f71fa038b` |
| `build/m9-image-collage-fix-r2/check.log` | 参数校验处失败，exit 1，原件保留 | `959325b1950dc78b1a6f3efcfaecd34a8f6727a7be53c9c3ec85246d99146e78` |
| `build/m9-image-collage-fix-r3/check.log` | 29 PASS，exit 0 | `f4479c4bf28ea971a182204eceed6e5dccee0e21bfdd66e51cfcf60dda43288b` |
| `build/m9-image-collage-fix-r1/miniapp-typecheck.log` | exit 0 | `a8e97d46287ca702862044efc3146425fb718568a5d42e421d42af23666bb61f` |
| `build/m9-image-collage-fix-r1/miniapp-h5-build.log` | exit 0 | `0caa1e132c6e0e2ab8ad13e7d403eb1d71e5a26d2743259b7bd6cf10dbbfaf8f` |
| `build/m9-image-collage-fix-r1/miniapp-mp-weixin-build.log` | exit 0 | `ee27dadc2789c4f0bcecc00269f38b0ca89b49883a417d9fad9cd61b6864d314` |

- 另已读取 `disk/m9-image-collage-fix-r1-latest.json`：build/recovery 均为 0、backendHttpHealthy=true、scratchRemoved=true。这是该构建窗口恢复记录，不是此刻全部服务的新运行检查。
- 后端采用 `-Dmaven.test.skip=true` 打包，不能解释为全部测试源码编译通过；29 项及 7 项为独立编译运行的检查。
- R2 失败原件证明发生在 `validateEnabled`。实施者解释为最低空间阈值夹具错误；本轮没有修复前测试文件的不可变快照可独立重建该具体赋值，但修复后源码使用 10GiB，R3 运行通过。
- 29 项调用过滤器方法及路径帮助方法，未启动真实 Servlet 容器；锁测试使用 Runnable 替身；图片为小尺寸合成图。它们不等于真实 HTTP、真实 OS 锁接管、9 张上限图内存峰值、浏览器迟到回调或用户验收。
- 未重复执行已通过的后端构建和渲染检查。为确认 R2-B，仅执行了不产生外部写入的实际页面脚本内存诊断。

## 后续最小门槛

修复上述两个 P2，提交准确增量及针对性原始验证日志后再复审。本报告不要求重复整仓审核或无变化的测试。

最终实现通过后，仍须绑定 Nginx/systemd/feature flag 配置，执行已审设计中的 HTTP 与 H5 闭环、三入口共享槽竞争、当前数据库约束核对、上限图内存及三层临时目录清理。微信多文件上传真机、真实资金、对象存储、真实打印端和外部用户验收保持 `NOT_RUN`。

本次只生成本地审查原件；没有实现修复、部署、迁移、重启或原系统写入。
