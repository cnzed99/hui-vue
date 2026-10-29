"""Representative source/fake-port tests; no real DB, Redis, HTTP or browser."""
import copy
import io
import pathlib
import sys
import tempfile
import threading
import unittest
from unittest import mock
sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'ops/db'), str(ROOT / 'ops/acceptance')]
import unified_v3_session_ports as sessions
import unified_v3_acceptance_ports as ports
import ordinary_modules_activation_acceptance as acceptance


class Lease:
    def check(self):
        pass


def plan():
    return {'runId': '04000000-0000-4000-8000-000000000001', 'storeId': 7,
            'users': {alias: {'userId': uid, 'tenantId': 1, 'roleId': rid,
                             'credentialRef': {'path': '/FAKE/' + alias, 'sha256': 'a' * 64}}
                      for alias, uid, rid in (('admin', 11, 1), ('reader', 12, 2))}}


def rows():
    return {'access': [{'id': 101, 'token': 'FAKE_PRIVATE_ACCESS_TOKEN',
        'refresh': 'FAKE_PRIVATE_REFRESH_TOKEN', 'userId': 11, 'userType': 2,
        'tenantId': 1, 'clientId': 'default'}],
        'refresh': [{'id': 201, 'token': 'FAKE_PRIVATE_REFRESH_TOKEN', 'userId': 11,
                     'userType': 2, 'tenantId': 1, 'clientId': 'default'}]}


class Database:
    def __init__(self, lose_delete=False):
        self.rows = rows()
        self.deletes = []
        self.lose_delete = lose_delete
        self.unrelated = {'id': 999, 'userId': 99}

    def factory(self):
        outer = self
        class Connection:
            def objects(self, sql, args):
                if sql == sessions.USER:
                    return [{'id': 11, 'tenantId': 1, 'status': 0, 'deleted': 0}]
                if sql == sessions.USER_BY_NAME:
                    assert args == ('FAKE_USERNAME', 1)
                    return [{'id': 11, 'tenantId': 1, 'status': 0, 'deleted': 0}]
                if sql == sessions.ROLES:
                    return [{'roleId': 1}]
                if sql == sessions.STORES:
                    return [{'storeId': 7}]
                if sql == sessions.OAUTH:
                    return [{'clientId': 'default', 'status': 0, 'accessTtl': 1800, 'refreshTtl': 86400}]
                for kind in ('access', 'refresh'):
                    if sql == sessions.TOKEN_READ[kind]:
                        assert args == (11, 2, 1, 'default')
                        return copy.deepcopy(outer.rows[kind])
                    if sql == sessions.TOKEN_REMAIN[kind]:
                        return [{'count': sum(row['id'] == args[0] for row in outer.rows[kind])}]
                raise AssertionError('unexpected SQL')
            def execute(self, sql, args):
                kind = next(kind for kind, value in sessions.TOKEN_DELETE.items() if value == sql)
                row = outer.rows[kind][0]
                assert args == (row['id'], 11, 2, 1, 'default', sessions.token_fingerprint(row['token']))
                outer.deletes.append((kind, args[0]))
                outer.rows[kind] = []
                if outer.lose_delete:
                    raise OSError('delete_effect_completed_response_unknown')
                return 1
            def close(self):
                pass
        return Connection()


class Redis:
    def __init__(self):
        self.access = 1
        self.calls = []
    def bind(self, lease):
        pass
    def exact(self, action, kind, token):
        self.calls.append((action, kind))
        if action == 'DEL':
            self.access = 0
            return 1
        return self.access if kind == 'access' else 0


class Http:
    def request(self, *args):
        return {'status': 200, 'body': {'code': 0, 'data': True}}


