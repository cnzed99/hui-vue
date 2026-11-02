# M8 候选构建记录（2026-11-02）

状态：`PASS_CANDIDATE_ONLY`；未迁移运行库、未部署 M8、取消 POST 未开启。

## 精确源码

- 后端初稿：`57221d2d853b276cdd8fbbfc736a0281bde3f8dc`
- 后端 SQL 关联修正：`cf6cdf0eda42cba969e0221ed4cd564cade7ffc3`
- 管理后台：`6575759a145cce02f0aa8dc46952a6add2bfa192`
- 小程序：`6de36ca866758b31a1f08c4dc7f496b6e8b37790`
- 工程/SQL：`03aabad23fc817f50a9baf87e45e631c9382884a`

## 通过项

- 后端 `mvn -pl yudao-server -am -Dmaven.test.skip=true package`：`BUILD SUCCESS`。
- 管理后台完整 Vite cloud 构建：`built in 1m 33s`。
- 小程序最终 HEAD `vue-tsc --noEmit`：退出码 0。
- 小程序 H5 构建：`DONE Build complete`。
- 标准窗口每次均恢复 MySQL、Redis、Nginx、print-backend，后端 HTTP 健康；本批 scratch 已删除。
- 系统盘剩余约 40GiB，高于 10GiB 门禁。

产物 SHA-256：

```text
629b0e151677f0dfa920ebae5fdbf5eff2a16384d442a4992bf1481e52edf512  yudao-server.jar
33acc866e5674acb0bee7639280addc05cbd4e264f5fe752aa89b2a002411095  admin dist/index.html
f01dc9aef2957d3d0e32f2d8aabc70a8ac03772976438876f20f77cc5f86343d  miniapp dist/build/h5/index.html
```

## 失败与处理

1. 首轮使用 `-DskipTests` 时，既有 `PrintStoreProfileChecks` 测试辅助类因未声明 Mockito 导致 testCompile 失败。M8 正式源码编译已通过；改用 `-Dmaven.test.skip=true`，不让与本批无关的旧测试编译阻塞。
2. 管理端整仓 `vue-tsc` 在 2GiB V8 堆达到 heap limit，内核没有 OOM。M8 变更已通过隔离严格 TS、两个 SFC 编译及 14/14 契约检查；保留完整 Vite 构建，取消低价值整仓类型扫描。
3. 三项实际构建通过后，证据脚本误用 `dist-prod/index.html`，实际 cloud 输出为 `dist/index.html`。只修正证据路径并补产物哈希，没有重跑或伪造构建结果。

## 边界

- 构建通过不等于运行库迁移、API 联调、并发/幂等或用户验收通过。
- 下一门禁是 Astra High 对最终源码、SQL、账本/并发、权限和恢复方案的独立审查。

