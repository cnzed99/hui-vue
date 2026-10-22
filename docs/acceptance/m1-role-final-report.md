# M1 真实角色权限验收（限定范围）

不可变 release 重启且实际认证路由就绪后，复测 18 项检查全部通过。证据为 evidence/acceptance/m1-role-permissions-release-v2.json；其中记录源码指纹、执行时间、只读种子观察、逐例预期/实际业务码，以及测试后观察的运行 release 路径与 SHA256。未输出凭据、令牌或用户列表内容。

服务就绪依据为真实 /admin-api/system/auth/get-permission-info 返回预期业务码401，以及随后成功登录；没有把 systemd active 或 /actuator/health 的HTTP200当就绪证明。

## 实际差异结果

| 场景 | 实际结果 | 能证明什么 |
|---|---|---|
| 无token请求受保护用户列表 | HTTP200，业务401 | 未认证拒绝 |
| 无token且无tenant头 | 业务401 | 认证过滤器先拒绝；不证明缺tenant校验 |
| admin登录及权限信息 | 业务0，存在query权限、无全权限通配 | 合成非super_admin管理员获得显式权限 |
| reader登录及权限信息 | 业务0，无query权限、无全权限通配 | 合成受限账号可登录但未授予查询动作 |
| admin请求实际用户列表 | 业务0，存在真实分页数据结构 | system:user:query允许路径实际可用 |
| reader直接请求相同列表 | 业务403且无数据 | 服务端@PreAuthorize阻断，非仅按钮隐藏 |
| admin/reader令牌配tenant-id=2 | 业务403 | 令牌租户与请求租户不一致被拒绝；tenant2不存在，未证明第二个存在租户的数据隔离 |
| admin令牌不显式发tenant头 | 业务0 | 按源码从有效会话恢复其自身tenant上下文 |
| 匿名login使用不存在tenant2 | 业务1002015000 | 不存在租户拒绝；未建立第二租户夹具 |
| 停用账号login | 业务1002000001，不发token | 停用账号拒绝 |
| 两个新建测试会话logout | 均业务0且true | 本次退出请求成功 |
| 退出后的两个旧访问令牌 | 均业务401 | 本次旧访问令牌失效 |

对应源为 UserController.java 的 /system/user/page + system:user:query；TenantSecurityWebFilter 对已登录令牌租户与请求头进行比对；ErrorCodeConstants 指定停用用户及不存在租户错误码。只读DB观察：一个有效租户、tenant2不存在、两个启用账号、一个停用账号；admin授予query/list，reader无query。未修改源或数据库配置，业务数据未增删。登录/退出产生的受控会话及审计属于本次测试必要动作。

## 首轮失败与修正

原 evidence/acceptance/m1-role-permissions.json 保留，5 PASS/7 FAIL。日志经safe_log读取，出现运行期NoClassDefFoundError，包括Tomcat RequestUtil；主执行方随后停服并改用不可变release JAR。复测前同时纠正两项测试期望：认证过滤器优先拒绝不能误算tenant检查；不存在tenant需经真实允许匿名的login路由验证。失败证据没有覆盖或删除，也没有把它重写成通过。

## 不能扩大结论

这18项是认证/权限的限定真实检查，不等于BASE-03或AUTH-01整体通过。未覆盖：存在的第二租户/第二门店资源、打印文件归属、客户/员工/终端身份隔离、全部业务动作权限、密码变更与恢复审计、过期令牌和refresh-token策略、浏览器/视觉一致性、用户验收。后续覆盖位置见 docs/acceptance/m1-current-evidence.json。

测试工具为 ops/acceptance/role_permission_smoke.py。运行完成已撤销本次创建的两会话。三个派生私有smoke凭据文件仍供主执行方基础认证/日志回归使用，本子任务未删除；主执行方全部认证测试结束后可清理，原始凭据保留于受限runtime/secrets目录。
