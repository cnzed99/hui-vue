# M9 图片拼版最终实现增量复审 R4

日期：2026-11-02

结论：`PASS`，仅限下列准确版本的“开发候选最终实现”。R3 唯一剩余的迟到选择器回调 P2 已关闭；结合前轮已关闭的上传路径与锁生命周期问题，当前未发现阻止进入部署准备的未解决源码阻断项。本结论不等于 HTTP、浏览器、部署启用、微信真机、生产或用户验收通过。

## 身份与范围

- 独立审查 Agent：`/root/m9_collage_final_astra_high_r2`，固定角色 `astra_reviewer`。
- 实际模型：宿主明确为 `gpt-6-astra`；High 来自固定角色配置，无单独运行时 effort 读回接口。
- 强制触发：M9 上传主体边界及共享存储/子进程并发的关键实现复审。本轮仅核对 R3 剩余选择器回调修复，复用后端未变版本的前轮结论。
- 已遵守前轮读取的项目 AGENTS.md；只读代码、差异和证据，并在云端执行实际页面脚本的无外部写入内存诊断。未改实现、未读取凭据、未迁移、未部署、未操作原站。

## 绑定版本

以下 HEAD、工作树状态和哈希由本审查者亲自核验；三条工作树均干净。

| 对象 | 版本 |
| --- | --- |
| 工程 HEAD | `264dc1c39273115993e07ad43ad82f16073a6856` |
| 后端 HEAD（未变） | `5717463b8bd1598d0f3a066055bc928c3ddb4f9d` |
| Miniapp 增量 | `717d8fa25d6e0e064df9f8842dc0b863d5d1d027` → `91c4abf9898e6344933f212a02ef1965cceb2d23` |
| Miniapp 页面 SHA-256 | `603b86c1c095132ec2e1b5ce46d0c37e94107432f807e02ed3fcbaa815a6e918` |
| 候选 JAR SHA-256（未变） | `1884012860d0de3ae8bad21461843bac5e3c77dc9c35690614ab7584f0e65e6b` |

工作树根目录：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-{engineering,backend,miniapp}-20261102`。工程材料为 `docs/reviews/m9-image-collage-final-review-r4-materials.md`。

## 修复判定

Miniapp `src/pages/image-collage/index.vue:58-84` 在打开选择器时捕获 generation、activeSessionToken 和递增的 pickerRequest；success 回调在调用 acceptSelection 前检查 mounted、continuing 和三个快照。acceptSelection 再检查捕获 token 与当前 token 一致。

`resetForSessionChange:229-238` 与 `disposePage:241-249` 都推进 pickerRequest，已有 generation 也随失效操作变化。`continuing` 时不会再打开选择器。因此会话重置、离页、选项变化或新的选择器请求都会使旧回调被丢弃；不会把旧图片按回调到达时的新 token 重新绑定。

本增量只修改该页面。后端、计价、订单、支付退款、打印任务及数据库结构没有变化；未重复审核不受影响的实现。

## 独立执行的页面逻辑诊断

审查者在云端读取准确提交的实际 Vue script，用现有 TypeScript 编译器在内存中转译；仅按 H5 条件排除 MP-WEIXIN 分支，注入 ref/computed、ensure 会话、选图回调、上传、草稿和导航替身。真实页面函数被执行，未发起 HTTP 或真实上传。

6 条针对性诊断全部通过：

| 诊断 | 结果 |
| --- | --- |
| 正常 picker、同会话 A→A | 接受 1 张图，调用一次 A 的合成上传 |
| A 选图后首次生成时返回 B | 上传 0，选择清空 |
| continuing 期间尝试打开 picker | 不新增选择器请求 |
| 旧 picker 在 B 会话清理后返回 | 上传 0、选择 0、草稿 0、导航 0 |
| 卸载后旧 picker 返回 | 选择仍为 0 |
| 新 picker 替换旧 picker | 旧回调丢弃，当前回调正常接受 |

执行输出状态为 `PASS`，来源标记 `actual_91c4abf_H5_page_script_with_in_memory_stubs`。这是实际脚本配合替身的受控逻辑验证，不是浏览器 DOM/文件选择器或微信真机证据。上轮迟到回调反例已在该范围内关闭。

## 原始构建证据核验

本轮亲自读取并核对 `/home/ubuntu/print-system/evidence/build/m9-image-collage-fix-r5/`：

| 原件 | 结果 | SHA-256 |
| --- | --- | --- |
| `typecheck.log` | exit 0 | `dc594bf50185cef5f9d824ea24be8ad002b68bdb2c4f395c00d0daf7cfde7fbb` |
| `h5-build.log` | exit 0 | `96bdb1ce039937cf13f4af9e2f07e13bd83894dfd5fd5199bdc4a81d2ec7ec92` |
| `mp-weixin-build.log` | exit 0 | `6eecae8e43d715b358b2f3dcd45777f8eea6d2fd2aed6ace8b25cd9b79095e78` |

`evidence/disk/m9-image-collage-fix-r5-latest.json` SHA-256 为 `e97a730ab5aac7afd5485fda471307e5a1f93181c4416db868d60c070019f104`，记录 build=0、recovery=0、scratchRemoved=true、backendHttpHealthy=true。它证明该构建窗口的恢复结果，不代替未来部署后的健康检查。

后端保持 R3 的准确提交及 JAR，不重复其已核验的 33 项 M9、7 项 M7 standalone 与构建记录。前轮对真实 OS 锁接管、关闭超时和组件替身的证据边界继续有效。

## 部署前最小门槛与 NOT_RUN

1. 将 Nginx 精确路由、全局/每 IP 限额、body-temp、Servlet 私有 multipart 目录、显式资源边界、systemd 限制、功能开关及回滚方式纳入准确部署候选。当前配置未在本次实现 PASS 中绑定。
2. 在隔离或受控开发环境完成真实容器 multipart 与旧 PDF/单图回归、当前数据库约束核对、HTTP 主体/幂等边界、共享槽竞争，以及真实锁关闭/接管和临时文件清理验证。保留原始输出。
3. 按已审设计验证上限图片的系统级内存余量，并完成 H5 多图到 READY、权威报价、模拟支付、后台任务、模拟回执和结果查询闭环。浏览器离页/迟到回调与文件选择需取得运行证据。
4. 微信多文件上传真机、真实支付退款、对象存储、真实打印端、生产发布及用户验收仍为 `NOT_RUN`。mp-weixin 构建通过不能替代它们。

后续若改动关键实现或部署规则，复审受影响的准确增量；不要把本 PASS 改绑其他提交。当前可进入部署准备，不再重复已关闭的同版本源码审查。
