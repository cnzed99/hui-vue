# 3寸照片本地选择 H5 烟测 Astra High 复审

- 日期：2026-11-03
- 候选：`1f8f7eda96612bf0db7c3a3848f6467c637bd9fc`
- 触发：同一浏览器烟测连续两轮失败，按 `AGENTS.md` 在第三轮修复前强制审查。
- 模型：固定 `astra_reviewer`，配置 `gpt-6-astra / high`；运行时遥测未另取。
- 最终结论：`PASS`

## 失败与修正

1. 首轮浏览器未启动：未向 Chromium 提供已缓存 sysroot 的 `LD_LIBRARY_PATH`。
2. 第二轮脚本无限等待 filechooser，且异常路径没有有界关闭浏览器，留下独立 browser PGID。已按精确 PID/PGID 终止并核对四项服务正常。
3. Astra High 初审要求：真实入口与直接注入分开；filechooser、导航、断言和关闭全部有界；验证预览真实可见并成功解码；使用独占 profile 精确清理浏览器；监听到 context 关闭后再汇总请求；实际输出残留进程数。
4. Sol 修正脚本并重跑，最终输出入口 `VERIFIED_FILECHOOSER`、选中1张、预览解码 `1x1`、非GET请求0、业务API请求0、profile残留进程0。

## 绑定脚本

- `ops/acceptance/photo_local_picker_browser.cjs` SHA-256：`061a30576142bcaacd3a1a219cbea4704cc0ab6a2f91d76a0858fa6dd8eb525f`
- `ops/acceptance/run_photo_local_picker_browser.sh` SHA-256：`d3d8fed18412c02efedb8d3346499cd47204059861bbb792ca87599c00785088`

## 边界

本结论仅覆盖H5开发环境单张本地选择与预览烟测；不覆盖微信开发者工具/真机、30张界面实测、照片纸排版、权威计价、服务端上传、订单、打印或正式上线。
