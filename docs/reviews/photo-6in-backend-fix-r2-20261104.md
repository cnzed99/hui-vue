# 六寸照片纸 R2：容器路径准入修复

基线 `cc21f8421cc2b99eca4918f90bb0fac3ba3513a2`。实施配置 GPT-6.1 Sol High，运行 effort 未独立核验。状态 `FIX_IMPLEMENTED / TARGETED_PASS / ASTRA_HIGH_REREVIEW_REQUIRED / NOT_DEPLOYED`。

最小实现变更只在 PhotoAdmissionFilter：以容器已经解码的 servletPath + pathInfo 识别受保护 namespace；其内请求在读取 body 前要求原始 requestURI 精确等于 contextPath + 容器映射路径，非规范 URI 直接400。不自行调用 URL decode，也不循环解码。规范的非multipart非parts请求继续原链路，其它解析前门禁保持原逻辑。

实际测试 `MiniappDemoPhotoTomcatPathChecks` 启动真实 Spring Boot/Tomcat，DispatcherServlet 映射 `/app-api/*`，安装候选 Filter、候选上传 controller、自有 parser 和启用的默认 StandardServletMultipartResolver/MultipartConfig（默认临时目录独立、threshold=0），通过回环 HttpClient 发送真实 HTTP；无 Mock request。独立记录 body getInputStream、默认 resolver、Servlet getParts、controller 调用次数，检查私有 multipart、默认 multipart、原图目录。身份及上传持久化服务为 fixture，未接生产登录或数据库。

25项 PASS：

| 请求 | HTTP | body读取 | 默认resolver/getParts | controller | 请求结束后三目录文件 |
|---|---:|---:|---:|---:|---:|
| 标准 parts | 200 | 1 | 0/0 | 1 | 0/0/0 |
| `%70hoto-upload-sessions` | 400 | 0 | 0/0 | 0 | 0/0/0 |
| 功能关闭 | 404 | 0 | 0/0 | 0 | 0/0/0 |
| 无身份 | 401 | 0 | 0/0 | 0 | 0/0/0 |

编码请求的实际容器读回：rawURI 含 `/%70hoto-upload-sessions/`，servletPath 为 `/app-api`，pathInfo 为 `/print/miniapp/demo/photo-upload-sessions/<uuid>/parts/0`。因此容器确实解码并映射该请求，400来自候选 Filter 的规范性检查，而非依赖容器提前拒绝。标准请求在 controller 内观察到自有私有 task/image，响应结束后已清理。

已有生命周期63项回归 PASS；只给旧 Mock fixture 补 servletPath 以反映容器映射语义，未新增无关测试。定向 javac、git diff --check PASS。原件 `/home/ubuntu/print-system/evidence/photo-6in-backend-fix-r2-20261104/manifest.json`。

首次新候选 fixture 启动缺少 PropertySourcesPlaceholderConfigurer，补齐测试上下文标准占位符解析后通过；没有把测试搭建失败算作 HTTP 验收。未执行迁移、部署、systemd/nginx更改或完整项目构建。最终精确增量仍须 Astra High复审。
