# M9 开发环境部署候选独立审查

- 日期：2026-11-02。
- 结论：**CHANGES_REQUIRED**。本候选不可按现有脚本执行部署；修正以下部署/验收增量后复审。不撤销既有实现 R4 的开发候选 PASS。
- 模型/层级：GPT-6 Astra，astra_reviewer / High（宿主固定角色配置；无额外运行时 effort 遥测）。独立于实施者，触发原因是主体隔离、并发和幂等规则部署前最终差异审查。
- 行为：只读检查云端源码/配置/证据，执行无业务写入的 Binder、PNG 内存诊断；未部署、未启动验收业务、未读取凭据、未操作原站。仅写本地审查原件。JShell 首次启动自动创建用户 preferences 目录，不涉及项目代码或服务配置。

## 绑定版本

- 工程工作树：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-engineering-20261102`，HEAD `a239164df6e34df19359e53b99f738cee94245f3`；独立核对工作树清洁。
- 实现沿用 R4：后端 `5717463b8bd1598d0f3a066055bc928c3ddb4f9d`，Miniapp `91c4abf9898e6344933f212a02ef1965cceb2d23`。本次未重扫未变实现。
- 后端候选 JAR：`1884012860d0de3ae8bad21461843bac5e3c77dc9c35690614ab7584f0e65e6b`；H5 index：`ca4d334162e3ce2c135465919fcfc46fdb14beb115e976ca5f1790e303cf3bbc`（继承前轮核验，部署脚本也检查）。
- 本轮独立重算新增六文件 SHA256：

| 文件（相对工程根） | SHA256 |
|---|---|
| ops/runtime/candidates/m9/50-m9-image-collage.conf | 5fb33764ced7f379596e641d993bf45f6641ca55c23fe8d6c229f4cd83f70aa6 |
| ops/runtime/candidates/m9/nginx-print-system.conf | 16120f633299d048c4fc0a7ab93f2e87f639bb3c13c04ccb5736830fa46872e5 |
| ops/runtime/candidates/m9/print-miniapp-limits.conf | 34f58a6be63394f578449d1657692e673b9bced3f3176a74a104cac97d1634b9 |
| ops/runtime/deploy-m9-image-collage.sh | 89a6520f91ff5ab4c11f6946dc58c8a0558df1409c6b4957ec1f2ef359429ab4 |
| ops/acceptance/m9_image_collage_runtime_smoke.py | adcac58785199d6f57c8ed763ddcaa52a9896334a2d9bd336e5f8478bd0cb3ec |
| ops/acceptance/m9_image_collage_browser.mjs | 09d64843ce3004b1822af448185a838949eda67f16270c84d8459ea64bce5a22 |

## 阻断发现

### D1 / P1：暂存文件被 Nginx include，失败回滚不能恢复可加载配置

位置：`deploy-m9-image-collage.sh:110–118`、`:74–105`。

独立读取现机 `/etc/nginx/nginx.conf:60` 为 `include /etc/nginx/sites-enabled/*;`。第 111 行把新 site 写入该目录的 `print-system.next`；若第 112、113、114 行失败或第 115 行前中断，EXIT rollback 只恢复正式 site/limits，不删除 `.next`。恢复后的旧 zone 文件没有 M9 两个 zone，而被 include 的 `.next` 仍引用它们；还可能产生重复 server 定义。此时 `nginx -t` 失败，回滚不能恢复可重新加载的旧配置。第 93 行失败后仍执行 reload，且未清除致因。

必要修正：暂存移至所有 include glob 之外；回滚明确清理本批已知暂存路径，分别备份并恢复 available/enabled 的原状态，确认 `nginx -t` 成功后才 reload。用文件/命令替身或隔离配置做至少“暂存后失败”和“新配置已启用后失败”故障注入，核对旧 site/zone/dropin/两条发布链接和健康恢复。无需在正在演示的服务上故障注入。

### D2 / P2：直接开启功能，跳过既定关闭阶段与旧链路回归

位置：drop-in 的 `PRINT_MINIAPP_DEMO_IMAGE_COLLAGE_ENABLED=true`；部署脚本 `:117–126`、`:135–150`；设计 `docs/design/m9-image-collage-design.md:69`。

设计明确“部署时先关闭验证旧链路和 GET，再显式开启”。本脚本安装 enabled=true 后直接 publish；publisher 的健康/登录检查不能验证新增全局 multipart 配置对旧 PDF/单图上传的影响。新入口只检查一次匿名拒绝。尚未通过旧链路回归即暴露开启后的新路由。

必要修正：实现明确的关闭安装阶段，先验证关闭早拒绝、既有 PDF/单图正常处理和 GET，再显式开启并健康检查，最后开放相应 edge/UI。任何阶段失败执行同一可验证回滚；保留关闭/开启两阶段的证据。同步说明设计中的旧开关名与实际已核验配置名，避免操作者关闭错误开关。

### D3 / P2：运行验收脚本引用不存在的辅助模块，并且请求节奏与现有限流不匹配

位置：`m9_image_collage_runtime_smoke.py:18–26`、`:66–79`；`m7_image_runtime_smoke.py:29–49`。

现机固定路径 `/home/ubuntu/print-system/ops/acceptance/m7_image_runtime_smoke.py` 不存在；辅助模块只存在于本工程工作树，部署脚本也未发布它。因此脚本在入口参数解析前即 import 失败。辅助 request 没有重试/节流，匿名、创建、重放、顺序冲突四次上传紧邻执行；edge 10r/m、burst=2，且还复用之前探针和 M7 的限流区。短时第四次请求可能被 edge 429，而断言要求应用 409；创建后的 PDF inspector 也可能暂占共享渲染槽。

必要修正：从 `__file__` 同目录引用已固定的辅助模块，或明确发布和核验依赖；先执行不触发业务写入的 `--help`/导入检查。将业务语义验收与限流验收分开，业务脚本在有界等待 READY/限流恢复后保持原键重试，仅对明确的 429 做有限等待，不能吞掉其它错误或把重试当幂等成功。保留失败证据。

### D4 / P2：浏览器验收的两张 PNG 均损坏，正常拼版路径无法有效验证

位置：`m9_image_collage_browser.mjs:66–67`。

独立 Python 内存校验两个 Base64 fixture：每个 77 字节，IHDR CRC 正确；IDAT 长度 20，但 CRC 均不匹配，`zlib.decompress` 均报 `Error -5 ... incomplete or truncated stream`。这不是有效图片成功路径的输入，不能期望本系统严格图像校验生成 READY。

必要修正：使用可确定生成的有效 PNG（可复用 runtime smoke 的 PNG 生成方式），先独立解码并核对尺寸与颜色，再运行浏览器路径。脚本只使用新系统新建合成演示数据；原站、真实支付/打印和微信真机继续 NOT_RUN。当前浏览器脚本未执行。

### D5 / P2：H5 发布只核验 index，实际执行的 JS/CSS 未绑定已审构建

位置：`deploy-m9-image-collage.sh:25`、`:130–132`、`:155`。

只验证 index.html 不能保证其引用的页面脚本仍是已审查构建；任意已引用 chunk 内容变化，所有现有哈希检查仍通过。第 132 行在复制后生成清单不与已确认清单比较，且输出文件本身在 find 前已被创建，会被纳入自哈希，无法作为稳定可复核的发布清单。

必要修正：固定已验证 H5 全文件清单或归档哈希，部署前后校验全部文件和期望文件集合，清单排除自身，防止缺失/额外/变更 chunk 被误报为同一候选。该修正只处理交付绑定，不要求重新审查未变页面实现。

## 已确认满足的部分

- 新 site 相比现行配置仅增精确 collage 路由。POST 之外显式 403；没有放宽其它 app-api 路由。13MiB edge 请求体上限，global 2/per-IP 1，429 限流码，独立 body-temp，读取/发送超时与 15 秒渲染上限相容；仍监听回环 18080。
- 源码配置全局 multipart 单 part 16MiB、总请求 32MiB、阈值 0、part-count 10、part-header-size 1KiB；路由过滤器 13MiB 和业务 1–9 文件等保护继承 R4。真实容器 HTTP 生效及邻接 404 尚须部署后验证。
- 独立读取现机目录：Nginx `/var/lib/nginx/print-miniapp-collage-body` 为 `700:www-data:www-data`；Servlet multipart 为 `700:ubuntu:ubuntu`；systemd `PrivateTmp=yes`。本轮读取磁盘可用 42,395,074,560 字节，超过 10GiB。
- 使用现机 Spring Boot 3.5.15 / Spring 6.2.19 的真实 Binder、SystemEnvironmentPropertySource、ConfigurationPropertySources.attach 与候选实际属性类，读取非秘密 drop-in 在内存绑定：`BEAN_BOUND=true`，enabled=true，renderVersion/root 正确，`validateEnabled()` PASS。下划线名称在该实际实现可绑定，不把命名习惯当成缺陷。
- MultipartProperties 独立绑定结果：16,777,216B / 33,554,432B / 0B，location 为预期私有目录。第一次诊断缺 Servlet classpath，补入现机 Tomcat jar 后复测成功；没有将夹具 classpath 错误归咎候选。
- runtime smoke 的预期业务断言包括同键同文件、交换顺序冲突、跨主体 404、既有报价订单/模拟支付及租约 fencing 回执，未见将真实支付/打印标完成；helper 错误输出只给 HTTP 状态，不打印响应 token/凭据。其执行能力受 D3 阻断，本次未读取其中引用的秘密文件。

## 修复后最小部署门槛与证据边界

1. 提交上述六文件的必要修正及精确版本；只复审变化的部署/验收部分，不重做后端和页面 R4 整体审查。
2. 完成无服务写入的完整构建清单校验、脚本导入、PNG 解码、隔离回滚故障注入；现机 Nginx/systemd 配置检查与两阶段发布材料一并提供。
3. 获得部署候选增量 PASS 后才执行开发环境发布。发布后验证关闭阶段旧 PDF/单图、开启后实际路由/邻接/方法/413/429/part 限制和新系统合成闭环；浏览器到共享打印设置、三层临时目录清理与资源余量分别留证。
4. 当前 HTTP 业务验收、浏览器运行、部署/回滚实机执行均为 **NOT_RUN**。正式上线、微信开发工具/真机、真实支付退款、真实打印及用户验收均为 **NOT_RUN**。即使后续部署候选 PASS，也仅允许开发环境部署和验收，不是上述验收通过。