class Tests(unittest.TestCase):
    def test_01_exact_session_sql_redis_success_and_unknown_delete_no_replay(self):
        frozen = plan()
        self.assertFalse(sessions.NATIVE_EXECUTION_ENABLED)
        with self.assertRaisesRegex(ValueError, 'NATIVE_SESSION_SQL_EXECUTION_DISABLED'):
            sessions.FixedSessionMysql().query(sessions.OAUTH, ('default',))
        with (mock.patch.object(sessions.socket, 'create_connection', side_effect=AssertionError('constructor IO')),
              mock.patch.object(sessions.MysqlSession, '_start', side_effect=AssertionError('constructor IO'))):
            sessions.FixedSessionMysql()
            sessions.ExactRedisTokenPort({})
            sessions.SqlRedisSessionStore(frozen)
        for lose in (False, True):
            with self.subTest(lose=lose):
                database, redis = Database(lose), Redis()
                store = sessions.SqlRedisSessionStore(frozen, database.factory, redis, Http(), lambda: {
                    'ingressClosed': True, 'backendInactive': True, 'applicationConnections': 0})
                store.bind(Lease())
                self.assertEqual(store.identity(frozen['users']['admin']), 'EXACT_ACTIVE_USER_ROLE_STORE')
                self.assertEqual(store.oauth_client()['accessTtl'], 1800)
                self.assertEqual(store.assert_login_credential(frozen['users']['admin']['credentialRef'],
                    'FAKE_USERNAME'), 'EXACT_FROZEN_CREDENTIAL_USER')
                actual = store.token_rows(frozen['users']['admin'])
                store.logout_exact(actual)
                store.prepare_cleanup({'admin': actual})
                if lose:
                    with self.assertRaises(OSError):
                        store.revoke_exact(actual)
                    with self.assertRaises(ValueError):
                        store.revoke_exact(actual)
                    self.assertEqual(database.deletes, [('access', 101)])
                else:
                    self.assertEqual(store.revoke_exact(actual), {'access': 0, 'refresh': 0,
                                     'redisAccess': 0, 'redisRefresh': 0})
                    self.assertEqual(database.deletes, [('access', 101), ('refresh', 201)])
                self.assertEqual(database.unrelated, {'id': 999, 'userId': 99})
                self.assertFalse(any(action == 'DEL' and kind != 'access' for action, kind in redis.calls))
                with self.assertRaises(ValueError):
                    store.token_rows({'userId': 99, 'tenantId': 1, 'roleId': 1})
        transcript = []
        class Connection:
            def makefile(self, mode):
                return io.BytesIO(b'+OK\r\n:1\r\n')
            def sendall(self, raw):
                transcript.append(raw)
            def close(self):
                pass
        redis = sessions.ExactRedisTokenPort({'host': '127.0.0.1', 'port': 6379, 'database': 0,
                'accessPrefix': 'oauth2_access_token:', 'refreshPrefix': None},
                password=lambda: b'FAKE_PASSWORD', connect=lambda *args, **kwargs: Connection())
        redis.bind(Lease())
        self.assertEqual(redis.exact('DEL', 'access', 'FAKE_PRIVATE_ACCESS_TOKEN'), 1)
        self.assertIn(b'oauth2_access_token:', transcript[1])
        with self.assertRaises(ValueError):
            redis.exact('DEL', 'access', 'FAKE_PRIVATE_ACCESS_TOKEN')
        with self.assertRaises(ValueError):
            redis.exact('SCAN', 'access', 'FAKE_PRIVATE_ACCESS_TOKEN')

    def test_02_source_endpoint_schema_and_one_shot_concurrent_cas(self):
        for path in acceptance.GET_PATHS:
            self.assertEqual(ports.endpoint_schema(path)['type'], 'object')
        endpoint = '/admin-api/print/directory/my-products'
        frozen = {'acceptanceContract': {'getSchemas': {endpoint: ports.endpoint_schema(endpoint)}}}
        native = acceptance.FixedLoopbackHttp(frozen)
        native.bind(Lease())
        with self.assertRaisesRegex(ValueError, 'NATIVE_HTTP_EXECUTION_DISABLED'):
            native.raw('127.0.0.1', 48080, 'GET', endpoint)
        data = {'list': [], 'total': 0, 'page': 1, 'pageSize': 20, 'categories': [], 'dataBasis': 'FAKE'}
        def exchange(*args):
            return {'status': 200, 'body': {'code': 0, 'data': copy.deepcopy(data)}}
        http = acceptance.FixedLoopbackHttp(frozen, exchange=exchange)
        http.bind(Lease())
        token = 'FAKE_PRIVATE_ACCESS_TOKEN'
        session = {'accessId': 101, 'accessFingerprint': acceptance.fingerprint(token)}
        http.bind_session(session, token)
        self.assertEqual(http.successful_get(endpoint, session), data)
        data['unexpected'] = True
        with self.assertRaisesRegex(ValueError, 'schema_mismatch'):
            http.successful_get(endpoint, session)
        with self.assertRaisesRegex(ValueError, 'business_code'):
            acceptance.common_result({'status': 200, 'body': {'code': 403, 'data': {}}}, ports.obj({}))
        class Sql:
            value = {'version': '0'}
            def case_readback(self, key):
                return copy.deepcopy(self.value)
        sql = Sql()
        lock = threading.Lock()
        class CasHttp:
            count = 0
            def guard(self):
                pass
            def raw(self, host, port, method, path, body, actor):
                with lock:
                    self.count += 1
                    if self.count == 1:
                        sql.value = {'version': '1', 'name': body['name']}
                        return {'status': 200, 'body': {'code': 0, 'data': {'id': '04000000-0000-4000-8000-000000000015', 'version': '1',
                            'name': body['name'], 'fileCount': None, 'sort': None}}}
                    return {'status': 409, 'body': {'code': 987654, 'msg': 'FAKE_CONFLICT'}}
        calls = [{'id': name, 'alias': 'admin', 'method': 'PUT', 'path': '/admin-api/print/library/categories/04000000-0000-4000-8000-000000000015',
                  'body': {'name': name, 'expectedVersion': '0'}} for name in ('A', 'B')]
        group = {'id': 'fake-cas', 'calls': calls, 'readbackKey': 'fake-row', 'beforeVersion': '0',
                 'expectedBefore': {'version': '0'}, 'successDataSchema': ports.endpoint_schema(calls[0]['path'], 'PUT'),
                 'successHttp': 200, 'versionField': 'version', 'conflictHttp': 409, 'conflictCode': 987654,
                 'conflictDataSchema': ports.obj({}), 'expectedAfterByWinner': {
                     name: {'version': '1', 'name': name} for name in ('A', 'B')}}
        concurrent = ports.ConcurrentCases(CasHttp(), sql)
        proof = concurrent.run(group, {'admin': {}})
        self.assertIn(proof['winner'], ('A', 'B'))
        self.assertEqual(sorted(row['code'] for row in proof['results']), [0, 987654])
        semantic = {'concurrentCases': [group], 'expectedProof': {'cases': []}, 'expectedSqlReadback': {}}
        observed = {'cases': [], 'concurrentCases': [proof]}
        ports.validate_case_proof(semantic, observed)
        ports.validate_scoped_readback(semantic, observed, {'fixed': {}, 'concurrent': {
            group['id']: group['expectedAfterByWinner'][proof['winner']]}})
        bad = copy.deepcopy(observed)
        bad['concurrentCases'][0]['winner'] = 'UNKNOWN'
        with self.assertRaises(ValueError):
            ports.validate_case_proof(semantic, bad)
        with self.assertRaises(ValueError):
            concurrent.run(group, {'admin': {}})

    def test_03_bound_browser_receipt_and_manual_pending_cannot_ack(self):
        frozen = plan()
        actors = {alias: {'userId': user['userId'], 'tenantId': 1, 'clientId': 'default',
                  'accessId': 101 + index, 'refreshId': 201 + index,
                  'accessFingerprint': 'a' * 64, 'refreshFingerprint': 'b' * 64}
                  for index, (alias, user) in enumerate(frozen['users'].items())}
        contract = {'mode': 'CONTROLLED_CALLBACK',
                    'viewport': {'width': 1280, 'height': 800}, 'requiredRoutes': [
                    {'alias': alias, 'path': '/print/library/categories', 'expectedState': 'FAKE_PASS'}
                    for alias in ('admin', 'reader')]}
        frozen['browserContract'] = contract
        bindings = ports.safe_session_bindings(actors)
        proof = {'status': 'PASS_BROWSER', 'runId': frozen['runId'],
                 'planSha256': ports.digest(ports.canon(frozen)),
                 'sessionBindings': bindings, 'viewport': contract['viewport'], 'routes': [
                 {'alias': row['alias'], 'path': row['path'], 'state': row['expectedState'],
                  'screenshotSha256': 'd' * 64} for row in contract['requiredRoutes']]}
        browser = ports.BoundBrowserPort(frozen, lambda *args: copy.deepcopy(proof), lambda *args: {
            'runId': frozen['runId'], 'sessionBindings': bindings, 'authResiduals': 0})
        self.assertEqual(browser.verify(contract, actors), 'ACK')
        self.assertEqual(browser.clear_auth(actors), 'ACK')
        bad = copy.deepcopy(proof)
        bad['sessionBindings']['reader']['userId'] = 99
        with self.assertRaisesRegex(ValueError, 'binding_mismatch'):
            ports.BoundBrowserPort(frozen, lambda *args: bad).verify(contract, actors)
        manual = ports.ManualBrowserPort(frozen)
        self.assertFalse(manual.report()['satisfiesTechnicalBrowserGate'])
        with self.assertRaises(RuntimeError):
            manual.verify(contract, actors)


    def test_04_lost_login_response_registers_exact_logout_and_preserves_other_sessions(self):
        frozen = plan()
        database, redis = Database(), Redis()
        # Retain a pre-existing session for the same user: only the new pair may be bound/removed.
        existing = rows()
        existing['access'][0].update(id=102, token='FAKE_OTHER_ACCESS_TOKEN', refresh='FAKE_OTHER_REFRESH_TOKEN')
        existing['refresh'][0].update(id=202, token='FAKE_OTHER_REFRESH_TOKEN')
        database.rows = copy.deepcopy(existing)
        cache_tokens = {'FAKE_PRIVATE_ACCESS_TOKEN', 'FAKE_OTHER_ACCESS_TOKEN'}
        def exact_cache(action, kind, token):
            redis.calls.append((action, kind, token))
            if action == 'DEL':
                self.assertEqual((kind, token), ('access', 'FAKE_PRIVATE_ACCESS_TOKEN'))
                cache_tokens.remove(token)
                return 1
            return int(kind == 'access' and token in cache_tokens)
        redis.exact = exact_cache
        calls = []
        def exchange(host, port, method, path, body, headers):
            calls.append((method, path))
            if path == acceptance.LOGIN:
                for kind in ('access', 'refresh'):
                    database.rows[kind] += rows()[kind]
                raise OSError('login_created_but_response_lost')
            self.assertEqual(path, acceptance.LOGOUT)
            self.assertEqual(headers['Authorization'], 'Bearer FAKE_PRIVATE_ACCESS_TOKEN')
            return {'status': 200, 'body': {'code': 0, 'data': True}}
        # The old fake driver deletes its first row. Select by the same exact SQL predicate here.
        original_factory = database.factory
        def factory():
            connection = original_factory()
            execute = connection.execute
            def exact_execute(sql, args):
                kind = next(kind for kind, value in sessions.TOKEN_DELETE.items() if value == sql)
                selected = [row for row in database.rows[kind] if row['id'] == args[0]]
                rest = [row for row in database.rows[kind] if row['id'] != args[0]]
                database.rows[kind] = selected
                result = execute(sql, args)
                database.rows[kind] += rest
                return result
            connection.execute = exact_execute
            return connection
        http = acceptance.FixedLoopbackHttp(frozen, exchange=exchange)
        store = sessions.SqlRedisSessionStore(frozen, factory, redis, http, lambda: {
            'ingressClosed': True, 'backendInactive': True, 'applicationConnections': 0})
        http.sql = store
        class Events:
            def append(self, *args, **kwargs):
                pass
            def add(self, *args, **kwargs):
                pass
        class Browser:
            def clear_auth(self, actors):
                return 'ACK'
        with tempfile.TemporaryDirectory() as temporary:
            base = pathlib.Path(temporary)
            ledger_path = base / 'runtime/private/ordinary-activation' / frozen['runId'] / 'sessions.jsonl'
            ledger_path.parent.mkdir(parents=True, mode=0o700)
            frozen['sessionContract'] = {'ledgerPath': str(ledger_path)}
            ledger = acceptance.ExactSessionLedger(frozen, store)
            adapter = acceptance.AcceptanceAdapter(frozen, http, ledger, store, Browser())
            adapter.bind(Lease(), Events(), Events())
            try:
                with (mock.patch.object(acceptance, 'BASE', base),
                      mock.patch.object(http, 'credential', return_value={
                          'username': 'FAKE_USERNAME', 'password': 'FAKE_PASSWORD'})):
                    with self.assertRaises(acceptance.LoginResponseLost):
                        adapter.login()
                    self.assertEqual(set(adapter.sessions), {'admin'})
                    self.assertEqual(set(http.tokens), {(101, acceptance.fingerprint('FAKE_PRIVATE_ACCESS_TOKEN'))})
                    adapter.cleanup()
                    with self.assertRaisesRegex(ValueError, 'cleanup_unknown_no_retry'):
                        adapter.cleanup()
                self.assertEqual(calls, [('POST', acceptance.LOGIN), ('POST', acceptance.LOGOUT)])
                self.assertEqual(database.rows, existing)
                self.assertEqual(cache_tokens, {'FAKE_OTHER_ACCESS_TOKEN'})
                self.assertEqual(database.unrelated, {'id': 999, 'userId': 99})
                self.assertEqual(database.deletes, [('access', 101), ('refresh', 201)])
                self.assertEqual(http.tokens, {})
                self.assertNotIn('FAKE_PRIVATE_', ledger_path.read_text())
                self.assertFalse(acceptance.NATIVE_EXECUTION_ENABLED)
                self.assertFalse(sessions.NATIVE_EXECUTION_ENABLED)
            finally:
                ledger.close()

    def test_05_category_uuid_v4_exact_contract(self):
        category_id = '0400000a-000b-400c-800d-000000000015'
        path = '/admin-api/print/library/categories/' + category_id
        schema = ports.endpoint_schema(path, 'PUT')
        category = {'id': category_id, 'version': '1', 'name': 'FAKE', 'fileCount': None, 'sort': None}
        self.assertTrue(acceptance.data_matches(category, schema))
        self.assertEqual(schema, ports.endpoint_schema('/admin-api/print/library/categories', 'POST'))
        for invalid in ('15', category_id.upper(), category_id.replace('-400c-', '-500c-'),
                        category_id.replace('-800d-', '-700d-')):
            with self.subTest(invalid=invalid):
                self.assertFalse(acceptance.data_matches({**category, 'id': invalid}, schema))
                with self.assertRaisesRegex(ValueError, 'endpoint_schema_not_frozen'):
                    ports.endpoint_schema('/admin-api/print/library/categories/' + invalid, 'PUT')

    def test_06_long_total_exact_serializer_boundaries(self):
        paths = ('/admin-api/print/library/categories', '/admin-api/print/directory/my-products',
                 '/admin-api/print/directory/platform-products', '/admin-api/print/directory/library',
                 '/admin-api/print/directory/terminals', '/admin-api/print/directory/printers')
        safe = 9007199254740991
        accepted = (0, safe - 1, -safe + 1, str(safe), str(-safe), str(safe + 1), str(-safe - 1),
                    str(2**63 - 1), str(-2**63))
        rejected = (safe, -safe, 2**63 - 1, -2**63, True, 1.0, None, '0', str(safe - 1),
                    str(-safe + 1), '+' + str(safe), '0' + str(safe), '-0' + str(safe),
                    str(2**63), str(-2**63 - 1), str(safe) + '.0', str(safe) + 'e0')
        for path in paths:
            schema = ports.endpoint_schema(path)['properties']['total']
            for value in accepted:
                with self.subTest(path=path, accepted=value):
                    self.assertTrue(acceptance.data_matches(value, schema))
            for value in rejected:
                with self.subTest(path=path, rejected=value):
                    self.assertFalse(acceptance.data_matches(value, schema))
        # P37/P38 totals are declared Strings, unaffected by Long serialization.
        self.assertEqual(ports.endpoint_schema('/admin-api/print/statistics/paper-total')
                         ['properties']['total'], ports.scalar('string'))


if __name__ == '__main__':
    unittest.main()
