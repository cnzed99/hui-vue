# M9 图片拼版最终实现增量复审材料（R4）

日期：2026-11-02

- 后端保持 `5717463b8bd1598d0f3a066055bc928c3ddb4f9d`，R3 已由 Astra High 关闭锁生命周期 P2。
- Miniapp 从 `717d8fa25d6e0e064df9f8842dc0b863d5d1d027` 更新为 `91c4abf9898e6344933f212a02ef1965cceb2d23`。
- 唯一增量：选图器打开时冻结 generation、会话 token 和递增的 picker request；回调开始时逐项复核，任一不一致即丢弃。会话清理及页面卸载都会使旧 picker request 失效，`continuing` 期间禁止打开选择器。
- 原始证据目录：`/home/ubuntu/print-system/evidence/build/m9-image-collage-fix-r5`。类型检查、H5 构建、微信构建退出码均为 0；日志 SHA-256 分别为 `dc594bf50185cef5f9d824ea24be8ad002b68bdb2c4f395c00d0daf7cfde7fbb`、`96bdb1ce039937cf13f4af9e2f07e13bd83894dfd5fd5199bdc4a81d2ec7ec92`、`6eecae8e43d715b358b2f3dcd45777f8eea6d2fd2aed6ace8b25cd9b79095e78`。
- 标准构建窗口记录：`evidence/disk/m9-image-collage-fix-r5-latest.json`，SHA-256 `e97a730ab5aac7afd5485fda471307e5a1f93181c4416db868d60c070019f104`；build=0、recovery=0、scratchRemoved=true、backendHttpHealthy=true。
- 当前 M8 服务保持 active，M9 未部署。本轮只请求复核 R3 剩余的旧选择器回调 P2，不扩张为 HTTP、浏览器、微信真机、部署、生产或用户验收 PASS。
