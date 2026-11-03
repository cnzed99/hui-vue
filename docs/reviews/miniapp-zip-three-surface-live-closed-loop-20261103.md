# ZIP 三端在线闭环验证（2026-11-03）

## 结论

- 状态：`PASS`，11/11 检查通过，0 失败。
- 范围：小程序归档接口选择 ZIP 内 PDF → 服务端解析、计价、下单与模拟支付 → Vue 管理后台页面创建并处理唯一模拟打印任务 → 小程序重新进入并恢复同一订单及模拟成功回执。
- 环境：已部署开发候选，统一经 `http://127.0.0.1:18080` 的 Nginx 边缘入口访问。
- 本轮没有修改产品源码、数据库结构、计价、支付/退款、权限隔离或打印防重复规则；仅新增并执行验收脚本，因此未触发新的独立审查或 Astra High 强制条件。

## 绑定版本

- 后端活动版本：`runtime/releases/current -> zip-pdf-candidate-20261103-4ee185a`
- 小程序活动 H5：`/var/www/print-system/miniapp -> /var/www/print-system/releases/miniapp-zip-id-photo-entry-20261103-a044e30`
- 文档分支验证前提交：`docs/miniapp-tutorial-readonly@0d172fb6b96a73446d35dfdaa8ddbb9808e95a1f`
- 验收脚本：`runtime/coordination/zip_admin_ui_live_closed_loop.mjs`
- 验收脚本 SHA-256：`08c34d71a86bc3f14d110d706937715209eff2f8f7cced3c4014c855bd6d149b`

## 在线证据

- 报告：`evidence/miniapp/zip-admin-ui-live-closed-loop-20261103T131455538Z-c367cb8a/report.json`
- 报告 SHA-256：`17b3b62c43743a120ba4236a66d84f310f182c6836142291dc6e14dd866bfbdb`
- 后台成功页面截图：`admin-zip-success.png`，SHA-256 `14e4390681c067801255753bfd98482267bc9971be621798cfd83258095a252a`
- 小程序结果页截图：`miniapp-zip-result.png`，SHA-256 `499c125fc06317f4d74a5f0e7cfd1d3e9571b173700431467d9871d93859c4e0`
- 后台登录会话退出：`PASS`。
- 验收产生的隔离开发订单、任务和回执按不可变审计记录保留，未删除或篡改。

通过项包括：

1. 演示会话签发。
2. ZIP 字节哈希与候选 PDF 清单一致。
3. 选中的 ZIP 内 PDF 解析为一页并进入 `READY`。
4. 模拟支付在后台处理前完成。
5. Vue 后台页面读取同一小程序订单并显示“不会驱动真实打印机”的边界。
6. 后台页面完成创建、领取、受理、执行及模拟成功回执。
7. 小程序重新进入后恢复已支付订单和同一模拟回执，并保留“仅为系统模拟，不代表真实出纸”提示。
8. 小程序首页及底部导航可继续正常访问。

## 环境复核

- `mysql`、`redis-server`、`nginx`、`print-backend.service`：全部 `active`。
- 后端权限信息入口：HTTP 200。
- 小程序 H5：HTTP 200。
- 系统盘：59G 总量，约 19G 已用、38G 可用（34%），满足至少 10GiB 可用空间要求。
- 首次使用裸 `node` 命令因非交互 SSH 环境未配置 PATH 而退出；随后使用项目固定 Node 路径执行通过。这是启动方式问题，不是产品失败，也没有修改产品。

## 证据边界

- 真实微信登录：`NOT_RUN`
- 真实支付/退款：`NOT_RUN`
- 真实打印机：`NOT_RUN`
- 微信真机：`NOT_RUN`
- 外部独立验收：`NOT_RUN`
- 原“云途智印”后台和小程序：本轮未操作。

## 模型与审查

- 实施/联调：项目配置为 GPT-6.1 Sol High；当前运行环境未提供可核验的实际模型及 effort 遥测，因此标记“未核验”。
- 独立审查：本轮未调用。原因是没有产品源码或关键业务规则变更，只执行已部署、此前已审查链路的在线验收。
- Astra High：未触发；未设计或修改计价、支付退款、权限隔离、打印防重复规则，也未执行破坏性迁移或正式上线。
