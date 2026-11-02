# M8 HTTP + Java + MySQL 隔离集成证据（2026-11-02）

结果：`PASS_ISOLATED_CANDIDATE`。实施模型：GPT-6.1 Sol High。

绑定版本：

- 后端：`cf6cdf0eda42cba969e0221ed4cd564cade7ffc3`
- 候选 JAR SHA-256：`629b0e151677f0dfa920ebae5fdbf5eff2a16384d442a4992bf1481e52edf512`
- SQL SHA-256：`b0cbf9be2d1620694e3db76a6faeb77496ce9eae2771129ae27a09bf6ed949c7`

实际执行 `ops/testing/m8-cancellation-integration/run.sh`。使用一次性数据库/用户、独立 49180 Java 端口、独立 16389 Redis 进程与文件目录；四项运行服务未替换。新测试订单由实际 HTTP 创建，模拟付款、后台建任务/动作和取消均经过实际 Java + MySQL 事务。文件及计价快照是新系统测试夹具，未执行原“云途智印”写操作。

通过范围：

| 验证 | 结果 |
| --- | --- |
| 未付取消，后续付款拒绝 | PASS |
| 已付无任务：全额模拟退款、反向借贷各一条 | PASS |
| 首次 QUEUED/fence0：取消任务、无打印回执、退款，后续领取拒绝 | PASS |
| CLAIMED 登记人工核实：版本不变；随后 ACCEPT 成功；历史申请快照保持 CLAIMED，当前任务 ACCEPTED | PASS |
| 同键原输入重放、同键改输入冲突、同订单异键返回已有标识 | PASS |
| 跨主体查询返回 404 | PASS |
| 退款账本插入中途强制失败：订单/取消/退款收据/账本全部回滚 | PASS |
| 付款与取消双方分别先赢：真实重叠，取消先赢后付款拒绝；付款先赢后旧版本取消 STALE_VERSION | PASS |
| 建任务与取消双方分别先赢：真实重叠，取消先赢后建任务拒绝；任务先赢后旧版本取消 STALE_VERSION | PASS |
| CLAIM 与取消双方分别先赢：真实重叠，取消先赢后领取拒绝；领取先赢后旧版本取消 STALE_VERSION | PASS |
| 取消提交前后 owner 6+6 次、admin 3+3 次读取：全部为完整旧/新快照 | PASS |
| 重启独立候选并关闭取消 POST：旧退款 GET 可读，新 POST 返回 1934001 | PASS |

六组竞争使用一次性数据库中的测试触发器与命名锁控制。候选事务先进入更新并持有业务锁，在 `PROCESSLIST` 观察到其等待 `User lock` 后启动对手，请求均未完成，再释放测试门闩。没有启用或修改全局 `performance_schema`。

原始去敏结果保留于 `docs/testing/m8-cancellation-integration-results/`：`r3-full-pass.json`（14 组）和 `r5-owner-admin-snapshot-pass.json`（增量 owner/admin 快照）。同时保留失败过程：r1 为测试触发器 SQL 分隔符；r2 为主机关闭 performance_schema 导致观察器未命中；r4 为新增 admin 测试误读只在列表存在的字段。均属测试脚本问题，未修改业务源码，失败结果没有改为通过。

清理核对：一次性数据库/用户计数均 0；49180/16389 端口关闭；本批 scratch 不存在；MySQL、Redis、Nginx、print-backend 均 active；磁盘可用约 41GiB。

证据边界：本次只证明隔离候选的 Java/MySQL 行为；运行库迁移、三端发布、真实支付退款、真实打印、微信真机与用户验收仍须分别取证。Astra High 最终增量复审与运行库部署门禁仍适用。
