# R11 管理端第三轮受限类型检查（2026-11-03 CST）

当前结论：`RESOURCE_LIMIT`（dom7修复后最终有限试验，见末节）。以下保留最初 `INSUFFICIENT_EVIDENCE` 及后续依赖修复记录。完整单进程 `vue-tsc` 仍未通过，不据此关闭 R11。

设计来源：主协调转交的 Astra High `PASS_TO_IMPLEMENT`。触发为相同完整检查两轮 V8 堆 OOM（2500/2048MB）。该批准仅允许受限方案，不是检查通过。实施模型 `gpt-6.1-sol`，effort 继承且未另取遥测。

本批以管理端 `6575759a145cce02f0aa8dc46952a6add2bfa192` 为基线。原 strict、compilerOptions、vueCompilerOptions 及真实依赖保留。新增真实 Vite serve 插件声明生成入口、支持 Vue 根的分片规划器和资源受限执行器。默认 ts:check 堆上限由8192MB调整为2048MB；该命令仍是原完整检查，并未声明在当前机器可通过。

## 本次实际执行

- `fixture-type-error`：真实 vue-tsc 返回2，检出 TS2322。
- `fixture-global-conflict`：真实 vue-tsc 返回2，检出 TS2717；两份源文件的 declare global 冲突未被隔离隐藏。
- `generate-declarations`：真实项目 Vite serve 插件逐源转换，19.95秒，最高 RSS 675220KiB，退出1。未发生OOM或超时。
- 失败位置：`src/components/bpmnProcessDesigner/src/modules/rules/CustomRules.js:2`，`import inherits from 'inherits'` 无法解析。原始 Vite import-analysis 错误保存在 `generate-declarations.log`。
- 自动声明已产生部分内容，但不满足全源转换完成条件，标为 `PARTIAL_NOT_VALIDATED`，单独保留后从工作树清理，不作为完整声明输入。
- 依赖图、共享声明分类、覆盖清单和代表片：`NOT_RUN`。规划器仅经过 Node 语法检查，未得到真实全项目验证。

执行在标准构建窗口内，systemd scope 约束 MemoryMax=3GiB、MemorySwapMax=256MiB，Node堆2048MB，每步独立进程组、180秒超时，`/usr/bin/time -v` 记录资源，禁用core dump。窗口本身正常结束（buildExitCode=0仅指实验执行器正常返回），试验结果仍为 `DECLARATION_GENERATION_FAILED`。已自动恢复四服务，另行实测 HTTP200/业务code401；scratch已清理，可用空间约39GiB。

## 保留门槛

需要先恢复项目本身的依赖完整性或查明该上游组件的依赖契约，再用真实插件生成完整自动声明。不得跳过此文件、手写any、换stub或把当前部分声明当完整结果。原始证据保持不变；后续尝试应使用新证据目录。

规划器采用 TypeScript extraFileExtensions 解析 `.vue` 根；真实 vue-tsc `--listFilesOnly` 提供导入依赖图；所有全局脚本、ambient/module augmentation和无法可靠分类的文件进入共享集。普通根需恰好分配一次，并集必须等于原根。即使后续分片全部通过，仍只能声明“全根文件覆盖分片检查”，不能直接改写成原完整程序等价通过。

本次没有部署管理端或修改业务模块。剩余工作需由主协调安排，当前提交仅为可审查的诊断工具和真实失败证据。

## 同批依赖修复后的有限复验

主协调授权核对并仅修复首项直接依赖后继续。`CustomRules.js` 确实直接导入 `inherits`，package.json未声明；现有 pnpm-lock.yaml 包记录、缓存与实际包均为2.0.4。因此在生产 dependencies 精确加入 `inherits: 2.0.4`，锁文件只增加根 importer 三行，不改任何已有包版本。`pnpm install --lockfile-only --offline --frozen-lockfile --ignore-scripts` 校验通过。开发检查使用隔离 node_modules 的缓存链接，不改主目录依赖树。

