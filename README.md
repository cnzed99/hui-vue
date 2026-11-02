# 打印系统微信小程序

技术栈：uni-app、Vue 3、TypeScript。云端目录 /home/ubuntu/print-system/src/miniapp。当前已有受限文件、原创文库、图片/拼版和订单开发闭环，资金与打印仍为模拟；状态见工程仓库 docs/plans/miniapp-roadmap.md。

```bash
npm ci
npm run type-check
npm run build:h5
npm run build:mp-weixin
npm run check:wechat-readiness:test
```

H5 默认同源 /app-api；无 HTTPS 配置的普通 MP 编译保留本地 fallback，仅为编译验证。源 manifest AppID 为空、urlCheck=false，不能作为正式 MP 联调包。

正式联调编译先复制 .env.wechat-integration.example 为 .env.wechat-integration.local，填写自己的公开 AppID、备案 HTTPS API 基址及 request/uploadFile/downloadFile origin。禁止在客户端配置 AppSecret、商户密钥/证书等秘密。

```bash
npm run check:wechat-readiness -- --output dist/wechat-readiness.json
npm run check:wechat-readiness -- --config-only
npm run build:mp-weixin:integration
```

默认清单命令退出1并报告 NOT_READY，本地配置不能证明外部接入与真机验收；--config-only 满足语法门禁时退出0，仅表示 READY_FOR_COMPILE。正式联调编译强制绝对 HTTPS FQDN、精确 /app-api 基址、非占位 AppID 及域名列表，拒绝本地/HTTP/IP、查询片段、凭据、路径越界。

联调产物为 dist/integration/mp-weixin，附 dist/integration/readiness.json；临时源注入 AppID 与 urlCheck=true，编译后核对生成配置，不修改源 manifest。失败非零退出并清除旧联调产物。普通构建仍输出 dist/build/h5 与 dist/build/mp-weixin。

所有 *_CONFIRMED 仅记录自述 DECLARED_NOT_VERIFIED；JSON 只输出白名单公开字段，外部微信、真实资金、真机与正式发布均不由本地门禁宣称完成。正式发布仍须真实证据及 Astra High 上线审查。合成测试 AppID/域名仅用于离线检查，不能复制为真实配置。
