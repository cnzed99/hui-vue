# M5 自定义文字打印闭环 Astra High 审查记录

日期：2026-10-31
实施：GPT-6.1 Sol High
独立审查：`astra_reviewer`，GPT-6 Astra High
触发条件：计价/支付/打印防重复链路复用、文本隐私边界、结果查询权限入口、两轮部署与验收修复失败。

## 审查过程

1. 设计初审要求正文不进入持久化与日志，并要求渲染时限、固定字体/版本、幂等与有界并发；修订后设计 PASS。
2. 最终源码初审发现 `consumes=application/json` 可能使近似 Content-Type 绕过控制器局部 `@ApiAccessLog` 关闭正文记录。Sol 改为所有 POST 都命中控制器并在内部严格验证 Content-Type；增量复审 PASS。
3. 字体安装初版受 root umask 影响导致服务用户不可读。安装脚本改为目录 0755、字体/许可证 0444，并校验符号链接与 SHA；增量复审 PASS。
4. 部署连续两轮分别因字体父目录权限和 root 创建 release 目录权限失败；Astra High 审查第三次修复方案 PASS。Sol 只修正准确候选目录权限并让发布器以 ubuntu 运行，部署成功且回滚链保留。
5. `print-result` Nginx 初版只使用 `limit_except GET`，现场证据显示 Spring 隐式 HEAD 未执行控制器会话校验并返回 200。入口立即回滚；Astra High 将旧候选判为 CHANGES_REQUIRED，并通过“非 GET 在代理前 403”的修订设计。
6. 最终精确增量复审 PASS：只有 GET 到达已审的 tenant/store/principal 查询链；HEAD/POST/OPTIONS 403，GET 无/错会话 401，错 Origin 403，跨主体 404，相邻路径 404，成功与拒绝响应 no-store。

## 最终绑定哈希

| 对象 | SHA256 |
| --- | --- |
| 后端 release `23d0c838f0205ff4` 的 jar | `23d0c838f0205ff435fec1b584a27bdb0bdcaddb6f9a40a256292a225bdb6b90` |
| Nginx 候选与 active | `b46ff356f256d6669c09ec136fe17e550a2131495208b35b9e684643dc832c60` |
| API 验收脚本 | `c036242dcc188608f2091ad5e46700c7a2580122fef9f683166cbe23bcac1e50` |
| API 40/40 报告 | `413b9a34e638884a4eb8d2b0b04ad2bef656c2127317db30d3f22262d829ef2b` |
| 重启确定性脚本 | `f512de416daf1becc79e1a53353d65cad21e90c056b1bfc40665b89c0f11e252` |
| 重启确定性 7/7 报告 | `b77b4f156236c5116df1e76354d95e983c2d7625975572a35e8454d709078edf` |
| 页面闭环脚本 | `0cdb2e0c4a05dfec4a49d86a68494f221a6b7449cc793091933dabab6b459f97` |
| 页面闭环 11/11 报告 | `d7d8bcef2e19a5eeda3d43029830b16a9fa396ab30a075baa954bbf2f499f3d8` |

## 结论

最终结论：**PASS（云端开发候选与回环部署）**。四项服务 active，后端和小程序健康为 200；源码、配置、运行报告和截图证据一致。该结论不等于正式上线或外部验收。

## 未执行边界

- 真实微信设备/AppID：`NOT_RUN`
- 真实支付与退款：`NOT_RUN`
- 正式对象存储：`NOT_RUN`
- 真实打印机：`NOT_RUN`
- 外部用户验收：`NOT_RUN`
