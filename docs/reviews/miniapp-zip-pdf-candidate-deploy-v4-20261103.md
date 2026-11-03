# ZIP-PDF 开发候选 v4 部署记录

- 日期：2026-11-03
- 环境：云端开发演示候选，`http://127.0.0.1:18080`
- 结论：`DEPLOYED_DEVELOPMENT_CANDIDATE`
- 产品后端：`5ac26f944d11e50abc66d4fc6542c7f5da564259`
- 后端测试与部署计划：`4ee185a2873179ed022d36eb203c2d663990d8f7`
- 后端文档头：`30780cc451bf1f5d2861dba356c1af7d17c91e80`
- 小程序 H5：`089411c6ffe2af9628d77cf53b19119b03c4a8b6`
- 浏览器脚本头：`8cab60db4c0ea753a8fcc62ac2ab7f514315878d`

## 模型与审查

- 实施配置：GPT-6.1 Sol High；宿主实际模型和 effort 无独立遥测，未核验。
- 独立审查：`astra_reviewer`，宿主确认 `gpt-6-astra`，角色为 Astra High；实际 effort 无独立遥测。
- 首轮结论：`CHANGES_REQUIRED`。阻断项为部署脚本绑定旧文档提交号，以及回滚鉴权探针会被现有 10r/m 限流误伤。
- 修复后增量复审：`PASS`，只允许本次准确哈希的开发候选部署，不代表正式上线或用户验收。

准确部署脚本：

- backup：`970ab692916f51766dd5f9d8c5fcaaf9c0bb5b6a54450e39ee25e1c126431ab6`
- deploy：`d5f618d6252539201993fb2798ac70528c46947d3a22ccc70f819d9110adeb55`
- gates：`5b60b22c9987390378438be016ee6fbc79f9cf38c1f25f7a664e660c874a29c6`

## 部署前门禁

- 备份完成并在独立临时 MySQL 做全量恢复验证；对象归档校验通过。
- 隔离 Nginx 对当前全部 13 个 POST 和 1 个 DELETE 写入口均返回 503。
- worker 代际排空、故障回滚和未知来源计数关闭写入的 fixtures 通过。
- `rollback-original` 使用不同 loopback 来源连续验证 5 次 401，未放宽现有限流。
- 云端工作树、JAR、DDL、Nginx 与 H5 摘要均在脚本内精确绑定。

## 实际部署结果

- 维护排空 5 次连续稳定采样通过。
- 数据库已有 `archive_sha256`、`archive_entry_ordinal` 与 ENFORCED CHECK；本批没有再次执行 DDL，迁移前后文件行数均为 83。
- 候选后端启动健康；普通 PDF direct 与 edge 回归均通过。
- 候选 ZIP Nginx 路由和 H5 原子切换完成；四项服务均 active。
- 活动制品摘要：后端 JAR `1372b69126036c2f2c84289d697e1c468d2aedea5244cf0234a883dcb9142314`；H5 index `952a393ff0eae100d02b5d49b0adf208f937c1ab646543d5a1c9e19aa4d9968a`。
- 系统盘约 59 GiB，部署后剩余约 38 GiB。

## 活动环境闭环

真实边缘入口 12 项通过：演示会话、ZIP 摘要与目录、选中 PDF、READY/权威页数、权威报价、模拟订单、模拟支付、后台登录、CLAIM、ACCEPT、START、SUCCEED、模拟回执和后台同任务读取。

浏览器链在模拟支付后等待回执时停在 `print_result`，因为开发环境没有自动打印消费者，后台必须人工处理；这不是支付或 ZIP 失败。之后使用真实后台接口完成新订单的领取、执行和模拟成功回执，并由小程序接口读回同一回执。没有删除本批合成验收数据。

## 证据

- `evidence/zip-dev-v4-no-switch-fixtures-20261103.log`
- `evidence/zip-dev-v4-isolated-nginx-20261103.log`
- `evidence/zip-dev-candidate-deploy-v4-20261103.log`
- `evidence/miniapp/archive-pdf-live-candidate-20261103.json`
- `evidence/zip-live-api-closed-loop-20261103.json`
- `evidence/zip-live-api-closed-loop-20261103.log`

## 未完成边界

真实微信、真实支付退款、对象存储、实机打印、用户手工验收和正式上线均为 `NOT_RUN`。该部署仍是开发演示候选。
