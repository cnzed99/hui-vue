# M1 角色权限首轮诊断

首轮真实请求结果保存在 evidence/acceptance/m1-role-permissions.json：5 PASS、7 FAIL，不能发布为权限通过。

测试先静态核验了 /admin-api/system/user/page 对应 system:user:query。只读查询确认合成种子：一个租户，第二租户不存在；admin角色有query/list，reader无query；两启用用户、一停用用户。未读取/打印凭据或令牌。

实际故障：管理员登录HTTP500非JSON；reader成功登录后权限信息、受保护接口、退出均出现HTTP500非JSON；停用账号登录未返回预期JSON。通过safe_log查看运行日志，见NoClassDefFoundError/ClassNotFoundException（包括org/apache/catalina/util/RequestUtil）。这属于本轮运行产物/类加载故障，不能直接归因于角色授权逻辑。已通知主执行方检查运行中Boot JAR是否被同路径repackage覆盖并使用不可变release产物；本审查不自行改部署或重启。

该轮只创建reader会话；finally已尝试退出，退出HTTP500，但旧令牌再访问受保护接口得到401。这能证明旧令牌访问被拒绝，尚不能把失败的退出及审计流程视为完整通过。

另两项预期需纠正：无token时认证过滤器先拒绝，即使缺tenant或tenant=2也返回401。它只证明未认证拒绝，不能当缺tenant校验/不存在tenant校验通过。脚本已改：无token无tenant期望401并标明证据边界；不存在tenant改用真实允许匿名的login路径触发租户合法性检查，目标仍为只读核验不存在的tenant2，不能称存在的第二租户数据隔离通过。

首次失败报告保留，不覆盖。待主进程部署稳定产物并确认就绪后，用新的报告文件复测；未重复构建或触碰DB记录。
