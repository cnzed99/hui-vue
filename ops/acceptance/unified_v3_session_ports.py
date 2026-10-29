"""Source-only unified v3 session ports. All constructors are inert.

Only an explicitly bound held lease can invoke the fixed SQL/Redis transports.
No credentials are read here. Redis authentication is an explicit memory callback.
The apply CLI does not import or construct these ports.
"""
import copy
import socket
from ordinary_activation_common import MysqlSession, require, canon, digest

ADMIN_USER_TYPE = 2  # UserTypeEnum.ADMIN in the frozen backend source.
NATIVE_EXECUTION_ENABLED = False
USER = "SELECT JSON_OBJECT('id',id,'tenantId',tenant_id,'status',status,'deleted',CAST(deleted AS UNSIGNED)) FROM system_users WHERE id=%s ORDER BY id"
USER_BY_NAME = "SELECT JSON_OBJECT('id',id,'tenantId',tenant_id,'status',status,'deleted',CAST(deleted AS UNSIGNED)) FROM system_users WHERE username=%s AND tenant_id=%s AND status=0 AND deleted=b'0' ORDER BY id"
ROLES = "SELECT JSON_OBJECT('roleId',role_id) FROM system_user_role WHERE tenant_id=%s AND user_id=%s AND deleted=b'0' ORDER BY id"
STORES = "SELECT JSON_OBJECT('storeId',store_id) FROM print_staff_store WHERE tenant_id=%s AND user_id=%s AND status=0 AND deleted=b'0' ORDER BY store_id"
OAUTH = "SELECT JSON_OBJECT('clientId',client_id,'status',status,'accessTtl',access_token_validity_seconds,'refreshTtl',refresh_token_validity_seconds) FROM system_oauth2_client WHERE client_id=%s AND deleted=b'0' ORDER BY id"
TOKEN_READ = {
    kind: "SELECT JSON_OBJECT('id',id,'token'," + kind + "_token," +
    ("'refresh',refresh_token," if kind == 'access' else '') +
    "'userId',user_id,'userType',user_type,'tenantId',tenant_id,'clientId',client_id) FROM system_oauth2_" +
    kind + "_token WHERE user_id=%s AND user_type=%s AND tenant_id=%s AND client_id=%s ORDER BY id"
    for kind in ('access', 'refresh')
}
TOKEN_DELETE = {
    kind: "DELETE FROM system_oauth2_" + kind + "_token WHERE id=%s AND user_id=%s AND user_type=%s AND tenant_id=%s AND client_id=%s AND SHA2(" + kind + "_token,256)=%s"
    for kind in ('access', 'refresh')
}
TOKEN_REMAIN = {
    kind: "SELECT JSON_OBJECT('count',COUNT(*)) FROM system_oauth2_" + kind + "_token WHERE id=%s"
    for kind in ('access', 'refresh')
}
SQL_TEMPLATES = {USER, USER_BY_NAME, ROLES, STORES, OAUTH, *TOKEN_READ.values(), *TOKEN_DELETE.values(), *TOKEN_REMAIN.values()}


class FixedSessionMysql(MysqlSession):
    """Lazy mysql socket transport restricted to this module's finite templates."""
    def _start(self):
        require(NATIVE_EXECUTION_ENABLED, 'NATIVE_SESSION_SQL_EXECUTION_DISABLED')
        return super()._start()

    @classmethod
    def fixed_sql(cls, sql, params=()):
        require(sql in SQL_TEMPLATES and type(params) is tuple and sql.count('%s') == len(params),
                'session_sql_template_or_parameters_not_allowed')
        pieces = sql.split('%s')
        result = pieces[0]
        for value, tail in zip(params, pieces[1:]):
            result += cls.literal(value) + tail
        return result

    def execute(self, sql, params=()):
        require(sql in TOKEN_DELETE.values(), 'only_exact_token_delete_allowed')
        return self.execute_with_row_count(self.fixed_sql(sql, params))


def token_fingerprint(value):
    require(isinstance(value, str) and 16 <= len(value) <= 4096, 'opaque_token_required')
    return digest(value.encode())


