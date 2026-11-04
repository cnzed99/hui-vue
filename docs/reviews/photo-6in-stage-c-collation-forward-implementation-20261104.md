# Stage C collation forward repair 实施记录

基线 `e0816dafad22d7619decf9b17c3807860e278d48`。实施配置 GPT-6.1 Sol High，运行effort未独立核验。Astra High初审CHANGES_REQUIRED；当前状态 `IMPLEMENTED_TARGETED_PASS / EXACT_INCREMENT_REVIEW_REQUIRED / PRODUCTION_NOT_EXECUTED`，不能将隔离测试或旧PASS替代新增量复审。

唯一保护变更是quote trigger的显式字符集/collation及revision CAST，forward SQL只DROP/CREATE同名trigger，查询/owner条件/失败分支/handler/file→binding→profile三锁与原版保持一致。首次安装SQL同步修正但已执行历史SHA `28889a04225006e6a5186f1e63a1e96996b3e521577b1d01ab355d69448df8dd` 原样保留；现库只能forward，不重跑首次安装、不逆迁移。

定向验证：私有skip-networking mysqld真实复现unicode_ci数据库+0900创建连接的旧ERROR1267；forward18项、新库18项通过，拒绝均45000、最终gate0，修复后body逐字读回及旧保护归一化一致检查通过。首次精确body断言发现mysql CLI剥离说明注释，将注释移到DDL外后复验通过，未改查询。bash -n、Python AST和git diff --check通过；已移除本批__pycache__。

原件 `/home/ubuntu/print-system/evidence/photo-6in-stage-c-collation-forward-20261104/`，详细范围、hash、安全执行门禁与停止条件见 `docs/release/photo-6in-stage-c-collation-forward-repair-20261104.md` 和release-hashes JSON。安全执行脚本为未执行候选，需准确commit/SQL/批准旧body SHA及Astra source+migration+release PASS，备份后才允许DROP；失败、成功和中断均保持nginx/backend停写，读回正文不一致不得恢复。

本轮没有访问生产DB/schema/服务/凭据、没有改Java/JAR、没有生产迁移/服务切换，也不推送远程。Stage C仍阻断，后续D–G不得开始。修复提交交主任务按精确增量复审后，生产动作仍由主任务另行授权执行。
