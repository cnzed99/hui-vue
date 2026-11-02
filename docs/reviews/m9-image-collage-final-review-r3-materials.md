# M9 图片拼版最终实现增量复审材料（R3）

日期：2026-11-02

## 绑定版本

- 上轮工程材料：`372ac261cc6f877209e62875b6852f78c3239d70`。
- 上轮后端：`672cffaaa29df6d900b7d951ceb7447eae716cff`；本轮后端：`5717463b8bd1598d0f3a066055bc928c3ddb4f9d`。
- 上轮 Miniapp：`9af0efa9344b4b0e82cd15741bea6483c8c10c2d`；本轮 Miniapp：`717d8fa25d6e0e064df9f8842dc0b863d5d1d027`。
- R2 Astra High 结论：`CHANGES_REQUIRED`；原上传路由 P1 已关闭，剩余两个 P2 为关闭期锁生命周期与首次选图会话绑定。

## 两项增量修复

1. 后端渲染器在 `beginRender()` 内以生命周期监视器原子完成“未停止、OS 锁仍有效、活动数加一”；`@PreDestroy stop()` 先禁止新渲染，再等待全部活动渲染退出。若在渲染时限加 5 秒内仍不能排空，调用文件服务把 OS 锁保留到进程退出。渲染器生产构造依赖文件服务，因此 Spring 销毁顺序为渲染器先排空、文件服务后停止 worker 并决定是否释放锁。
2. Miniapp 在初始会话建立时记录 `activeSessionToken`，首次接受图片时记录 `selectionSessionToken`。生成前首次 await 返回后，无论新请求还是重试，都要求当前会话等于选择会话；否则清空选择、请求键及派生文件，要求重新选择。继续设置及轮询确认也同步更新或清除会话边界。

## 原始证据

- 目录：`/home/ubuntu/print-system/evidence/build/m9-image-collage-fix-r4`。
- 后端完整候选打包退出码 0；候选 JAR SHA-256 `1884012860d0de3ae8bad21461843bac5e3c77dc9c35690614ab7584f0e65e6b`。
- M9 standalone：33 项通过，退出码 0，最大 RSS 131332 KiB；包含关闭线程等待活动渲染、排空后拒绝新渲染和未触发保留锁的检查。日志 SHA-256 `4413d5fcd438a64ba75132d3c8f6a2c8708f5ce455ca29ba6d91b4147f6cafe1`。
- M7 standalone：7 项通过，退出码 0；日志 SHA-256 `16c5397b42c7da0822cb5f815dcd6bc131f69f3e77e3bfffbefd25cba665cb28`。
- Miniapp 类型检查、H5 构建、微信构建退出码均为 0；日志 SHA-256 分别为 `87dc6d64710b0a2c24f056313c9ed2399d30af91f275a21bddfddb5fe673286b`、`7e0aaaf01af8e8d2ebbe70ae1c034485fec60c2f3500eea573c534a2c553ed33`、`db3f64b992207bdae27f2967fa2de61695484e4daecf98bf185566cfb8585d2d`。
- 标准构建窗口 build=0、recovery=0、scratchRemoved=true、backendHttpHealthy=true；记录 SHA-256 `4fc1c764bd75d97f629d36c5edbbd6fcb742a0c36a858ce25f9f2286314e3b11`。
- 后端和 Miniapp 工作树干净，提交已推送；当前 M8 的 MySQL、Redis、Nginx 和后端均保持 active，M9 未部署。

## 证据边界

本轮请求 Astra High 仅复审上述两个 P2 的准确增量。候选 HTTP multipart、Nginx/systemd 配置、H5 可控延迟浏览器测试、微信真机、真实资金/对象存储/打印设备和用户验收仍为 `NOT_RUN`。源码和 standalone PASS 不代表部署或外部验收通过。
