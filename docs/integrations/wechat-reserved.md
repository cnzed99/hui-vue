# 微信能力预留与待申请事项

状态：接口占位，不包含任何密钥，不代表真实接入完成。

## 配置项

| 能力 | 建议配置名 | 当前状态 | 正式接入前置 |
| --- | --- | --- | --- |
| 小程序 AppID | `WECHAT_MINIAPP_APP_ID` | 空值 | 完成主体与小程序注册 |
| AppSecret | `WECHAT_MINIAPP_APP_SECRET` | 不入库 | 仅放服务器密钥管理/环境变量 |
| 登录 | `WECHAT_LOGIN_ENABLED` | `false` | `wx.login`、服务端 code2Session、账号绑定 |
| 微信支付 | `WECHAT_PAY_ENABLED` | `false` | 商户号、API v3 密钥、证书、支付目录 |
| 退款 | `WECHAT_REFUND_ENABLED` | `false` | 退款权限、回调、状态机和对账 |
| 订阅消息 | `WECHAT_SUBSCRIBE_ENABLED` | `false` | 模板申请、用户授权、发送记录 |
| 对象存储 | `PRINT_OBJECT_STORAGE_PROVIDER` | `stub` | 存储桶、临时凭证、跨域、生命周期 |
| 真实打印端 | `PRINT_TERMINAL_PROVIDER` | `simulated` | 协议、设备身份、心跳、回执、防重复 |

## 预留适配器

- `WechatLoginGateway`：用临时登录码换取受保护的身份信息；禁止在客户端保存 AppSecret。
- `WechatPayGateway`：统一下单、支付结果查询和回调验签。
- `WechatRefundGateway`：退款申请、结果查询和回调验签。
- `WechatSubscribeMessageGateway`：模板发送与发送记录。
- `ObjectStorageGateway`：签发短期上传凭证、确认对象和生成受控下载地址。
- `PrintTerminalGateway`：任务投递、心跳、领取、回执和异常恢复。

这些名称只是端口边界。支付退款、权限隔离、回调幂等和打印防重复的具体规则必须先经 Astra High 审查，再进入实现。

## 回调与域名待办

- 准备已备案 HTTPS 业务域名，并在微信公众平台配置 request/uploadFile/downloadFile/socket 合法域名。
- 为登录、支付、退款和订阅消息区分开发、测试、正式环境。
- 支付/退款回调必须使用 HTTPS 公网地址、验签、幂等处理和可审计原文摘要；不得记录完整密钥或敏感报文。
- 对象存储使用短期、最小权限凭证；上传后由服务端确认文件归属与类型。
- 正式发布前完成隐私保护指引、用户协议、类目与资质、体验版/审核版/正式版发布流程。

## 待用户提供或申请

1. 新系统小程序主体与 AppID（只确认是否具备，不在聊天或仓库发送密钥）。
2. 备案域名和 HTTPS 证书。
3. 微信支付商户号、API v3 证书体系及退款权限。
4. 订阅消息模板与隐私协议文本。
5. 对象存储账号/桶和真实打印端协议资料。

## M10 微信发布配置就绪门禁（2026-11-02）

本批只补配置与离线门禁，不接入外部微信能力，不改计价、资金、权限或防重复规则，不部署。当前状态为 **NOT_READY**：既有及本批普通 MP 产物仅证明编译，微信开发者工具、真机均 **NOT_RUN**。

### 已审计开发配置

- 小程序基线 b44e5fa2ab6a404519654f595342cb958981ea40；11 个 API 模块均读取 VITE_APP_API_BASE。
- H5 沿用同源 /app-api；MP 无 HTTPS 基址时沿用既有本地目录 fallback/不可用提示。该行为不能证明微信联调完成。
- 源 src/manifest.json 的 uni-app AppID 与 mp-weixin.appid 均为空，开发 urlCheck=false。普通 build:mp-weixin 保留编译验证行为。
- 新增无密钥 .env.wechat-integration.example，复制为 gitignored 的 .env.wechat-integration.local 后填本项目自己的公开配置。AppSecret、支付密钥/证书、数据库配置不得放入任何 VITE_* 或客户端配置。

