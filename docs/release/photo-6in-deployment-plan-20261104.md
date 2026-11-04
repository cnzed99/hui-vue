# 六寸照片纸分阶段发布候选方案

状态：`STAGE_C_BLOCKED_COLLATION / FORWARD_REPAIR_PREPARED_NOT_EXECUTED / ASTRA_HIGH_INCREMENT_REVIEW_REQUIRED`。本文件和候选配置不授权执行。实施配置 GPT-6.1 Sol High，运行 effort 未独立核验。源码、迁移与发布三项均须取得绑定准确版本的 Astra High PASS；没有自动执行器，每阶段验收后才允许下一阶段。

## 准确版本与现行基线

兼容源码：`3f4019b62f4a3a2384ad83946a4643eaab881966`。本批只增发布材料，不改JAR源码。主任务全量构建PASS，已读取构建摘要，原件在 `/home/ubuntu/print-system/evidence/builds/photo-6in-20261104/`。

| 候选产物 | SHA-256 |
|---|---|
| 后端 `photo-6in-backend-20261104/yudao-server/target/yudao-server.jar` | `101a55e53b06f08d4f5521ab0efe11547ad8cce0b30b99e1795e91935a4b3208` |
| admin提交f7cdd684，`photo-6in-admin-20261104/dist/index.html` | `0b4bea9c2702b5766dd2b2be3e01df48d0d03405b74bb2cbd67c74603d03a9d7` |
| miniapp提交752da383，`photo-6in-miniapp-20261104/dist/build/h5/index.html` | `0c3b4f0786445261f4d09d10da44d7dd5dfc05ece307d6d5e63fdbfa41486d03` |
| 同miniapp，`dist/build/mp-weixin/app.json` | `1699814148be289421dc1827a6d292538860fa05050ecdc681271e2cd2b7b139` |

以上工作树均位于 `/home/ubuntu/print-system/runtime/worktrees/`。入口hash不代替全静态目录清单；执行前须为已经构建的目录生成排序逐文件SHA清单并验证复制目标，不得重建后沿用旧hash。

已完整读取启用Nginx、同类locations及全部两个include、限流配置、systemd单元/drop-ins和start脚本；没有读取runtime.env/manual-demo.env或密钥内容。

| 当前文件 | SHA-256 |
|---|---|
| `/etc/nginx/sites-enabled/print-system`（真正生效） | `57c9e35f5c2a04e78ece2932139116863625e8d89b4d3209780d138f6dbdb246` |
| `/etc/nginx/sites-available/print-system`（不是同一文件） | `e728449cb2596ab88ec8951f52a4bee6ae315de0497c3585d14e521c2a1292e7` |
| `/etc/nginx/print-id-photo/locations.conf` | `925a5d20fe7700ace0356a7eab430ebd6c8acbd36f7c8e7c927b8f0cb69d9ea1` |
| `/etc/nginx/print-zip-candidate/locations.conf` | `54259e13a8dd9d78886847eb3860a99a13f8f6f7a2db745780c54d2be57ef11a` |
| `/etc/nginx/conf.d/print-miniapp-limits.conf` | `860ec67fc62ad972f4c467d1b82d4ade50c8246267d0b17a294ffb707bbd03b9` |
| `/etc/nginx/conf.d/print-zip-candidate-zones.conf` | `f800a84d7480f7b71f44d95a38b084ac6b6304906c828a30fc8e812396c0ae0d` |
| `/etc/nginx/nginx.conf` | `4869cddce1030ceffb99bf88b1a64a0ec2b5d51c0c2377958f48533bae320522` |
| `/etc/systemd/system/print-backend.service` | `90ed0bd4a55ebeab43905948e32af4bacc78440fb5347de0cb4029f6cad7955e` |
| `/home/ubuntu/print-system/ops/runtime/start-backend.sh` | `acb08224974d383bc41544b0e196d1f25a8988a999d41709f5838f2ac6a56bcc` |

基线漂移即中止重审，不能用sites-available覆盖sites-enabled，避免丢失zip/id-photo include。现行回环18080→48080，不顺带改监听、公网、TLS或微信接入。

## 配置候选和安装位置

