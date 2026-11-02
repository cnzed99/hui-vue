# M9 部署整改 R2 独立审查

**结论：CHANGES_REQUIRED。** 本轮修复解决了主要部署问题，但仍需修正两项增量后才能执行部署。既有业务实现 R4 PASS 保留。

- 实际模型：GPT-6 Astra High / `astra_reviewer`
- 基线：`a239164df6e34df19359e53b99f738cee94245f3`
- 整改：`4907c12f6b94a924969c00f490b4e4cb71ab8321`
- 范围：部署及验收增量；只读审查，未部署、未写业务数据、未读取凭据、未操作原站。

## 剩余阻断

1. `ops/acceptance/m9_pre_enable_regression.py` 在 PDF 上传后立即上传单图，尚未等待 PDF READY；两者共享进程槽，正常后端也可能返回 429。PDF、单图及 disabled 探针还需对明确的 429 做有界、同请求体、同幂等键重试。
2. `ops/runtime/deploy-m9-image-collage.sh` 的 `sha256sum -c` 仅检查清单内资源，尚未拒绝额外文件或符号链接。部署前后必须核对实际文件集合与 manifest 完全一致，部署后仅可额外存在清单自身。

## 已关闭项目

- Nginx 不再从 include 目录加载 `.next`，开始与回滚均清理固定遗留路径。
- 已形成 M9 disabled -> 既有 PDF/单图回归 -> M9 enabled 的阶段顺序。
- runtime helper 路径有效；runtime 429 四次重试保持请求体和幂等键不变。
- 浏览器 PNG 与 pre-enable PNG/PDF 离线结构校验通过。
- 当前脚本所用 M9 专用 H5 候选目录有 73 个文件，和 manifest 一一对应且全部哈希匹配；manifest 不包含自身。

## 结论边界

当前结论不授权执行部署。修复上述两项后仅需复审相关增量。后续 PASS 也只可授权开发环境部署及 HTTP/browser 验收，不等于生产上线、微信真机、真实支付/打印或用户验收。
