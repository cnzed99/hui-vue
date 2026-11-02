# M7 单图片打印闭环审查记录

日期：2026-11-02
范围：单个 JPEG/PNG → 确定性 A4 纵向单页 PDF → 既有报价、订单、模拟支付、后台唯一任务、模拟回执和小程序结果查询。

## 绑定版本

- 后端分支：`print/phase1-backend-m7-image`
- 后端提交：`e2efa424a860b532b39861d68c96e9743e9278e1`
- 小程序分支：`print/phase1-miniapp-m7-image`
- 小程序提交：`d022b43bc55414e7f7dcf5bee595b57dacca6c01`
- 后端发布产物 SHA-256：`f95abc20388187c5c9d35401b45534403e9d2ff2d4e45108218b7505195a176c`
- 部署脚本 SHA-256：`9db0841b9b067bef38884099d80715993b80d1b6214648d311b5221872afb82b`
- 运行证据 SHA-256：`ac5699706ffcebf3673655ab818af72c062ec638740db7858d4ad640f15b36a7`

## Sol High 源码增量审查

- 角色：`sol_reviewer`
- 实际模型：GPT-6.1 Sol / High（由固定角色指定；宿主模型遥测未另行核验）
- 初审结论：`CHANGES_REQUIRED`
- 初审问题：父进程合并子进程 stdout/stderr 后要求整段只包含成功行；Spring 启动诊断会使真实转换成功仍映射为 `RENDER_UNAVAILABLE`。
- 修正：允许有界诊断行存在，但只接受一个完整 `OK:...` 成功行；仍要求进程退出码 0、输出常规文件、大小限制和固定异常映射。
- 最终结论：`PASS`。绑定后端提交及 `MiniappDemoImageProcessRenderer.java` SHA-256 `6a3746291d028b0856874da176e9fd44b9bda028233232acba5a4c9cd5b6b77c`。
- 审查者核对了脱敏闭环证据，确认图片 `READY`、1 页、模拟支付成功、任务成功和模拟回执；未重复扫描旧模块，未自行重跑运行验证。

## Astra High 部署失败门禁审查

- 触发条件：同一部署后路由探针连续两轮“诊断→修改→验证”仍失败，第三次修复前强制升级。
- 角色：`astra_reviewer`
- 实际模型：GPT-6 Astra / High（固定角色指定；宿主 effort 遥测未另行核验）
- 初审结论：`CHANGES_REQUIRED`
- 初审问题：探针缺少允许的 Origin，403 被误当成限流；固定状态断言未检查 JSON 业务码；回滚在 `set +e` 后可能忽略恢复失败；部署未持有标准构建共享锁。
- 修正：探针传入允许 Origin，严格验证 `HTTP 401 + application/json + code 1926003 + data null`；只有 429 有界重试；锁覆盖快照、发布、探针和回滚；回滚核对旧配置哈希、旧链接、四项服务和健康 JSON，失败明确返回 `ROLLBACK_FAILED`。
- 判断器验证：正确 401/1926003 接受；HTTP 200、403、500 和非 JSON 均拒绝；锁占用时脚本在任何变更前以 rc 75 退出。
- 增量复审结论：`PASS`，允许一次受控开发环境部署。该 PASS 不等于正式上线批准。

## 执行与证据

- 受控部署成功：后端 release `f95abc20388187c5`；小程序 release `miniapp-m7-image-20261102T044816Z-ed1f3461`。
- 部署探针：`HTTP 401`、`Content-Type: application/json;charset=UTF-8`、业务码 `1926003`。
- 小程序构建源、活动文件及 HTTP `/miniapp/` 返回内容 SHA-256 均为 `495b3342b02fe6c9eb513074138240de1edf13cb3f50effeb3d323b115a9771a`。
- `evidence/miniapp/m7-image-full-loop-20261102.json`：近像素上限合成 JPEG 经边缘入口生成 `READY` 单页 PDF，再完成既有权威报价、订单、模拟支付、后台任务、模拟回执和小程序结果查询。
- 后端、MySQL、Redis、Nginx 均为 active；执行后磁盘剩余约 41GB。

## 结论边界

- 当前结论是云端回环开发候选 `PASS`，不是正式上线或外部验收。
- 本批没有修改计价算法、支付/退款、权限作用域、打印任务状态机或防重复规则；只复用此前已审链路。
- 后端全量测试仍被仓库既有 Mockito 缺失阻断；本批不能宣称全量测试通过。
- 本批最终窗口未重跑微信构建；Windows 浏览器自动化因无法可靠判断 Chrome URL 按安全策略停止。
- 微信开发者工具/真机、真实支付退款、正式对象存储、真实打印机和外部用户验收仍为 `NOT_RUN`。
