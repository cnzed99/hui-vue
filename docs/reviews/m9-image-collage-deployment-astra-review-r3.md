# M9 部署整改 R3 独立复审

**结论：PASS。** 上轮两项阻断已关闭；仅授权执行开发环境部署及 HTTP/browser 验收，不代表这些验收已经通过。

- 实际模型：GPT-6 Astra High / `astra_reviewer`
- 基线：`4907c12f6b94a924969c00f490b4e4cb71ab8321`
- 审查提交：`e06ed245d01abd95bc8994de43daa31be78ad8e3`
- 工作树：`/home/ubuntu/print-system/runtime/worktrees/m9-image-collage-engineering-20261102`
- 审查范围：`m9_pre_enable_regression.py`、`deploy-m9-image-collage.sh` 的本轮增量及必要上下文。只读审查，未部署、未修改服务、未读取凭据、未写业务数据。

## 已关闭项目

1. 实际顺序为 `PDF 上传 -> PDF READY -> 单图上传 -> 单图 READY -> M9 disabled 验证`。PDF、单图和 disabled 探针均只对 429 做四次有界重试；同一次请求的 body 与 idempotency key 不变，非 429 不重试，耗尽时明确失败并触发部署回滚。
2. 部署前和复制后都由 `verify_miniapp_tree` 精确核对 manifest 与实际文件集合，并拒绝符号链接；内容继续由 `sha256sum -c` 校验。发布目录只允许额外存在 `SHA256SUMS`。候选目录的 73 项集合及完整 SHA256 均通过。

独立替身覆盖：429 后成功、非 429 不重试、429 耗尽、PDF/单图顺序、正常/额外/缺失/符号链接/发布清单集合门禁。Bash 语法通过。

## 结论边界

本 PASS 只授权按该提交执行开发环境部署，并继续获取关闭阶段回归、开启后 HTTP/browser、临时目录清理和资源余量证据。实际部署、HTTP/browser、微信真机、生产上线、真实支付/打印及用户验收仍为 NOT_RUN。
