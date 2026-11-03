# 新手教程只读里程碑审查记录

## 绑定范围

- 小程序分支：`print/phase1-miniapp-tutorial-list`
- 小程序提交：`a7a7c3bc3dcc5bce33a4c7ba067a235a9f61729a`
- 工程分支：`docs/miniapp-tutorial-readonly`
- 工程提交：`99564d1996702b113dc91a4b80bac228e8feac15`
- 活动发布：`/var/www/print-system/releases/miniapp-tutorial-final-20261103T0140Z-a7a7c3b`
- 活动入口 SHA-256：`cfcc74f51d61715247c8a75e0f3ba8acedd27a96b8317cddf0931fe3278b7e5e`

## 审查过程

1. `sol_reviewer` / GPT-6.1 Sol High 初审为 `CHANGES_REQUIRED`：特殊无效文章ID可命中对象继承属性；烟测没有覆盖全部外部HTTP(S)；历史警示和外链边界断言不足。
2. Sol 修复为对象自有键校验并补齐断言。H5同页连续hash导航两轮仍失败，按项目规则触发 `astra_reviewer` / GPT-6 Astra High。
3. Astra High 定位为当前 uni-h5 的同文档页面缓存复用，给出 `PASS_TO_FIX`：冷深链逐例使用独立页面验证；同页hash热切换保留为非门禁诊断，不得宣称框架问题已解决。
4. 增强外部请求检查首次捕获 uni-app 默认DCloud阴影图预载。实现没有加入域名白名单，而是在H5全局样式中禁用该默认预载，并在首个导航前增加请求级阻断和 `serviceWorkers: 'block'`。
5. 最终 Sol High 增量复审 `PASS`；Astra High 限定增量复审 `PASS`。角色配置由宿主固定；实际底层模型/effort没有另取运行遥测。

## 验证原件

证据目录：`evidence/miniapp/tutorial-final-20261103-a7a7c3b/`

- `smoke.log`：PASS；8页、7篇、5个外链视频边界、2篇共9步、6个无效冷深链、1个合法冷深链、7个历史警示；业务流程非GET、外部HTTP(S)、原站/公众号请求和阻断记录均为0；浏览器残留进程0。
- `build-window.json`：build=0、recovery=0、backendHttpHealthy=true、scratchRemoved=true。
- `sha256.txt`：烟测脚本、包装器和活动发布入口摘要。
- 烟测在业务流程前以受控外部GET及本机POST证明拦截器真实生效，清零探针记录后再运行只读业务流程；不会用拦截结果掩盖实际回归请求。

## 结论和边界

本里程碑允许收口教程列表、7个本地详情及冷深链只读展示。没有修改计价、支付退款、权限隔离或打印防重复规则。

`warmHashHotSwitch=RESOLVED` 仅表示最终一次短诊断未复现；路由源码没有修复 uni-h5 缓存机制，Astra High 的独立复现仍有效。不得宣称任意同文档hash热切换已经解决。微信开发者工具、真机、真实打印、真实支付和用户外部验收继续为 `NOT_RUN`。