1. `ops/runtime/print-photo-6in.service.conf` → 候选 `/etc/systemd/system/print-backend.service.d/80-photo-6in.conf`；只含PHOTO_ENABLED=false、PHOTO_QUOTE_ENABLED=false。后续true阶段的准确字节和SHA也已预计算在release-hashes JSON的stageDropins中，切换副本必须匹配该hash，不能手改已绑定的初始文件。
2. `ops/runtime/print-photo-6in-limits.conf` → 候选 `/etc/nginx/conf.d/print-photo-6in-zones.conf`，http作用域只一次。
3. `ops/runtime/print-photo-6in-nginx.conf` → 候选 `/etc/nginx/print-photo-6in/locations.conf`；在准确启用server中仅增加 `include /etc/nginx/print-photo-6in/locations.conf;`，保留原两个include和全部既有locations。临时/备份文件不得进入sites-enabled/*或conf.d/*.conf通配范围。

只开放create POST、会话UUID GET、parts 0–29 POST、complete POST；其余namespace路径404，generic `/app-api/`继续404，GET不开放HEAD。每段用raw `$request_uri == $uri` 拒绝编码、归一化和查询路径；拒绝TE/Content-Encoding并校验Authorization格式，真实身份仍由后端核验。Create限JSON/稳定key/正CL/2KiB，parts限multipart/正CL/3MiB，GET和complete只允许空body。原样传递Authorization、Origin、Referer、Idempotency-Key。

parts使用HTTP/1.1和request_buffering off，避免应用身份准入前将原图spool至Nginx。执行者须在批准窗口创建 `/var/lib/nginx/print-photo-6in-body` 为www-data:www-data、0700；应用photo-multipart/photo-upload-objects由持OS lock的JAR创建为ubuntu、0700。新zone全局2/单IP1并发、120r/m+burst35，支持30次顺序parts；应用单许可和>=10GiB门禁独立保留。UUID路由不写access_log，证据不保留Token/原图。

## 分阶段执行顺序（历史执行以主任务原件为准；当前Stage C阻断）

### A. 预检/备份

核对准确发布材料commit、三项Astra PASS、JAR/静态/config hash、干净分支和tenant1/store1。重查菜单2010/2011无冲突、迁移preflight仍满足，保持>=10GiB且不与重型构建并行。umask077下在runtime/backups/本次唯一目录备份数据库、启用Nginx及全部include/zone、systemd/drop-ins、旧JAR真实路径和hash、admin旧index、H5旧symlink目标。gzip校验及SHA读回，不将备份入Git/输出/复制到本地，不删旧数据或旧产物。

先停Nginx写入口并等待已入站请求排空，再停后端；现行TimeoutStopSec=30s、KillMode=control-group，停止后确认MainPID0、cgroup无子进程、stop结果非timeout，再切JAR。仍有子JVM/OS lock或超时停机时保持停写处理，不启动第二个worker。

### B. 兼容JAR先启动：应用双false，尚未迁移

把核SHA的JAR保全至唯一不可变release目录，按现场读回的准确current/backend.jar引用方式原子切换，不替换包含其他文件的未知目录。安装双false候选，daemon-reload后启动，等待 `/actuator/health` 实际UP（通常约25秒），不是仅看start返回。运行PDF parser的runtimeJar引用必须解析到同一兼容JAR并核hash。历史v1文档报价/订单/模拟支付/取消和旧任务证据恢复必须通过，照片Nginx路由仍不安装。

start脚本会source私有runtime.env，因此不能只凭drop-in认定有效flag，也不得读取秘密文件来判断。用回环parts无身份请求应返回功能关闭404而非401，验证PHOTO_ENABLED=false；用当前主体已READY合法ID_PHOTO文件创建PHOTO_6IN报价，应为1928001/DISABLED，不能是缺binding/storage错误，验证PHOTO_QUOTE_ENABLED=false。无可用文件时只在兼容JAR已保全且健康后用既有证件照入口生成一份。关闭行为或历史兼容不符则停止，不迁移，配置优先级修复须另审。

### C. 迁移gate0：当前阻断，不得继续D–G

主任务报告旧SQL SHA `28889a04225006e6a5186f1e63a1e96996b3e521577b1d01ab355d69448df8dd` 已执行，collation回归阻断；此历史hash及e0816daf提交记录不变。本增量不能冒充该旧SQL未执行，也不能将新hash反写旧执行记录。当前文件已修正为新库首次安装候选，SHA `a5bba439bcb6d2c48aa0622ec0f33baca137a0c38480d2ac5a6574f801de68a3`。现库禁止重跑首次安装DDL，应只使用 `print-miniapp-demo-photo-6in-trigger-collation-forward.sql`（SHA `327b6cb5c1073477886d047093d1ec0c8700ca6a5db75801af45d742eaa9380e`）及另附Stage C修复计划；仅重建同名quote trigger，保留gate0和全部新保护。初审CHANGES_REQUIRED，精确增量复审通过前不执行，D–G停止。

以下保留原首次安装流程和**已执行旧版hash**供核对，不作为现库重跑指令：

再次停Nginx/后端并排空、验证备份。SQL `ops/db/print-miniapp-demo-photo-6in.sql` SHA `28889a04225006e6a5186f1e63a1e96996b3e521577b1d01ab355d69448df8dd`；现有迁移脚本SHA `f2eca0e0e64ba02ff46decfa361af802b3358ec71134263f4d4dd3a39080f151`。其局部两个PASS环境门禁不替代本计划release PASS；执行者还须先核release审查。该脚本成功/失败均保持写入口停止，不自动部署。

各DDL提交点立即读回：source/pages CHECK、file→binding→profile三个当前FOR UPDATE trigger、immutable trigger、scope FK、初始独立UUID/revision1/price1.00/quote_enabled0、角色权限。旧trigger删除后任何CREATE/读回失败均保持停写，不做逆DDL、不在保护缺失时恢复。完成后只恢复兼容JAR+双false并等健康；回归旧v1和普通报价，DB gate0和direct SQL拒价均核对。

### D. 文件true/报价false/DB0

切已核SHA的文件true副本，兼容JAR健康后才安装精确Nginx路由和zone。组合配置先在include通配范围外nginx -t，核准确hash，再原子换文件/reload；失败须还原完整旧include链、移除本次准确临时文件并nginx -t，不能漏`.next`。报价应用false且DB0，不先公开新UI。

### E. HTTP上传验证

受控演示主体create→顺序parts→complete→原uploadId GET恢复，随后用既有 `GET /app-api/print/miniapp/demo/file?id=<fileId>` 等READY（不是/files/{id}）。抽查普通规格/30页、拼版9图/1页、证件照1页及同域恢复相同fileId。编码`%70hoto-upload-sessions`、ordinal30、无身份、错误method/TE/超限须拒绝，无临时原图泄漏。应用和direct SQL均拒照片报价，DB仍0；管理员scope和reader PUT403/无scope拒绝也验收。结果未知保留原uploadId查询，禁止换域盲重试。

从首条PHOTO来源行开始，或首条v2报价（即使只有ID_PHOTO v2），旧verifier/JAR永久退出允许回退列表。

### F. DB1后报价true，完整模拟闭环

前述验收及开闸配置hash均获审后，在短窗口锁binding，核审批读回的scope/profile UUID/revision/初始价1.00；以UUID+revision+gate0为条件更新gate1，必须影响1行并读回。然后才切已核SHA的报价true副本、重启兼容JAR、等健康并恢复受控写入，不反序、不静默越过失败。

HTTP和候选界面私有预览完成照片/证件照→v2报价→订单→模拟支付→唯一任务→CLAIM/ACCEPT/START→唯一模拟回执→结果恢复；张数N×copies/两位金额/改价后原键冻结与新键新价核对。管理员改价只INSERT新profile/CAS指针，验证后以新版本恢复1.00，不UPDATE旧profile。付款/推进动作要求当前READY/未过期/对象及hash/source一致。既有未知任务只恢复同jobId/fence证据，不重建、不自动退款、不重发。任何金额、权限或唯一任务失败立即关闸，不进入公开静态发布。

### G. 最后admin/miniapp静态发布

全闭环后按逐文件SHA复制原已构建dist到唯一不可变release，核入口hash。admin当前 `/var/www/print-system` 是真实目录，含miniapp/releases，禁止整目录替换或rsync --delete：先追加并校验内容hash assets，保留旧assets；变更的非hash同名公共文件需逐文件备份/审核清单，不盲覆盖；最后同文件系统临时index原子rename。H5 `/var/www/print-system/miniapp` 是symlink，当前目标 `/var/www/print-system/releases/miniapp-id-photo-server-file-20261103-69d93c1`，用同目录临时symlink再原子rename切换。失败恢复旧index/准确备份文件和旧H5目标，清理仅本次准确临时路径。

浏览器核实际加载index/hash，验证价格卡、照片/证件照入口及旧页面、订单恢复。MP只保全构建包/app.json hash，不上传微信平台、不切AppID、不宣称真机/正式微信发布；原系统不访问。

## 回退与失败安全

先关精确照片Nginx入口，再关两个应用flag，再将scope绑定DB gate0并读回；不能完成关flag/健康时保持入口关闭并停后端，gate0未读回不恢复写入。排空、确认无子JVM/OS lock后，只可恢复已保全兼容JAR和旧静态入口。v2历史订单仍可查询/取消/模拟退款，已接受/执行/RESULT_UNKNOWN只恢复原证据。

首条PHOTO或v2后禁止旧JAR；保留新CHECK/trigger/binding/不可变版本/权限/数据，不删表列或来源行，不还原document-only trigger，不全库回滚覆盖已创建订单/支付/回执。没有兼容JAR或保护缺失时保持停写；UI回退不授权重复计费、第二任务或重复打印。

旧版材料准备阶段的隔离Nginx语法检查PASS仍是原证据；主任务已报告Stage C执行旧迁移后阻断。本Worker本轮只准备forward修复及隔离验证，未访问生产DB/服务；修复生产执行和后续D–G全部待复审，不把历史执行状态重写为NOT_RUN。隔离检查使用准确enabled基线及旧includes，仅将临时目录改至本批target；未加载/重启现网。非root探针默认error-log权限告警不影响nginx -t返回0；配置syntax/test均successful。本机不支持-e选项的探针尝试未更改现网。所有候选准确hash见同目录release-hashes JSON，随发布资料commit交审。
