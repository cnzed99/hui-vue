# 打印系统微信小程序

技术栈：uni-app、Vue 3、TypeScript。云端目录：`/home/ubuntu/print-system/src/miniapp`。

当前批次只实现首页、文库、我的和底部导航，数据来自 `src/data/fixtures.ts`。微信登录、上传、计价、订单、支付、对象存储和打印端均未真实接入。

```bash
npm install
npm run type-check
npm run build:mp-weixin
npm run build:h5
```

构建输出位于 `dist/build/mp-weixin` 与 `dist/build/h5`。`manifest.json` 的 AppID 保持为空，禁止提交真实密钥。