管理端候选 `f692e79e2879ab8162debe483bf848b55fb80925` 另将Vite缓存目录放入构建scratch；工程执行入口改为要求准确候选提交和新的证据目录，防止旧HEAD和旧记录覆盖。资源、严格度与停止条件继续遵守已审方案。

新证据目录为 `evidence/testing/r11-admin-typecheck-inherits-20261103/`。两项故意失败fixture继续检出TS2322/TS2717。真实声明生成25.782秒，峰值RSS1045476KiB，退出1，无OOM；新的准确阻断为 `src/views/bpm/model/form/PrintTemplate/module/utils/dom.ts:1` 的 `import $ from 'dom7'` 无法解析。按主协调“若再缺依赖则停止”要求，本批不继续补包，不跳过源码。

本轮结论继续为 `INSUFFICIENT_EVIDENCE`；依赖图、代表片、普通片及原完整检查均未执行。部分生成声明标为 `PARTIAL_NOT_VALIDATED` 后移出工作树。服务恢复HTTP200/业务code401，四服务active，scratch清理完成。原始第一轮证据保留。

默认 `ts:check` 的8192MB降为2048MB是当前3.6GiB物理内存主机的安全上限纠偏，避免该普通入口主动允许8GiB V8堆；它不改变TypeScript严格度，也不证明2048MB足以完成全量检查。完整检查仍有两次历史OOM，本批受限试验结果仍有独立门槛。

## dom7 官方类型修复与最终有限试验

主协调转交 Astra High `PASS_TO_IMPLEMENT`，绑定管理端 `f692e79e2879ab8162debe483bf848b55fb80925`。按方案精确增加生产依赖 `dom7: 4.0.6`，锁文件仅增加根 importer 条目，复用现有锁定包与缓存，离线冻结锁校验通过。删除 `types/dom7.d.ts` 的整包any占位，真实官方 `dom7.d.ts` 生效；未改 `dom.ts:8`，因为本批没有真实诊断要求该类型导出变更。

管理端候选为 `b429f6d7bd367144ad1ec460507ad40d0f5087fa`。新增窄门禁以原 compilerOptions 解析 dom7，验证实际路径等于官方声明，并在隔离fixture中确认给 `Dom7Array.length` 赋字符串准确产生TS2322。没有添加stub、断言或弱化类型。

最终证据目录 `evidence/testing/r11-admin-typecheck-dom7-20261103/`：

- 依赖修复与官方类型门禁：PASS；另两项TS2322/TS2717执行器fixture继续检出预期错误。
- 真实声明生成：PASS，2911源文件，62.145秒，峰值RSS1707032KiB。冻结摘要为 auto-imports `d887f4e8ba8e138e88be8d506867557041b06215a5b4f0c6564f729bf08824a7`、auto-components `e2a34603ce5c6a08b789507f6de86ef53e3130fd43a3154e206315bc376de800`。
- 真实 `vue-tsc --listFilesOnly` 依赖图枚举：RESOURCE_LIMIT，39.823秒、峰值RSS2237156KiB、V8在2048MB堆OOM，退出134。原日志与time记录均保留；GNU time末尾的Exit status字段不能覆盖进程退出134与OOM诊断。
- 根/共享覆盖规划、代表片、普通片：NOT_RUN。没有足够完整的图继续分片；没有将未执行写为PASS。
- 原单进程全量类型检查：NOT_RUN，本批未关闭R11。

资源上限、标准构建窗口及停止条件保持原方案。OOM后立即停止，没有增加heap/swap，没有继续补xyflow或element-ui。四服务恢复active，另行实测HTTP200/业务code401；scratch及隔离依赖链接清理，完整生成声明已冻结于证据并从工作树移除。没有部署或推送。

后续需要更大云端构建资源，或另行审查能够可靠构建完整Vue依赖及共享语义的方案。当前证据不支持在4GB主机上继续无上限重试，也不证明某个更大规格一定足够。