class ExactRedisTokenPort:
    """Finite RESP operations for exact access keys, and refresh-key absence only."""
    def __init__(self, contract, password=None, connect=None):
        self.contract = copy.deepcopy(contract)
        self.password = password
        self.connect = connect
        self.lease = None
        self.deletes = set()

    def bind(self, lease):
        self.lease = lease

    def guard(self):
        require(self.lease is not None and self.password is not None, 'redis_ports_not_configured')
        self.lease.check()
        require(self.contract == {'host': '127.0.0.1', 'port': 6379, 'database': 0,
                                 'accessPrefix': 'oauth2_access_token:', 'refreshPrefix': None},
                'redis_contract_not_fixed')

    @staticmethod
    def frame(parts):
        values = [part if isinstance(part, bytes) else str(part).encode() for part in parts]
        return b'*' + str(len(values)).encode() + b'\r\n' + b''.join(
            b'$' + str(len(value)).encode() + b'\r\n' + value + b'\r\n' for value in values)

    @staticmethod
    def reply(stream):
        line = stream.readline(4097)
        require(line.endswith(b'\r\n') and len(line) <= 4096, 'redis_reply_frame_invalid')
        if line == b'+OK\r\n':
            return 'OK'
        require(line.startswith(b':') and line[1:-2] in (b'0', b'1'), 'redis_reply_not_exact_integer')
        return int(line[1:-2])

    def exact(self, action, kind, token):
        self.guard()
        require(action in ('EXISTS', 'DEL') and kind in ('access', 'refresh') and
                (kind == 'access' or action == 'EXISTS'), 'redis_operation_not_exact_allowlist')
        fingerprint = token_fingerprint(token)
        if action == 'DEL':
            marker = (kind, fingerprint)
            require(marker not in self.deletes, 'redis_delete_unknown_no_replay')
            self.deletes.add(marker)
        key = ('oauth2_access_token:' if kind == 'access' else 'oauth2_refresh_token:') + token
        require(self.connect is not None or NATIVE_EXECUTION_ENABLED, 'NATIVE_REDIS_EXECUTION_DISABLED')
        password = self.password()
        require(isinstance(password, bytes) and 1 <= len(password) <= 4096, 'redis_password_memory_contract')
        connect = self.connect or socket.create_connection
        connection = connect(('127.0.0.1', 6379), timeout=5)
        try:
            with connection.makefile('rb') as stream:
                connection.sendall(self.frame(('AUTH', password)))
                require(self.reply(stream) == 'OK', 'redis_auth_failed_or_unknown')
                connection.sendall(self.frame((action, key)))
                return self.reply(stream)
        finally:
            connection.close()
            password = None


