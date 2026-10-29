"""Seven concentrated fake-port checks. No database, HTTP, service or credential IO."""
import copy
import json
import os
import pathlib
import sys
import tempfile
import unittest
from unittest import mock
sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'ops/db'), str(ROOT / 'ops/runtime'), str(ROOT / 'ops/acceptance')]
import ordinary_activation_runtime as runtime
import ordinary_modules_activation_acceptance as acceptance


class Lease:
    def check(self):
        return None


class Log:
    def __init__(self):
        self.rows = []

    def append(self, phase, **safe):
        self.rows.append((phase, safe))

    def add(self, stage, status, **safe):
        pass


def fixture():
    ref = lambda name: {'path': str(ROOT / 'runtime/fake-inputs' / name), 'sha256': 'a' * 64}
    return {'runId': '03000000-0000-4000-8000-000000000001',
            'release': {key: ref(key) for key in ('jar', 'frontendManifest', 'gateConfig')},
            'backupContract': {'outputLifecycle': 'RUN_GENERATED_OUTPUT',
                'encryptedPath': str(ROOT / 'runtime/backups/fake/backup.aes'),
                'successCriteria': {'restoreVerified': True, 'temporarySchemaRemoved': True}},
            'users': {alias: {'userId': ident, 'tenantId': 1, 'roleId': role, 'credentialRef': {}}
                      for alias, ident, role in (('admin', 11, 1), ('reader', 12, 2))},
            'sessionContract': {'ledgerPath': str(ROOT / 'runtime/private/ordinary-activation' /
                '03000000-0000-4000-8000-000000000001/sessions.jsonl')},
            'acceptanceContract': {'getSchemas': {path: {'type': 'object', 'required': [],
                'properties': {}, 'additional': False} for path in acceptance.GET_PATHS}}}


class RuntimePorts:
    def __init__(self, plan):
        self.plan = plan
        self.calls = []
        self.installs = 0

    def snapshot(self):
        return {'nginxActive': False, 'externalListeners': [], 'pendingRequests': 0}

    def command(self, argv):
        self.calls.append(argv)
        return 'ACK'

    def verify(self, contract):
        self.calls.append('backup')
        return {'runId': self.plan['runId'], 'encryptedArtifact': {
            'path': contract['encryptedPath'], 'sha256': 'b' * 64},
            'restoreVerified': True, 'temporarySchemaRemoved': True}

    def install(self, *_):
        self.installs += 1
        if self.installs == 2:
            raise OSError('partial')
        return 'ACK'


class Store:
    def __init__(self):
        self.values = {'access': [], 'refresh': []}
        self.revokes = 0

    def identity(self, user):
        return 'EXACT_ACTIVE_USER_ROLE_STORE'

    def token_rows(self, user):
        return copy.deepcopy(self.values)

    def add(self):
        self.values = {'access': [{'id': 100, 'userId': 11, 'tenantId': 1, 'clientId': 'default',
            'token': 'FAKE_PRIVATE_ACCESS_VALUE', 'refresh': 'FAKE_PRIVATE_REFRESH_VALUE'}],
            'refresh': [{'id': 200, 'userId': 11, 'tenantId': 1, 'clientId': 'default',
                        'token': 'FAKE_PRIVATE_REFRESH_VALUE'}]}

    def logout_exact(self, rows):
        return {'status': 200, 'body': {'code': 0, 'data': True}}

    def revoke_exact(self, rows):
        self.revokes += 1
        self.values = {'access': [], 'refresh': []}
        raise OSError('effect_completed_response_unknown')


