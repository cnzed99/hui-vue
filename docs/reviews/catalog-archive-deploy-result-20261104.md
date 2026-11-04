# 压缩包目录能力部署结果（2026-11-04）

## 结论

- 状态：`PASS_DEPLOYED_DEVELOPMENT_CANDIDATE`
- 源码提交：`faf2abd2b2d8c23c85beb8a1b8386001f09ca2ed`
- 候选 JAR SHA-256：`a7611c43765ca2b07f20a2e83106a9bfb5f58f7b3b4fe814ebab57afc6914795`
- 部署脚本 SHA-256：`9f1e5a9fad4cd5872f9404cc6ac1dbc3f767e67cc72435929a2939806f760b7a`
- Astra High 正式部署前审查：`PASS`，绑定上述精确脚本哈希。
- 部署备份：`runtime/deploy-backups/catalog-archive-20261104.OQ5Pk2LS`

## 验证结果

- 标准低内存打包：`BUILD SUCCESS`；四服务恢复和后端 HTTP 健康通过。
- 本次目录契约检查：`PASS public catalog, two direct PDF sources plus ZIP-contained PDF, unchanged limits`。
- 线上 catalog 版本：`miniapp-pdf-sources-2026-11-04`。
- 线上 `archive`：`PDF_AVAILABLE`，描述为“上传 ZIP 并选择其中的 PDF 打印”。
- 目录仍为 10 个入口，直接上传扩展名仍为 `pdf`；ZIP 使用独立受限归档接口。
- `mysql`、`redis-server`、`nginx`、`print-backend.service`：逐项 `active`。
- 证件照上传路由仍返回预期 GET 405；证件照普通文档计价保护触发器仍存在。
- Nginx 证件照请求体临时文件：0；系统盘约 37 GiB 可用。

## 失败记录与边界

- 首次 `-DskipTests package` 在既有 `PrintStoreProfileChecks` 缺 Mockito 依赖处停止；主源码已编译，但完整 `testCompile` 未通过。
- 随后按项目既有构建方式使用 `-Dmaven.test.skip=true` 完成打包，并单独执行本次目录契约检查。不得把该结果表述为完整测试套件通过。
- 本次只纠正已具备压缩包 PDF 闭环的公开目录能力，不改变归档解析、计价、订单、权限或打印任务规则。
- 实施按项目 GPT-6.1 Sol High 配置执行，主会话实际底层 model/effort 未独立核验；正式部署前审查实际模型 GPT-6 Astra、固定 High 角色，运行 effort 未独立核验。
