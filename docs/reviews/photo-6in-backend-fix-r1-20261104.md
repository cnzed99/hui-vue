# 六寸照片纸后端 R1 修复与集中验证

基线：`a57012dfd31132bf2b809c4bf49a1b2263a57ef6`。状态：`FIX_IMPLEMENTED / TARGETED_CHECKS_PASS / ASTRA_HIGH_REREVIEW_REQUIRED / NOT_DEPLOYED`。

按配置实施模型为 GPT-6.1 Sol High；本 Agent 运行 effort 未独立核验。原最终审查为 CHANGES_REQUIRED，本记录不将设计 PASS 或本轮测试替代最终复审。

本轮关闭的四处实现问题：

1. 价格 controller 移除 final，允许 Spring 方法安全 CGLIB 代理。局部处理 AccessDenied 为 HTTP403，PrintScope 缺失/不合法为 HTTP401/403，保持 scope 原业务码；不会被通用 CommonResult HTTP200 混淆。实际 Spring Boot/Tomcat 测试断言候选 controller 是 CGLIB proxy。
2. 新增 `MiniappDemoPhotoAdmissionFilter`，在 DispatcherServlet multipart 解析之前覆盖照片 parts 及该命名空间内不合法 multipart 请求。先检查功能开关、严格长度/编码/方法/路由、身份、全局单许可、至少10GiB加输入峰值磁盘余量，之后才读取 body。私有 `photo-multipart/task-*` 由持有 OS storage lock 的 FileService 创建和清理；自有单文件 parser 限定2MiB JPEG/PNG、单个 file、有限边界/头。controller 只消费已准入 identity/file，移除 request.getParts，容器不再二次解析。
3. 上传服务在 PHOTO_ENABLED=false 时仍取得 storage lock、初始化 photo-upload-objects、立即执行并定时重试清理。create/get/upload/complete 和清理均持活动操作计数；停机先关准入、停止 cleaner 并排空，不能排空则保留 OS storage lock 至进程退出。清理错误输出有界事件及 pending count，逐项保留重试；临时文件失败同样纳入重试。
4. renderer 的共享子进程许可改为显式 finally 释放，位置在子 JVM 确认退出和 scratch 删除/转交 pending 之后；中断标记在终止与清理后恢复。

集中验证原件：`/home/ubuntu/print-system/evidence/photo-6in-backend-fix-r1-20261104/`。

- 全部 print 包源码定向 javac PASS；不宣称完整 Maven/JAR 构建已运行。
- `MiniappDemoPhotoLifecycleChecks` 63项 PASS：关闭/身份错误/低盘/过大/未知长度/编码/非法路由不读 body且无临时文件；并发占满不读 body且不新增文件；解析成功仅有专用私有 task/image、chain退出即清理；disabled 初始化清理、删除失败 pending可见并重试恢复；活动上传正常停机等待、超时保锁、停止后拒新上传；真实独立子JVM超时及中断均确认 child退出，scratch处理被阻塞时第二个renderer不能获得共享许可，最终清理完成后许可才可用。
- 原独立 MySQL SQL/报价/CAS/上传恢复/执行文件准入 51项回归 PASS。
- `MiniappDemoPhotoSpringChecks` 启动实际 Spring Boot 3.5.15 + Tomcat 10.1.55、真实候选价格 controller/service、真实 PrintScopeService、真实 SecurityFrameworkServiceImpl 和 @EnableMethodSecurity，连接独立 MySQL。10项 PASS：CGLIB proxy成立；admin GET/PUT；reader GET、PUT真实 HTTP403且未插入价格；无scope GET/PUT拒绝；匿名拒绝；CAS HTTP409。身份通过仅回环 fixture header 注入，权限从临时库迁移后的角色菜单授权解析；不宣称生产 JWT/登录或完整应用上下文已验收。
- git diff --check 和 bash -n PASS。本轮未执行生产迁移、部署或更改 systemd/nginx；仅启动、关闭测试自己的临时 mysqld、回环 Tomcat 和子JVM。

可复现：先用已有定向 classpath编译本轮源码与两个新 Checks，再执行 LifecycleChecks；`PHOTO_6IN_SPRING_CHECKS=1 ops/db/probe-photo-6in-isolated.sh` 运行临时库回归与实际 Spring HTTP。完整项目兼容 JAR构建/实际应用整体启动由主任务在串行安全窗口接管。最终精确 old..new增量仍须 Astra High复审。
