# M8 SQL 候选隔离验证（2026-11-02）

状态：`PASS_CANDIDATE_ONLY`；运行库未迁移，取消 POST 未开启。

## 运行库只读预检

执行 `ops/db/run-readonly-preflight.sh`，10 项 `violation_count` 全部为 0：

- M8 三表、取消订单/事件/任务均不存在；
- 首次排队与曾领取后回队列形态无异常；
- 原付款收据金额/币种与订单一致；
- 原模拟付款账本借贷两条完整；
- 现有任务与订单状态、版本关系一致。

该步骤仅 SELECT，没有写运行库。

## 隔离 schema 迁移与零写入回退

执行 `ops/db/probe-m8-migration-isolated.sh`：

```text
migration_schema_pass tables=3 triggers=6 checks=8
rollback_no_writes_pass tables=0 cancel_check=0
```

脚本从运行库只导出结构，在一次性数据库中应用候选迁移，再执行“无 M8 写入”回退。结束时通过 trap 删除一次性数据库和本批 scratch；系统盘仍约有 41GiB 可用。

## 证据边界

- 本次证明 SQL 在当前 MySQL 结构副本上可应用、关键表/触发器/CHECK 存在、零写入回退可执行。
- 尚未证明 Java 事务、并发竞态、幂等重放、模拟退款账本写入或三端完整联调。
- 最终源码/SQL/恢复方案仍需 Astra High 审查通过，之后才允许迁移开发运行库和开启取消 POST。

