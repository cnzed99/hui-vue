# 小程序累计 H5 候选发布记录

- 日期：2026-11-03
- 环境：云端开发演示候选
- 实施配置：GPT-6.1 Sol High；宿主实际模型和 effort 无独立遥测，未核验
- 独立审查复用：`sol_reviewer` / GPT-6.1 Sol High，对产品 `5b5c302535164086acb93c0f7c2a4b18df52cc61`、验收 `a044e30cb19886382d4b2aa4fa877fc052cd61e1` 的既有结论为 `PASS`
- Astra：未调用；本批没有修改计价、支付退款、权限隔离、并发状态、打印防重复、数据库或后端写入
- 结论：`DEPLOYED_DEVELOPMENT_CANDIDATE`

## 集成关系

- 累计分支 `print/phase1-miniapp-id-photo-entry-batch` 是已部署 ZIP H5 提交 `8cab60db4c0ea753a8fcc62ac2ab7f514315878d` 的后代。
- 两提交之间新增文件复印引导、智能证件照、结婚照和车辆行驶证首层页面及验收脚本。
- `src/api/**`、`src/utils/**`、`src/stores/**`、`src/types/**` 没有差异，因此没有改变 ZIP、报价、订单、模拟支付或回执接口契约。
- 产品提交到验收提交只改变 `ops/acceptance/id_photo_entry_batch_browser.mjs`。

## 发布和验证

- 发布脚本：`runtime/coordination/miniapp-cumulative-h5-deploy-20261103.sh`，SHA-256 `12a078ba6a8c5c8167bbe8be746c2e9aca699a5a3fc5a6a861d126fc031cf1f6`。
- 活动目录：`/var/www/print-system/releases/miniapp-zip-id-photo-entry-20261103-a044e30`。
- H5 index SHA-256：`989d78ac3fd165625b0ba3cacc874f28adee25fb7e4dbb8ea06717bffba042fd`。
- 发布前页面验收 6/6：智能证件照9规格与搜索、自定义规格边界、四步指南、结婚照首层、车辆行驶证首层、深链返回与隐私边界。
- 活动地址验收 3/3：ZIP入口与2MiB/20页/1小时提示、智能证件照搜索、零写请求/零外部请求。
- 四项服务均 active，后端健康，ZIP API GET方法边界返回405；磁盘剩余约38GiB。
- 证据：`evidence/miniapp/id-photo-entry-cumulative-predeploy-20261103.json`、`evidence/miniapp/miniapp-cumulative-live-smoke-20261103.json`、`evidence/miniapp-cumulative-h5-deploy-20261103.log`。

## 边界

真实微信工具/真机、真实证件照制作、证件照计价、真实支付退款、对象存储、实机打印和外部用户验收仍为 `NOT_RUN`。本记录不把本地首层页面或模拟闭环表述为真实业务接入完成。
