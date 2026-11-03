# 文件复印单图里程碑审查记录

## 范围与版本

- 小程序分支：`print/phase1-miniapp-scan-file-copy`
- 最终提交：`1be1c6d980672afd584664b461c07aa83d137141`
- 基线提交：`a7a7c3bc3dcc5bce33a4c7ba067a235a9f61729a`
- 开发部署：`/var/www/print-system/releases/miniapp-scan-file-copy-final-20261103T0210Z-1be1c6d`
- 活动入口 SHA-256：`a993fc4fc03ee92ce05df2d6e608fdbc8b3c812775eb214cab8be6128b084982`

本里程碑只增加“扫描复印 → 文件复印”的单图来源入口和用途上下文，复用既有图片转 A4 PDF、打印设置及下游订单链。服务端、计价、支付退款、权限隔离、并发幂等和打印防重复规则均未修改。

## 构建与运行证据

- 最终标准构建窗口 `scan-file-copy-fix`：build=0、recovery=0、scratch 已清理、后端健康，MySQL、Redis、Nginx、后端均 active。
- `type-check`、H5 和微信小程序构建成功；Sass 保留上游 legacy API 弃用警告。
- 最终 H5 原件：`evidence/miniapp/scan-file-copy-20261103T015633174Z-2b9bedae/report.json`，11/11 PASS。
- 运行报告在浏览器启动前精确核对活动发布 realpath 和入口 SHA-256，再验证 H5 来源层、真实 filechooser、用途上下文、一次性交接、冷启动拒绝旧图、上传、单页 READY 和进入共用设置；外部请求与意外写请求为0。
- 下游打印设置、报价、订单、模拟支付、后台任务和回执源码无差异；本批不重复运行这些历史已验证链路，不把复用声明扩张为本批重新验收。

## 独立审查过程

- 审查角色：固定 `sol_reviewer`；模型配置和实际模型均为 GPT-6.1 Sol High，运行 effort 未另行核验。
- 初审：`CHANGES_REQUIRED`。临时图片持久键可能在退出后恢复旧图；H5“拍照”实际为普通文件选择器；烟测外部和写请求匹配不够精确。
- 修复：接收页挂载即消费 handoff；扫描入口和普通图片入口导航失败均清理；H5 只显示“选择图片”，微信继续区分拍照/相册；烟测使用精确 origin 和允许写路径并在 route 层阻断。
- 首次增量复审：`INSUFFICIENT_EVIDENCE`。10/10 原件仍记录旧提交，不能绑定最终实现。
- 证据修复：保留旧原件；最终脚本记录准确提交，并把活动发布路径和入口 SHA-256 纳入首项门禁；重跑得到11/11 PASS。
- 最终结论：`PASS`。版本绑定问题已关闭，未触发 Astra High。

## 剩余边界

- 微信仅完成编译，微信开发者工具与真机 `NOT_RUN`。
- 证件组合、自动矫正、自动裁边、去灰底/阴影和 OCR `NOT_RUN`。
- 真实支付退款、真实打印设备及用户外部验收 `NOT_RUN`。
