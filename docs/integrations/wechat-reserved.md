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
