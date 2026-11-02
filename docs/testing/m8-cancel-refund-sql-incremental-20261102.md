# M8 SQL / 零写入恢复增量修正验证

实际实施模型：GPT-6.1 Sol。状态：`PASS_SQL_CONSTRAINTS_ONLY`，等待 Astra High 增量复审。

## 修正

- 有任务的两个 cancellation 分支要求 taskId、状态、版本、eventSeq、fence 完整非空；人工快照精确要求 eventSeq=taskVersion、orderVersion=taskVersion+2，申请前后版本保持相同。
- 退款收据新增固定 `cancellation_outcome=CANCELLED_REFUNDED`，通过包含 owner、id、order、outcome 的复合 FK 绑定取消记录；人工核实及未付取消不能关联退款。
- 零写入回退要求维护窗口先关闭入口、停止并排空 M8 后端/候选进程、连接及事务；`rollback-m8-no-writes-controlled.sh` 持共享锁，任何门禁失败即停止，DDL 完成后仍保持维护状态，切回 M7 并验证后才恢复流量。
- SQL 回退自身也检查同一会话的排空凭据并再次断言零写入；已有 M8 写入禁止删除表或恢复旧约束。

## 隔离实测

`ops/db/probe-m8-migration-isolated.sh` 从运行库只导出结构，创建 `print_m8_probe_<timestamp>_<random>` 一次性数据库。SQL 引擎验证结果：

- 迁移后 3 张表、6 个不可变触发器及 8 个主要 CHECK 存在。
- 零 M8 写入回退成功，三表和新增 task CHECK 被撤回。
- 四类合法 cancellation 通过；有任务分支逐一将 9 个任务字段置 NULL，18 个反例全部被 `chk_pmdc_outcome` 拒绝。
- 4 个错误快照版本/eventSeq 反例全部拒绝。
- 两类合法退款通过；人工/未付取消关联退款被 `fk_pmdrr_cancel` 拒绝；伪造 cancellation_outcome 被 `chk_pmdrr_fixed` 拒绝。
- 服务仍活动时受控执行器拒绝进入 DDL；持有在途事务时共用数据库排空门禁返回 76，三表保持存在。
- 写入一个合法人工记录后，零写入回退返回 SQLSTATE 45000，三表保持存在。

原始输出位于 `docs/testing/m8-cancel-refund-sql-incremental-raw.txt`，同时保存在云端工程外证据目录 `evidence/testing/m8-sql-incremental/probe-result.txt`。

## 边界

测试父记录为一次性库合成数据，种子阶段暂时关闭 FK 以省略无关上游图；所有 M8 合法和反例记录都在 FK 开启后执行，并验证退款复合 FK。没有读取或复制运行库业务数据。

本验证证明真实 MySQL 的 SQL 约束、DDL 和回退门禁，未证明完整 Java+MySQL HTTP 事务、付款/建任务/领取竞态、三端 UI、实机或用户验收。运行库没有应用迁移，原站没有产生数据。完整维护停服/切 M7 恢复演练尚未执行；本次只验证活动服务拒绝和排空门禁分支。