class SqlRedisSessionStore:
    """Fixed actor identity, exact-ID token rows, and one-shot cleanup.

    Quiescence is supplied by the reviewed runtime coordinator. It must prove
    ingress closed, backend inactive and zero application DB connections before
    precise SQL/Redis cleanup, including after an unknown login outcome.
    """
    def __init__(self, plan, sql_factory=FixedSessionMysql, redis=None, http=None, quiescent=None):
        self.plan = copy.deepcopy(plan)
        self.sql_factory, self.redis, self.http, self.quiescent = sql_factory, redis, http, quiescent
        self.lease = None
        self.logout_started, self.cleanup_started = set(), set()
        self.bound_pairs = {}
        self.cleanup_prepared = False

    def bind(self, lease):
        self.lease = lease
        if self.redis is not None:
            self.redis.bind(lease)

    def guard(self):
        require(self.lease is not None, 'session_store_lock_first_required')
        self.lease.check()

    def actor(self, user):
        self.guard()
        matching = [actor for actor in self.plan['users'].values() if actor == user]
        require(len(matching) == 1 and type(user['userId']) is int and user['userId'] > 0 and
                user['tenantId'] == 1 and user['roleId'] in (1, 2), 'session_actor_not_frozen')
        require(type(self.plan['storeId']) is int and self.plan['storeId'] > 0,
                'session_store_id_not_frozen')
        return user

    def sql(self, statement, params, write=False):
        self.guard()
        require(statement in SQL_TEMPLATES, 'session_sql_not_fixed')
        session = self.sql_factory()
        try:
            return session.execute(statement, params) if write else session.objects(statement, params)
        finally:
            session.close()

    def identity(self, user):
        user = self.actor(user)
        require(self.sql(USER, (user['userId'],)) == [{'id': user['userId'], 'tenantId': 1,
                'status': 0, 'deleted': 0}], 'session_user_not_active_exact')
        require(self.sql(ROLES, (1, user['userId'])) == [{'roleId': user['roleId']}],
                'session_user_role_not_exclusive')
        require(self.sql(STORES, (1, user['userId'])) == [{'storeId': self.plan['storeId']}],
                'session_user_store_not_exclusive')
        return 'EXACT_ACTIVE_USER_ROLE_STORE'

    def oauth_client(self):
        rows = self.sql(OAUTH, ('default',))
        expected = {'clientId': 'default', 'status': 0, 'accessTtl': 1800, 'refreshTtl': 86400}
        require(rows == [expected], 'oauth_client_contract_drift')
        return expected

    def assert_login_credential(self, ref, username):
        self.guard()
        actors = [user for user in self.plan['users'].values() if user.get('credentialRef') == ref]
        require(len(actors) == 1 and isinstance(username, str) and username,
                'credential_not_bound_to_one_actor')
        user = self.actor(actors[0])
        require(self.sql(USER_BY_NAME, (username, 1)) == [{'id': user['userId'], 'tenantId': 1,
                'status': 0, 'deleted': 0}], 'credential_username_not_exact_frozen_actor')
        return 'EXACT_FROZEN_CREDENTIAL_USER'

    def token_rows(self, user):
        user = self.actor(user)
        values = {kind: self.sql(template, (user['userId'], ADMIN_USER_TYPE, 1, 'default'))
                  for kind, template in TOKEN_READ.items()}
        for kind, rows in values.items():
            require(isinstance(rows, list) and len({row['id'] for row in rows}) == len(rows),
                    'duplicate_session_row_id')
            for row in rows:
                require(set(row) == {'id', 'token', 'userId', 'userType', 'tenantId', 'clientId'} |
                        ({'refresh'} if kind == 'access' else set()), 'session_row_schema')
                require(type(row['id']) is int and row['id'] > 0 and row['userId'] == user['userId'] and
                        row['tenantId'] == 1 and row['clientId'] == 'default' and
                        row['userType'] == ADMIN_USER_TYPE, 'session_row_scope_changed')
                token_fingerprint(row['token'])
        return values

    def exact_rows(self, rows):
        self.guard()
        require(set(rows) == {'access', 'refresh'} and all(len(values) == 1 for values in rows.values()),
                'one_exact_access_refresh_pair_required')
        access, refresh = rows['access'][0], rows['refresh'][0]
        require(access['refresh'] == refresh['token'] and access['userId'] == refresh['userId'],
                'session_pair_not_related')
        for row in (access, refresh):
            require(type(row['id']) is int and row['id'] > 0 and row['tenantId'] == 1 and
                    row['clientId'] == 'default' and row['userType'] == ADMIN_USER_TYPE and
                    len([user for user in self.plan['users'].values()
                         if user['userId'] == row['userId'] and user['tenantId'] == 1]) == 1,
                    'cleanup_row_outside_exact_actor')
            token_fingerprint(row['token'])
        self.bound_pairs[access['id']] = copy.deepcopy(rows)
        return access, refresh

    def bind_associated_logout(self, association, rows):
        """Register only the pair uniquely associated and durably recorded by the ledger."""
        access, refresh = self.exact_rows(rows)
        require(set(association) == {'runId', 'intentId', 'userId', 'tenantId', 'clientId',
                'accessId', 'refreshId', 'accessFingerprint', 'refreshFingerprint'} and
                association['runId'] == self.plan['runId'] and
                isinstance(association['intentId'], str) and association['intentId'] and
                association['userId'] == access['userId'] and association['tenantId'] == 1 and
                association['clientId'] == 'default' and association['accessId'] == access['id'] and
                association['refreshId'] == refresh['id'] and
                association['accessFingerprint'] == token_fingerprint(access['token']) and
                association['refreshFingerprint'] == token_fingerprint(refresh['token']),
                'associated_logout_pair_binding_mismatch')
        require(self.http is not None and hasattr(self.http, 'bind_session') and
                access['id'] not in self.logout_started, 'associated_logout_port_missing_or_started')
        self.http.bind_session(association, access['token'])

    def logout_exact(self, rows):
        from ordinary_modules_activation_acceptance import common_result
        access, refresh = self.exact_rows(rows)
        require(self.http is not None and access['id'] not in self.logout_started,
                'logout_port_missing_or_unknown_no_replay')
        self.logout_started.add(access['id'])
        session = {'accessId': access['id'], 'accessFingerprint': token_fingerprint(access['token'])}
        reply = self.http.request('127.0.0.1', 48080, 'POST', '/admin-api/system/auth/logout', None, session)
        require(common_result(reply, {'type': 'boolean'}) is True, 'logout_ack_not_true')
        return reply

    def require_quiescent(self):
        require(self.quiescent is not None and self.quiescent() == {
            'ingressClosed': True, 'backendInactive': True, 'applicationConnections': 0},
            'exact_cleanup_quiescence_not_proven')

    def prepare_cleanup(self, rows_by_alias):
        self.guard()
        require(not self.cleanup_prepared and rows_by_alias and all(
            rows['access'][0]['id'] in self.logout_started for rows in rows_by_alias.values()),
            'all_exact_logouts_required_before_quiescence')
        self.cleanup_prepared = True
        self.require_quiescent()

    def revoke_exact(self, rows):
        access, refresh = self.exact_rows(rows)
        require(self.cleanup_prepared and self.redis is not None and access['id'] not in self.cleanup_started,
                'cleanup_port_missing_or_unknown_no_replay')
        self.cleanup_started.add(access['id'])
        self.require_quiescent()
        require(self.redis.exact('EXISTS', 'refresh', refresh['token']) == 0,
                'unexpected_refresh_cache_requires_review')
        self.redis.exact('DEL', 'access', access['token'])
        require(self.redis.exact('EXISTS', 'access', access['token']) == 0, 'access_cache_not_removed')
        for kind, row in (('access', access), ('refresh', refresh)):
            count = self.sql(TOKEN_DELETE[kind], (row['id'], row['userId'], ADMIN_USER_TYPE, 1,
                             'default', token_fingerprint(row['token'])), write=True)
            require(type(count) is int and count in (0, 1), 'exact_token_delete_rowcount')
        return self.residuals(rows)

    def residuals(self, rows):
        access, refresh = self.exact_rows(rows)
        self.require_quiescent()
        require(self.redis is not None, 'redis_residual_port_missing')
        counts = {}
        for kind, row in (('access', access), ('refresh', refresh)):
            actual = self.sql(TOKEN_REMAIN[kind], (row['id'],))
            require(len(actual) == 1 and set(actual[0]) == {'count'} and
                    type(actual[0]['count']) is int, 'token_residual_count_schema')
            counts[kind] = actual[0]['count']
        counts['redisAccess'] = self.redis.exact('EXISTS', 'access', access['token'])
        counts['redisRefresh'] = self.redis.exact('EXISTS', 'refresh', refresh['token'])
        return counts

    def session_residuals(self, sessions):
        self.guard()
        totals = {'access': 0, 'refresh': 0, 'redisAccess': 0, 'redisRefresh': 0}
        for session in sessions.values():
            require(session['accessId'] in self.bound_pairs, 'session_pair_not_associated')
            rows = self.bound_pairs[session['accessId']]
            access, refresh = self.exact_rows(rows)
            require(session['refreshId'] == refresh['id'] and
                    session['accessFingerprint'] == token_fingerprint(access['token']) and
                    session['refreshFingerprint'] == token_fingerprint(refresh['token']),
                    'session_residual_binding_mismatch')
            for key, value in self.residuals(rows).items():
                totals[key] += value
        return totals

    def clear_tokens(self):
        self.bound_pairs.clear()

