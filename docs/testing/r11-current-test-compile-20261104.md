# R11 当前后端测试编译结果（2026-11-04）

## 结论

- 状态：`PASS_TEST_COMPILE`
- 基线分支：`print/phase1-backend-id-photo-preview`
- 修复：`yudao-server` 增加 test scope 的 `mockito-core`，并排除其 Objenesis；不改变运行时依赖。
- 修复后 POM SHA-256：`2c82d5108760065ddd871e45e86b72db6123a38de0189b3ca7f8a5800f2ebbfc`。
- 该 POM 与此前 Astra High 设计审查及 Sol High 最终审查通过的精确 POM 完全一致。

## 当前分支验证

- 标准低内存构建窗口执行：`mvn -o -T1 -B -ntp -pl yudao-server -am test-compile`。
- Maven：`BUILD SUCCESS`，`yudao-server` 编译 25 个测试源。
- `target/test-classes`：71 个 class。
- 原阻断项 `PrintStoreProfileChecks.class`：存在。
- 当前目录能力检查：`PASS public catalog, two direct PDF sources plus ZIP-contained PDF, unchanged limits`。
- 四项服务：逐项 active；后端 HTTP 健康恢复成功；构建 scratch 已清理。
- 日志 SHA-256：`8a858b8a2529e5fa4cd5c84650eec5094af0279d56f6cd051860c3160963faeb`。
- 安全构建记录 SHA-256：`376d8d6c72f4364002e498992f81e0f2a5c7dbe364b14b9abb8dc123da83af0f`。

## 证据边界

- 本结果证明当前后端测试源码可编译，不等于所有测试 main、JUnit、SQL/Redis/HTTP 集成、微信真机或用户验收全部通过。
- test scope 依赖不要求重新部署当前运行 JAR；正式业务运行版本保持不变。
- 实施按项目 GPT-6.1 Sol High 配置执行，主会话实际底层 model/effort 未独立核验。
