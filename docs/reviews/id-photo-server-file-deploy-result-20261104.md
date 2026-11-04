# 证件照服务端文件链路候选部署结果（2026-11-04）

## 结论

- 状态：`PASS_DEPLOYED_DEVELOPMENT_CANDIDATE`
- 范围：证件照文件上传、隔离处理、来源持久化、小程序 H5 接入、普通文档计价隔离。
- 部署脚本：`runtime/coordination/deploy-id-photo-server-file-20261103.sh`
- 脚本 SHA-256：`8a0b5cbe1331f23c49315a23dcd18e86d7a9bdfb778125523d8c3a4adf04725d`
- Astra High 正式部署前审查：`PASS`，绑定上述精确 SHA-256。

## 部署对象

- 后端源码提交：`c94b866214cff55a127f73c7755122cc5974ac5c`
- 小程序源码提交：`ff741ea1e68c4e44f8f1833bbbd8949dedb40697`
- 后端 JAR SHA-256：`a39f9d44e7f50a26230db6de8fe77383cf55e48e120fd6fd049f804976cc8bd0`
- H5 `index.html` SHA-256：`69d93c169fed9249f57b24e016f6d9add92a37273afe855be81bc7781b7da968`
- 后端发布目录：`runtime/releases/id-photo-server-file-20261103-a39f9d4`
- H5 发布目录：`/var/www/print-system/releases/miniapp-id-photo-server-file-20261103-69d93c1`
- 部署备份：`runtime/deploy-backups/id-photo-server-file-20261103.ad0hEnFB`

## 实际执行结果

部署脚本输出：

```text
ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_PASS removed=0
ID_PHOTO_DEVELOPMENT_CANDIDATE_PASS
```

部署后另行核对：

- `mysql`、`redis-server`、`nginx`、`print-backend.service`：逐项 `active`。
- 当前后端链接：`id-photo-server-file-20261103-a39f9d4`。
- 当前 H5 链接：`miniapp-id-photo-server-file-20261103-69d93c1`。
- 线上后端 JAR 与 H5 哈希：均与候选一致。
- `print_miniapp_demo_file.source_kind/source_variant`：2 列存在。
- `trg_print_demo_quote_document_only`：1 个触发器存在。
- 后端目录接口：HTTP 200 且响应结构校验通过。
- 证件照上传路由 GET：HTTP 405；未放行路由：HTTP 404。
- Nginx 请求体临时文件：0；证件照私有任务临时目录：0。
- 部署备份 `artifacts.sha256`：校验通过。
- 证件照上传冒烟：处理达到 `READY`；证件照文件请求普通文档计价返回受控冲突，未产生普通文档报价。
- 系统盘：约 20 GiB 已用、37 GiB 可用（35%），满足至少 10 GiB 可用要求。

## 审查与证据边界

- 实施：项目配置为 GPT-6.1 Sol High；本次主会话实际底层 model/effort 未独立核验。
- 正式部署前独立审查：GPT-6 Astra，固定 `astra_reviewer` High 角色；运行 effort 未独立核验；结论 `PASS`。
- 本记录证明开发候选已部署且上述服务器验证通过，不等于微信真机验收、真实微信登录/支付退款、对象存储或真实打印设备接入完成。
- 一期整体仍为 `PARTIAL`；本里程碑不替代任务书逐项验收或用户验收。
