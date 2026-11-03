# 第一批原小程序界面候选验证

小程序提交 `4d0bba1d8e3baeb91d8e0059d1f1575f6c7ffc8b`。在云端标准构建窗口补录证据。首次执行的逐项输出仅保留在任务工具记录，首次构建产物已随 scratch 清理。

- `picker-mocks.log` 对应 `node scripts/check-original-batch1.cjs`。
- `typecheck.log` 对应 `vue-tsc --noEmit`。成功时工具不输出内容，外层命令以 `set -e` 顺序执行，最终退出码见 `safe-window.json`。
- `build-h5.log` 与 `build-mp.log` 分别记录 `uni build` 和 `uni build -p mp-weixin`。
- `artifact-conditions.log` 确认 H5 首页与微信证件照页面产物存在，微信首页含原生选择调用，H5 首页不含该调用。
- `artifact-sha256.txt` 保存窗口内检查过的产物摘要。窗口结束后产物已随 scratch 清理。
- `safe-window.log`、`safe-window.json` 与 `service-health.log` 记录自动恢复和后端健康检查。

微信开发者工具与真机验证待执行。