### 命令与状态语义

在小程序独立工作树执行：

```bash
npm run check:wechat-readiness -- --output dist/wechat-readiness.json
npm run check:wechat-readiness -- --config-only
npm run build:mp-weixin:integration
```

- 默认 readiness 命令生成 schemaVersion=1 JSON，当前退出 1、status=NOT_READY。只输出白名单公开配置和布尔声明，非法输入不原样回显。
- --config-only 只判断 AppID/URL/域名列表的语法门禁，满足时退出 0、configStatus=READY_FOR_COMPILE。这不证明 AppID 归属、备案、TLS、DNS 或微信控制台已配置。
- 正式 MP 联调包必须使用 build:mp-weixin:integration。加载 wechat-integration mode，先校验再编译：API 必须为绝对 HTTPS FQDN，仅允许 /app-api（可有一个末尾 /）；拒绝 HTTP、本地/内部保留 Host、IP、原系统域名、占位域名、非443端口、userinfo、查询/片段、百分号/反斜杠和路径越界。AppID 必须为非已知占位的 wx 加16位小写十六进制；格式检查不证明真实有效。
- WECHAT_REQUEST_DOMAINS、WECHAT_UPLOAD_DOMAINS、WECHAT_DOWNLOAD_DOMAINS 为逗号分隔的 HTTPS origin，无路径；前两者必须含 API origin。这是待核实声明，不能代替微信公众平台实际白名单。
- 构建临时复制 src，注入公开 AppID 并强制 urlCheck=true，不改源 manifest；编译后核对 project.config.json 的 AppID/urlCheck。产物为 dist/integration/mp-weixin，JSON 为 dist/integration/readiness.json。
- 同名 Vite mode 同样执行门禁，直接绕过 wrapper 使用空 AppID/关闭 urlCheck 的 manifest 会失败。缺配置或编译失败均非零退出并明确 NOT_READY；旧 integration 产物被清除，临时输入目录在结束/可捕获终止时清除。
- status 始终保留 NOT_READY，因为本工具只检查本地配置。*_CONFIRMED=true 仅记录 DECLARED_NOT_VERIFIED；真机声明也不能把 deviceValidation=NOT_RUN 改成已验收。正式发布仍须真实证据和 Astra High 上线审查。

### 机器清单与外部门槛

| JSON 检查项 | 输入 | 当前真实状态 |
| --- | --- | --- |
| AppID | VITE_WECHAT_APP_ID | 缺失；未伪造 |
| API HTTPS 基址 | VITE_APP_API_BASE | 仅H5同源开发值；联调配置缺失 |
| request/uploadFile/downloadFile 合法域名 | WECHAT_REQUEST_DOMAINS / WECHAT_UPLOAD_DOMAINS / WECHAT_DOWNLOAD_DOMAINS | 缺失；未访问微信控制台 |
| 备案及 HTTPS | WECHAT_ICP_HTTPS_CONFIRMED | 未提供/未核实 |
| 微信域名控制台 | WECHAT_DOMAIN_CONSOLE_CONFIRMED | 未提供/未核实 |
| 隐私协议 | WECHAT_PRIVACY_CONFIRMED | 正式文本与平台声明未核实 |
| 类目/资质 | WECHAT_CATEGORY_QUALIFICATIONS_CONFIRMED | 未提供/未核实 |
| 订阅消息 | WECHAT_SUBSCRIBE_CONFIRMED | 模板与授权未接入 |
| 支付配置 | WECHAT_PAYMENT_CONFIRMED | 未接入；现有资金仅模拟 |
| 微信登录 | WECHAT_LOGIN_CONFIRMED | 未接入；演示会话不代表微信身份 |
| 开发者工具/真机 | WECHAT_DEVICE_VALIDATION_CONFIRMED | NOT_RUN |

本批合成正例仅使用脚本内明确合成 AppID/域名做离线编译，无网络请求；不写入正式配置，合成 integration 产物在反例检查中清除。备案、证书、域名白名单、隐私指引、类目、模板、支付商户及真机证据仍需提供或申请，不读取凭据。
