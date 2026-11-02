# M9 图片拼版最终实现增量复审材料（R2）

日期：2026-11-02

## 复审范围

- 设计基线：`941da0dd296c1184736e71acf220992a6720778c`；设计 SHA-256 `d8ce1432611d107f3a5f9ac9a276a26f1829b4c1bf7410c3219aa627126b4911`。
- R1 后端候选：`c9f9545889dbf11a04a9fe3b5dc244edd3cf1de0`。
- R2 后端候选：`672cffaaa29df6d900b7d951ceb7447eae716cff`。
- R1 小程序候选：`24786e2bd6bddbbb7116a7b5ae70c7081c70cb4e`。
- R2 小程序候选：`9af0efa9344b4b0e82cd15741bea6483c8c10c2d`。
- R1 审查结论：`CHANGES_REQUIRED`，见 `docs/reviews/m9-image-collage-final-astra-review-r1.md`。
- 本轮只请求核对 R1 的 R1/P1、R2/P2、R3/P2 三项修复及其直接影响；报价、订单、支付退款、打印任务和数据库结构均未修改。

## 修复映射

1. 上传准入路径：过滤器现在从 `WebProperties.appApi.prefix` 构建精确 app API 路径，并叠加 Servlet context path；不再把 MVC 的 `/app-api` 前缀误当成 context path。
2. scratch 生命周期：拼版渲染器的生产构造器显式依赖 `MiniappDemoFileService`，初始化清理及每次渲染前调用 `requireStorageLockHeld()`；未持有共享存储 OS 锁时，不会触碰 scratch。
3. 小程序异步屏障：生成动作在首次 await 前冻结 generation、选择快照、重试令牌并进入 pending；每个 await 后复核页面、generation、会话令牌和相关文件；会话变化会清空选择及请求键。上传 handle 只由所属动作清理，继续设置在写草稿和跳转前再次核对。

## 原始验证证据

证据根目录：`/home/ubuntu/print-system/evidence/build/`。

- `m9-image-collage-fix-r1/backend-package.log`：`-Dmaven.test.skip=true` 完整后端候选打包成功；候选 JAR SHA-256 为 `9c4acda80eb5618ce4e59916a5e0cc171c0d2d10565860951798bc4d21ef3f71`。该模式不等于全部测试源码编译通过。
- `m9-image-collage-fix-r1/m9-check.log`：修复后初版 standalone 检查 23 项通过，退出码 0。
- `m9-image-collage-fix-r1/m7-regression.log`：既有 M7 单图 standalone 检查 7 项通过，退出码 0。
- `m9-image-collage-fix-r1/miniapp-typecheck.log`、`miniapp-h5-build.log`、`miniapp-mp-weixin-build.log`：类型检查、H5 构建、微信构建退出码均为 0。
- `m9-image-collage-fix-r2/check.log`：扩充过滤器测试后第一次执行失败；原因是测试夹具将强制的 10GiB 最低空闲阈值错误设为 0。失败原件保留，没有覆盖；生产实现未因此修改。
- `m9-image-collage-fix-r3/check.log`：纠正夹具后 29 项通过，退出码 0，最大 RSS 131272 KiB。新增覆盖 `/app-api`/context 路径、关闭状态、声明长度超限、低磁盘、第三并发上传在下游前拒绝，以及未持锁时活动 scratch 保留。
- `evidence/disk/m9-image-collage-fix-r1-latest.json`：标准构建窗口 build=0、recovery=0、scratchRemoved=true、backendHttpHealthy=true。

关键证据 SHA-256：

- R1 后端打包日志：`d8090ca5ed83a6222057f5f6b9570a80a66ae836a40ce34e311fb27941db8538`
- R1 M7 回归日志：`c0ce2746d750e9abc6b8ea8dd1303854a74517b5d76ab2c01182057f71fa038b`
- R2 失败原件：`959325b1950dc78b1a6f3efcfaecd34a8f6727a7be53c9c3ec85246d99146e78`
- R3 29 项通过原件：`f4479c4bf28ea971a182204eceed6e5dccee0e21bfdd66e51cfcf60dda43288b`
- Miniapp 类型检查：`a8e97d46287ca702862044efc3146425fb718568a5d42e421d42af23666bb61f`
- H5 构建：`0caa1e132c6e0e2ab8ad13e7d403eb1d71e5a26d2743259b7bd6cf10dbbfaf8f`
- 微信构建：`ee27dadc2789c4f0bcecc00269f38b0ca89b49883a417d9fad9cd61b6864d314`

## 当前边界

- 后端与小程序候选分支工作树干净并已推送到用户 fork 的对应显式分支。
- MySQL、Redis、Nginx、当前 M8 后端均为 active；M9 尚未部署或启用。
- H5 可控延迟的浏览器运行验证、候选 HTTP multipart、Nginx/systemd 配置、微信真机、真实支付/对象存储/打印设备和用户验收仍为 `NOT_RUN`。
- 本材料不请求把源码/构建审查解释为部署、HTTP、真机、生产或用户验收 PASS。
