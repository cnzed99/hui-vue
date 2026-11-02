# M9 图片拼版最终实现增量复审 R3

日期：2026-11-02

结论：`CHANGES_REQUIRED`。后端关闭排空和首次生成会话绑定已修复；上轮已指出的选择器异步回调会话绑定仍缺失，保留一个 P2。下一轮仅需复审该前端补丁及受影响流程。

## 审查身份与准确版本

- Agent：`/root/m9_collage_final_astra_high_r2`；固定角色 `astra_reviewer`。
- 实际模型：宿主 `gpt-6-astra`；High 为固定角色配置，无单独的运行时 effort 读回接口。
- 触发：原 M9 上传主体边界、共享存储与子进程生命周期的关键增量复审。继续遵守已读取的项目 AGENTS.md。
- 本轮亲自核对以下 HEAD、干净工作树、差异、设计/JAR 哈希及原始日志；未修改实现、未部署、未访问原站或凭据。

| 对象 | 版本 |
| --- | --- |
| 工程 HEAD | `5b56cb3f68333b943e08faf69ac4f0d87af0fbe9` |
| 后端增量 | `672cffaaa29df6d900b7d951ceb7447eae716cff` → `5717463b8bd1598d0f3a066055bc928c3ddb4f9d` |
| Miniapp 增量 | `9af0efa9344b4b0e82cd15741bea6483c8c10c2d` → `717d8fa25d6e0e064df9f8842dc0b863d5d1d027` |
| 设计 SHA-256 | `d8ce1432611d107f3a5f9ac9a276a26f1829b4c1bf7410c3219aa627126b4911` |
| 候选 JAR SHA-256 | `1884012860d0de3ae8bad21461843bac5e3c77dc9c35690614ab7584f0e65e6b` |

工作树分别为 `/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-{engineering,backend,miniapp}-20261102`。

## 已关闭的修复项

1. **R2-A 锁生命周期：源码修复成立。** `MiniappDemoImageCollageProcessRenderer.java:95-125,213-229` 在同一 monitor 内原子检查 stopping/锁并登记活动数，外层 finally 在复制、子进程终止及任务清理完成后减计数。stop 先禁止新工作，timedWait 会释放 monitor 允许活动操作结束；超时或中断未排空时保留锁。生产构造器显式依赖 FileService，Spring 销毁依赖顺序使 renderer 的排空/保留标记发生在 FileService.stop 之前；FileService 的 volatile 标记阻止关闭锁。未在所读增量中发现监视器死锁或成功登记后漏减计数的正常异常出口。
2. **R2-B 首次生成的会话轮换：原反例已修复。** `activeSessionToken`/`selectionSessionToken` 让首次生成也比较选择会话。审查者用 717d8fa 实际 Vue script、现有 TypeScript 内存转译及与上轮相同的替身重跑：A 选图→B 首次生成得到 uploads=[]、selectionCount=0、state=failed；同会话 A→A 得到一次合成上传。该执行无真实请求或上传。
3. 原上传路由 P1 在本轮没有变化，保留前轮已关闭结论。

## 剩余 R3-A / P2：旧选择器回调可在会话重置后重建旧图片

位置：Miniapp `src/pages/image-collage/index.vue:57-70,103-110`，相关 `continueToSettings:180-191`、`resetForSessionChange:219-227`。

`chooseImages()` 创建的回调仍为 `value => acceptSelection(value)`，没有捕获打开选择器时的 generation/会话；`acceptSelection()` 也不校验 mounted，而是在回调到达时把图片绑定到当时的 `activeSessionToken`。当前 `chooseImages()` 未检查 `continuing`，所以等待继续设置的会话校验时仍可开启选择器。

反例：已有 A 会话生成结果 → 点击继续设置，ensure 会话尚未返回 → 打开选图器 → ensure 返回 B，页面按设计清空选择 → A 阶段开启的选择器迟到返回 → 回调把这批图片写入并标记为 B → 后续生成在 B 下上传。这个问题已经属于 R2 报告明确要求的“选择器异步回调也需绑定有效页面/选择会话”，未扩展复审范围。

