# M9 微信逐张上传最终关键差异审查

本记录由主协调提供的独立审查结论整理，补录于部署后的文档收口；审查者只读，未执行部署。实际审查模型为 GPT-6 Astra High，角色 `astra_reviewer`。设计审查见 `m9-weixin-staged-upload-design-astra-review.md`。

## 初审

结论 `CHANGES_REQUIRED`，绑定后端 `4a34109859f3eddcc53ce5e8d9978f020cd98299`、小程序 `b44e5fa2ab6a404519654f595342cb958981ea40`、工程 `888ce39e5abbe6608461a55305624b940aabc8e4`。阻断项三项：

1. COMMITTING 会话在清理路径中可能误删，活动渲染的临时输入必须受会话状态及同步保护。
2. 清理调度抛出异常后可能永久停止，需保证后续清理仍执行。
3. 回滚到旧后端或关闭 staged 后无人清理暂存原图，需安全清理固定 scratch，并传播清理失败。

## 修复后增量复审

结论 `PASS`，允许按已审方案受控部署开发候选。准确绑定后端 `71e22c43606a620bc8ab9f1d100ff3630013e6ad`、小程序 `b44e5fa2ab6a404519654f595342cb958981ea40`、工程 `e4f9fec2258d4ff9f8a8d48f074becb7f33ac850`；JAR SHA256 `204c17310c99f2e2fa6dde8b0127d76ef5e9073028d1718760cdbb6987780ffc`。

后端修复状态/清理调度边界；工程回滚先停后端并确认退出，再持有与 Java FileLock 兼容的 POSIX 锁，只清理精确固定命名空间。异常路径、权限和链接门禁、清理失败导致回滚失败均有源检查及隔离验证。

PASS 覆盖上述源码、证据和候选部署方案；审查时实际部署为 `NOT_RUN`。不将之后变更的验收脚本绑定到此 PASS。

## 后续实际部署证据（与 Astra 审查版本分列）

部署工程提交 `09de3d2fa6f67f0d1e052cc70e5c472a60348847`；run `20261102T142140Z-1bdbec28`；证据 `evidence/deploy/m9-weixin-20261102T142140Z-1bdbec28/`。主协调已执行受控部署并确认 PASS，staged=true/native=false、四服务 active；运行及边缘门禁 JSON 已核对 PASS。

09de3d2 的验收脚本将9图请求绑定 `.5` 并复用单条 HTTP/1.1 keepalive 连接，11次均200、无重试并到READY；跨IP混合H5/part共享准入门禁通过。该脚本窄修另行检查，不表示 Astra 审过09de3d2。keepalive门禁不能证明uni.uploadFile的连接复用或微信平台行为。

微信开发者工具、真机、AppID接入、正式域名、真实支付退款、真实打印与外部用户验收均为 `NOT_RUN`。部署PASS仍为云端开发候选，不构成正式上线或平台验收。
