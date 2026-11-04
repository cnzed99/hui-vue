# Stage C collation forward repair：待精确增量复审

状态：`STAGE_C_BLOCKED / FIX_PREPARED_NOT_EXECUTED / ASTRA_HIGH_INCREMENT_REVIEW_REQUIRED`。主任务报告旧首次安装SQL已执行，Stage C普通链路回归遇到collation冲突而阻断。本Worker没有访问生产DB、服务或凭据，没有改Java/JAR，也没有执行修复。按配置实施模型GPT-6.1 Sol High，运行effort未独立核验。

历史记录必须保留：已执行旧SQL来自 `e0816dafad22d7619decf9b17c3807860e278d48`，SHA `28889a04225006e6a5186f1e63a1e96996b3e521577b1d01ab355d69448df8dd`；当时迁移脚本SHA `f2eca0e0e64ba02ff46decfa361af802b3358ec71134263f4d4dd3a39080f151`。这两个旧hash不能改绑修正后的当前文件，不能重新跑首次安装迁移来修现库。

现库唯一修复SQL：`ops/db/print-miniapp-demo-photo-6in-trigger-collation-forward.sql`，SHA `327b6cb5c1073477886d047093d1ec0c8700ca6a5db75801af45d742eaa9380e`。仅SET NAMES、DROP/CREATE同名 `trg_print_demo_quote_document_only` 和SHOW读回；不改表、数据、binding、gate、不可变档案或权限。连接固定utf8mb4_bin；k/v/active_id/mode/currency按来源列显式ascii_bin，profile_key/version显式utf8mb4_bin，revision CAST指定utf8mb4且COLLATE utf8mb4_bin。原所有查询、主体条件、失败保护和file→binding→profile三次FOR UPDATE保持一致。

当前首次安装SQL同步修正，SHA `a5bba439bcb6d2c48aa0622ec0f33baca137a0c38480d2ac5a6574f801de68a3`，只适用于尚未安装的新库。当前已执行Stage C的库不得使用它；历史旧SQL仍可从Git准确提交按旧SHA恢复取证。

## 隔离验证

`ops/acceptance/probe-photo-6in-collation-forward.sh` 启动自己私有datadir/socket的mysqld，skip-networking、32MiB buffer pool；自建最小真实字符集/FK fixture，不读取生产schema、数据或凭据。临时目录经绝对路径校验，退出停止自己的mysqld并精确清理。

已实际复现database=`utf8mb4_unicode_ci`且trigger creation connection=`utf8mb4_0900_ai_ci`；从历史Git原件加载旧迁移，合法照片报价触发ERROR1267。forward修复后database仍unicode_ci，trigger creation connection变为utf8mb4_bin；触发器正文与修复SQL逐字读回一致。新库在相同database/初始connection条件下安装修正SQL也通过。

两个矩阵各18项PASS：document合法/错profile/错owner；gate0照片及证件照均45000拒绝；仅隔离事务内gate1照片和证件照合法通过；两类错document profile、错owner、wrong version/key/mode/currency失败关闭。每项隔离事务回滚，最终gate仍0。拒绝检查要求45000，不能把1267等运行错误冒充业务保护通过。另比对旧/新保护正文：仅剔除声明元数据、注释及显式CAST collation后完全一致。

一次补强的逐字读回断言暴露mysql CLI默认剥离BEGIN内说明注释；说明移到DDL外后正文逐字检查通过，查询与保护没有变化。定向bash语法和diff检查通过。证据 `/home/ubuntu/print-system/evidence/photo-6in-stage-c-collation-forward-20261104/`；未重建JAR、不把SQL测试宣称为生产恢复。

## 复审后才允许的维护操作

安全执行候选：`ops/db/apply-photo-6in-collation-reviewed-forward.sh`。该脚本本轮未执行。调用前必须具备准确修复提交/repairSQL SHA/审批的旧ACTION_STATEMENT文件SHA和绑定该增量的Astra source、migration、release三项PASS；缺一停止。旧body SHA应取自主任务既有Stage C读回/批准的只读预检证据，不能猜值，也不能让脚本自行接受任意旧trigger。

脚本只使用 `--no-defaults --protocol=socket -uroot` 的本机管理入口，不读取配置/凭据文件。流程为：先停nginx/backend，确认MainPID0、stop非timeout、cgroup无子进程及DB无活动语句/在途事务；确认scope gate0、同名trigger和immutable trigger存在；umask077/私有目录备份SHOW CREATE、ACTION_STATEMENT及一致性DB备份，gzip/hash验证并匹配批准旧body hash；执行forward SQL；核charset/collation、SHOW CREATE和ACTION_STATEMENT与已审源正文逐字一致及三锁，重新读回gate0。

DROP后任何CREATE或读回失败、脚本中断或成功都保持两个写入口停止；不自动恢复旧错误trigger、不做逆迁移、不启gate1、不重启兼容JAR、不继续Stage D。后续必须由主任务获授权后，以双false+DB0的同一兼容JAR重新健康/普通链路验证，再按原发布阶段审核放行。任何保护缺失均不得恢复服务。正式SQL/服务执行、生产回归、后续开放全部NOT_RUN，初审CHANGES_REQUIRED在精确增量复审前不得标PASS。