审查者读取实际 R3 页面脚本，以内存替身控制 ensure 返回和选择器 success 回调，得到以下输出；其中 token、路径、键均为合成值，没有网络/存储写入：

```json
{"test":"same_session_normal","uploads":[{"token":"A","paths":["/synthetic/fixture.png"],"key":"test-key"}],"selectionCount":1,"state":"uploading"}
{"test":"initial_selection_session_rotation","uploads":[],"selectionCount":0,"state":"failed"}
{"test":"late_picker_after_session_reset","uploads":[{"token":"B","paths":["/synthetic/late-picked-under-A.png"],"key":"test-key"}],"selectionToken":"B","selectionCount":1,"drafts":0,"navigations":0,"violation":true}
```

最小修正：在 `chooseImages()` 打开时捕获页面 generation 和 activeSessionToken；success 回调先确认 mounted、generation 和捕获会话均仍一致，符合才接受图片。`continuing` 时禁止再次打开选择器；卸载及会话重置后旧回调必须失效。不要仅在回调时读取当前 token 重新标记旧图片。

必要验证：重跑上述正常同会话、首次会话变化及迟到选择器三条逻辑路径，并增加离页后旧 picker 回调不得恢复 images 的检查。无需因此重做后端实现或重复无变化的渲染构建。

## 原始日志核验

本轮亲自读取 `/home/ubuntu/print-system/evidence/build/m9-image-collage-fix-r4/` 并核对：

| 文件 | 结果 | SHA-256 |
| --- | --- | --- |
| `backend-package.log` | BUILD SUCCESS，exit 0 | `85fbdc1bb38393e7d101f8ac75692527e9857ac01c08587603ef0b034a2fc93a` |
| `m9-check.log` | 33 PASS，exit 0 | `4413d5fcd438a64ba75132d3c8f6a2c8708f5ce455ca29ba6d91b4147f6cafe1` |
| `m7-regression.log` | 7 PASS，exit 0 | `16c5397b42c7da0822cb5f815dcd6bc131f69f3e77e3bfffbefd25cba665cb28` |
| `miniapp-typecheck.log` | exit 0 | `87dc6d64710b0a2c24f056313c9ed2399d30af91f275a21bddfddb5fe673286b` |
| `miniapp-h5-build.log` | exit 0 | `7e0aaaf01af8e8d2ebbe70ae1c034485fec60c2f3500eea573c534a2c553ed33` |
| `miniapp-mp-weixin-build.log` | exit 0 | `db3f64b992207bdae27f2967fa2de61695484e4daecf98bf185566cfb8585d2d` |

`/home/ubuntu/print-system/evidence/disk/m9-image-collage-fix-r4-latest.json` 哈希为 `4fc1c764bd75d97f629d36c5edbbd6fcb742a0c36a858ce25f9f2286314e3b11`，记录 build=0、recovery=0、scratchRemoved=true、backendHttpHealthy=true。

33 项包含正常关闭排空和关闭后拒绝新渲染；锁检查/保留回调为替身，未直接执行真实双 JVM OS 锁接管或关闭超时路径。组件检查和源码推理支持此次生命周期修复，但仍需部署前隔离生命周期验证。打包采用 `-Dmaven.test.skip=true`，不等于全部测试源码编译通过。

## 后续门槛和边界

先提交上述唯一前端补丁、保存针对性运行原件并增量复审。通过后准备绑定配置并完成已有设计要求的真实 HTTP multipart、数据库约束、三入口争槽、隔离关闭/接管、上限图内存、三层临时目录清理及 H5 模拟闭环。浏览器/微信真机、部署、真实支付与打印、生产上线及用户验收均不能由本报告代替。

本轮只在云端执行读取和内存诊断，新增此本地审查原件；没有修复实现、重启、迁移或部署。
