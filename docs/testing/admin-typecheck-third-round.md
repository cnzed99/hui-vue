# R11 管理端第三轮受限类型检查（2026-11-03 CST）

结果：`INSUFFICIENT_EVIDENCE`。执行器已完成有限试验；真实声明生成阶段失败，依赖图、共享集和代表片均未执行。完整单进程 `vue-tsc` 仍未通过，不据此关闭 R11。

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