class Tests(unittest.TestCase):
    def test_01_runtime_output_before_release_and_partial_stays_closed(self):
        plan = fixture()
        ports = RuntimePorts(plan)
        adapter = runtime.RuntimeAdapter(plan, ports, ports)
        log = Log()
        adapter.bind(Lease(), log, log)
        proof = adapter.backup_verified()
        self.assertEqual(proof['encryptedArtifact']['sha256'], 'b' * 64)
        observed = [row for row in log.rows if row[0] == 'BACKUP_RESTORE_OBSERVED']
        self.assertEqual(len(observed), 1)
        self.assertTrue(observed[0][1]['temporarySchemaRemoved'])
        with self.assertRaises(ValueError):
            adapter.backup_verified()
        with self.assertRaises(OSError):
            adapter.install_release()
        self.assertIn('INSTALL_FRONTEND_UNKNOWN', [row[0] for row in log.rows])
        adapter.keep_closed()
        self.assertEqual(ports.calls, ['backup', ('systemctl', 'stop', 'nginx.service')])

    def test_02_business_error_stops_success_and_browser_unavailable_fails(self):
        reply = {'status': 200, 'body': {'code': 403, 'data': {}}}
        with self.assertRaisesRegex(ValueError, 'business_code'):
            acceptance.common_result(reply, {'type': 'object', 'required': [], 'properties': {}})
        with self.assertRaisesRegex(ValueError, 'BROWSER_UNAVAILABLE'):
            acceptance.ControlledBrowser().verify({}, {})
        methods = []
        def exchange(host, port, method, path, body, headers):
            methods.append((method, path))
            return {'status': 200, 'body': {'code': 0, 'data': True}}
        http = acceptance.FixedLoopbackHttp(fixture(), exchange=exchange)
        http.bind(Lease())
        token = 'FAKE_PRIVATE_ACCESS_VALUE'
        session = {'accessId': 1, 'accessFingerprint': acceptance.fingerprint(token)}
        http.bind_session(session, token)
        self.assertEqual(http.assign_same_role_menus('127.0.0.1', 48080, 1, [2000], session), 'ACK')
        self.assertEqual(methods, [('POST', acceptance.ASSIGN)])
        calls = []
        class Http:
            def request(self, *args):
                calls.append(args)
                return reply
        adapter = acceptance.AcceptanceAdapter(fixture(), Http(), object(), object(), object())
        adapter.bind(Lease(), Log(), Log())
        adapter.sessions = {'admin': {}}
        with self.assertRaises(ValueError):
            adapter.accept()
        self.assertEqual(len(calls), 1)

    def test_03_private_ledger_runtime_output_unknown_cleanup_not_replayed(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(acceptance, 'BASE', pathlib.Path(directory)):
            plan = fixture()
            parent = pathlib.Path(directory) / 'runtime/private/ordinary-activation' / plan['runId']
            parent.mkdir(parents=True, mode=0o700)
            plan['sessionContract']['ledgerPath'] = str(parent / 'sessions.jsonl')
            store = Store()
            ledger = acceptance.ExactSessionLedger(plan, store)
            ledger.bind(Lease())
            self.assertFalse((parent / 'sessions.jsonl').exists())
            intent = ledger.intent(plan['runId'], 'admin', plan['users']['admin'])
            store.add()
            row = ledger.associate_once(plan['runId'], 'admin', intent)
            with self.assertRaises(OSError):
                ledger.revoke_exact_once(plan['runId'], {'admin': row})
            with self.assertRaises(ValueError):
                ledger.revoke_exact_once(plan['runId'], {'admin': row})
            self.assertEqual(store.revokes, 1)
            ledger.close()
            raw = (parent / 'sessions.jsonl').read_bytes()
            self.assertNotIn(b'FAKE_PRIVATE_ACCESS_VALUE', raw)
            self.assertNotIn(b'FAKE_PRIVATE_REFRESH_VALUE', raw)
            self.assertIn(b'SESSION_CLEANUP_UNKNOWN', raw)

    def test_04_construction_inert_and_lost_login_not_replayed(self):
        plan = fixture()
        with mock.patch.object(runtime.os, 'open', side_effect=AssertionError('constructor IO')):
            runtime.FixedRuntimeRunner(plan)
            acceptance.FixedLoopbackHttp(plan)
            acceptance.ExactSessionLedger(plan)
        class Ports:
            def __init__(self):
                self.logins = self.associations = 0
            def oauth_client(self):
                return {'clientId': 'default', 'status': 0, 'accessTtl': 1800, 'refreshTtl': 86400}
            def intent(self, *args):
                return 'exact-intent'
            def login(self, *args):
                self.logins += 1
                raise acceptance.LoginResponseLost('unknown')
            def associate_once(self, run, alias, intent):
                self.associations += 1
                return {'runId': run, 'intentId': intent, 'userId': 11, 'tenantId': 1,
                    'clientId': 'default', 'accessId': 100, 'refreshId': 200,
                    'accessFingerprint': 'a' * 64, 'refreshFingerprint': 'b' * 64}
        ports = Ports()
        adapter = acceptance.AcceptanceAdapter(plan, ports, ports, ports, object())
        adapter.bind(Lease(), Log(), Log())
        with self.assertRaises(acceptance.LoginResponseLost):
            adapter.login()
        with self.assertRaises(ValueError):
            adapter.login()
        self.assertEqual((ports.logins, ports.associations), (1, 1))

    def test_05_current_new_but_process_argv_old_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(runtime, 'BASE', pathlib.Path(directory)):
            base = pathlib.Path(directory)
            old = base / 'runtime/releases/old/backend.jar'
            new = base / 'runtime/releases/new/backend.jar'
            old.parent.mkdir(parents=True)
            new.parent.mkdir(parents=True)
            old.write_bytes(b'old-package')
            new.write_bytes(b'new-package')
            (new.parent.parent / 'current').symlink_to(new.parent.name)
            old_argv = ['/usr/bin/java', '-jar', str(old)]
            new_argv = ['/usr/bin/java', '-jar', str(new)]
            launch = {'before': {'argv': old_argv, 'jarPath': str(old),
                      'jarSha256': runtime.digest(b'old-package')},
                      'after': {'argv': new_argv, 'jarPath': str(new),
                      'jarSha256': runtime.digest(b'new-package')}}
            rows = {'index.html': {'path': 'index.html', 'size': 1, 'sha256': 'a' * 64}}
            manifest = base / 'manifest.json'
            manifest.write_text(json.dumps({'files': list(rows.values())}))
            plan = fixture()
            plan['release']['jar']['sha256'] = runtime.digest(b'new-package')
            plan['release']['frontendManifest'] = {'path': str(manifest),
                                                 'sha256': runtime.digest(manifest.read_bytes())}
            listener = {'host': '127.0.0.1', 'port': 48080, 'processes': ['java']}
            plan['runtimeContract'] = {'backendLaunch': launch, 'listenerAllowlist': [listener],
                                      'gateSourceBindingSha256': 'b' * 64}
            plan['runtimeBefore'] = {'backendPid': 41, 'backendStartTicks': 'old-ticks'}
            runner = runtime.FixedRuntimeRunner(plan, lambda: 1, lambda: {
                'effectiveValue': True, 'configSha256': 'a' * 64,
                'sourceBindingSha256': 'b' * 64}, process_reader=lambda pid: ('new-ticks', old_argv))
            runner.bind(Lease())
            def command(argv):
                if argv == ('sudo', '-n', 'ss', '-H', '-ltnp'):
                    return b'LISTEN 0 128 127.0.0.1:48080 0.0.0.0:* users:(("java",pid=42,fd=1))\n'
                return b''
            with mock.patch.object(runner, 'run', side_effect=command), \
                 mock.patch.object(runner, 'unit', side_effect=lambda name: (False, 0, None)
                                   if name == 'nginx.service' else (True, 42, 'new-ticks')), \
                 mock.patch.object(runner, 'launch_binding', return_value=runtime.digest(runtime.canon(launch))), \
                 mock.patch.object(runtime, 'inventory', return_value=rows):
                snapshot = runner.snapshot()
                self.assertEqual((new.parent.parent / 'current/backend.jar').read_bytes(), b'new-package')
                self.assertEqual(snapshot['backendProcess']['jarPath'], str(old))
                self.assertEqual(snapshot['jarSha256'], runtime.digest(b'old-package'))
                adapter = runtime.RuntimeAdapter(plan, runner)
                log = Log()
                adapter.bind(Lease(), log, log)
                with self.assertRaisesRegex(ValueError, 'running_process_not_frozen_candidate'):
                    adapter.start_backend()
                self.assertNotIn('START_BACKEND_LOOPBACK_OBSERVED', [row[0] for row in log.rows])

    def test_06_nginx_active_with_wrong_entry_resource_is_closed(self):
        plan = fixture()
        index = b'<script src="/assets/app.js"></script>'
        asset = b'correct-asset'
        rows = {name: {'path': name, 'size': len(raw), 'sha256': runtime.digest(raw)}
                for name, raw in [('index.html', index), ('assets/app.js', asset)]}
        manifest = json.dumps({'files': list(rows.values())}).encode()
        site = b'root /var/www/print-system; proxy_pass http://127.0.0.1:48080;'
        config = b'frozen-effective-nginx-config'
        ingress = {'host': '127.0.0.1', 'port': 18080,
                   'sitePath': '/etc/nginx/sites-available/print-system',
                   'root': '/var/www/print-system', 'proxyPass': 'http://127.0.0.1:48080',
                   'siteSha256': runtime.digest(site), 'nginxConfigSha256': runtime.digest(config),
                   'indexResources': ['assets/app.js'], 'expectedProof': {}}
        plan['runtimeContract'] = {'ingress': ingress}
        plan['release']['frontendManifest']['sha256'] = runtime.digest(manifest)
        class Runner(runtime.FixedRuntimeRunner):
            def __init__(self):
                super().__init__(plan)
                self.active = False
                self.calls = []
            def snapshot(self):
                return {'nginxActive': self.active, 'pendingRequests': 0,
                        'externalListeners': [{'host': '127.0.0.1', 'port': 18080,
                                               'processes': ['nginx']}] if self.active else []}
            def command(self, argv):
                self.calls.append(argv)
                self.active = argv[1] == 'start'
                return 'ACK'
            def run(self, argv):
                return config
            def ingress_get(self, path):
                return {'status': 200, 'raw': index if path == '/login' else b'wrong-asset'}
        runner = Runner()
        adapter = runtime.RuntimeAdapter(plan, runner)
        log = Log()
        adapter.bind(Lease(), log, log)
        def files(path):
            return site if str(path) == ingress['sitePath'] else (
                index if str(path).endswith('/index.html') else manifest)
        with mock.patch.object(runtime, 'file_bytes', side_effect=files), \
             mock.patch.object(runtime, 'inventory', return_value=rows), \
             mock.patch.object(pathlib.Path, 'is_symlink', return_value=True), \
             mock.patch.object(pathlib.Path, 'resolve', return_value=pathlib.Path(ingress['sitePath'])):
            with self.assertRaisesRegex(ValueError, 'ingress_http_resource_mismatch'):
                adapter.reopen()
        self.assertFalse(runner.active)
        self.assertEqual(runner.calls, [('systemctl', 'start', 'nginx.service'),
                                        ('systemctl', 'stop', 'nginx.service')])
        self.assertIn('REOPEN_INGRESS_UNKNOWN', [row[0] for row in log.rows])
        self.assertNotIn('REOPEN_INGRESS_OBSERVED', [row[0] for row in log.rows])

    def test_07_replaced_ledger_path_is_rejected_before_append(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(acceptance, 'BASE', pathlib.Path(directory)):
            plan = fixture()
            parent = pathlib.Path(directory) / 'runtime/private/ordinary-activation' / plan['runId']
            parent.mkdir(parents=True, mode=0o700)
            path = parent / 'sessions.jsonl'
            plan['sessionContract']['ledgerPath'] = str(path)
            flags = []
            def opener(path, mode, permissions):
                flags.append(mode)
                return os.open(path, mode, permissions)
            ledger = acceptance.ExactSessionLedger(plan, Store(), opener=opener)
            ledger.bind(Lease())
            ledger.intent(plan['runId'], 'admin', plan['users']['admin'])
            self.assertTrue(flags[0] & os.O_APPEND)
            self.assertTrue(flags[0] & os.O_NOFOLLOW)
            moved = parent / 'sessions.moved'
            path.rename(moved)
            previous = moved.read_bytes()
            with path.open('xb') as stream:
                stream.write(b'')
            path.chmod(0o600)
            with self.assertRaisesRegex(ValueError, 'ledger_file_replaced_or_unlinked'):
                ledger.append('SHOULD_NOT_APPEAR')
            self.assertEqual(moved.read_bytes(), previous)
            self.assertEqual(path.read_bytes(), b'')
            ledger.close()


if __name__ == '__main__':
    unittest.main()
