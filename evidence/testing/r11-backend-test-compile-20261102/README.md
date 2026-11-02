# R11 后端测试编译证据归档（2026-11-03）

本目录保存云端 `evidence/testing/r11-backend-test-compile-20261102` 的29个原始文件，总计328607字节；所有文件逐字节复制，未重写历史日志。`SHA256SUMS` 只列29个原件，不包含清单自身。源记录采样时的状态保留，例如 `summary.json` 中的 `push: NOT_RUN` 是当时快照，不表示当前远端状态。

最终后端提交为 `f9d6d912993643b69462e5c091aecbabf02bb2f2`，基线为 `7efd316fb154c65fff089da7a5919284fb1f8cd6`。修复仅增加 Mockito test 依赖并排除其 Objenesis 传递依赖。最终测试源码编译和重新打包通过，245个外部运行库名称及摘要与稳定基线一致，现有 `PrintStoreProfileChecks` 内存检查65项通过。全量打印 main 检查、JUnit、SQL/Redis及HTTP验收仍未执行；本批没有部署。

证据入口：

- [最终摘要](summary.json)
- [第三轮运行库逐项比较](precise-repackage-20261103/runtime-lib-comparison.json)
- [第三轮 Mockito 内存检查结果](precise-repackage-20261103/mockito-check-result.json)
- [第三轮构建窗口恢复](precise-repackage-20261103/safe-build-window.json)
- [Astra 方案与 Sol 最终审查归档](../../../docs/reviews/r11-backend-test-compile-review-archive-20261103.json)

第一轮加入 Mockito 后发生 Objenesis 3.4→3.3 仲裁差异；第二轮排除后增量打包仍复用旧归档。第三轮先获得 Astra High 方案批准，在隔离工作树内仅删除两个目标 JAR 后重新构建，确认新 `Building jar` 行、产物时间及依赖一致性。所有原始日志均保留。两次 `dependency:tree` 离线失败未作为通过证据，也没有进行第三次重试。

归档前扫描全部文本，未发现私钥、凭据、token、真实用户联系方式或业务资料；最大原件77505字节。原始日志包含开发环境绝对路径和构建时间。脚本为历史复现原件，引用当时云端工作树及Maven缓存；Git clone本目录不能单独复现构建。归档未重跑测试，也不将当时服务恢复快照作为当前持续健康监控。

实施模型 `gpt-6.1-sol`；effort继承主协调上下文，未另取运行遥测。本次归档为文档工作，尚待主协调独立收口。
