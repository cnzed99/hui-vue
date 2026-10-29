#!/usr/bin/env python3
"""Unified v3 acceptance ports. Import and construction have no IO.

No main applies a plan. Fixed HTTP and private-ledger effects require an explicit
held lease. Database/session and controlled-browser proof ports must be supplied;
missing ports or an unavailable browser fail closed.
"""
import copy
import hashlib
import http.client
import json
import os
import pathlib
import re
import stat
import uuid
from urllib.parse import urlsplit
from ordinary_activation_common import require, canon, digest, BASE

POSITIONS = ('COMPUTER_TERMINAL', 'TOUCH_TERMINAL', 'FULLSCREEN_TERMINAL',
             'PORTRAIT_TERMINAL', 'CLOUD_BOX', 'ORDER_PAGE', 'COMPUTER_DESKTOP', 'MINI_SHARE')
GET_PATHS = (
    '/admin-api/print/store/current', '/admin-api/print/library/settings',
    '/admin-api/print/directory/my-products', '/admin-api/print/directory/platform-products',
    '/admin-api/print/directory/library', '/admin-api/print/directory/terminals',
    '/admin-api/print/directory/printers', '/admin-api/print/library/categories',
    '/admin-api/print/statistics/terminal', '/admin-api/print/statistics/paper-total',
    '/admin-api/print/statistics/paper-daily',
) + tuple('/admin-api/print/advertising/' + position for position in POSITIONS)
LOGIN = '/admin-api/system/auth/login'
LOGOUT = '/admin-api/system/auth/logout'
ROLE_LIST = '/admin-api/system/permission/list-role-menus'
ASSIGN = '/admin-api/system/permission/assign-role-menu'
CHECKS = {
    'permission': '16 nodes, 25 grants; original physical rows preserved; delete 2017 ungranted',
    'http': 'expected HTTP, integer CommonResult code zero and endpoint-specific data schema',
    'sessions': 'run-exclusive ledger; precise access/refresh IDs and fingerprints; unknown never replayed',
    'browser': '19 routes with real controlled proof; unavailable is not ACK',
    'release': 'source-bound JAR and complete frontend manifest, gate evidence, token cleanup before reopen',
}


def slot_removal_http(existing_legal_slot=False, retention_human_confirmed=False):
    return {'SLOT_REMOVAL_HTTP': 'NOT_RUN',
            'reason': 'SEPARATE_APPROVED_MATERIAL_CASE_REQUIRED' if existing_legal_slot and
            retention_human_confirmed else 'NO_APPROVED_EXISTING_LEGAL_MATERIAL',
            'blocksEmptyDraftActivation': False}


class Runtime:
    implemented = False

    def __init__(self, *_):
        raise RuntimeError('SOURCE_ONLY_RUNTIME_HTTP_ADAPTERS_NOT_IMPLEMENTED')


def credential_contract(base):
    base = pathlib.Path(base)
    return {'admin': str(base / 'runtime/secrets/smoke-admin.json'),
            'reader': str(base / 'runtime/secrets/smoke-reader.json'), 'tenantId': 1,
            'clientId': 'default', 'accessTtl': 1800, 'refreshTtl': 86400,
            'tokenStorage': 'MEMORY_ONLY_EXACT_PRIVATE_LEDGER', 'allowRefresh': False}


def data_matches(value, schema):
    """Small exact schema language carried in the reviewed immutable plan."""
    require(isinstance(schema, dict) and isinstance(schema.get('type'), str), 'data_schema_required')
    kind = schema['type']
    if value is None:
        return kind == 'null' or schema.get('nullable') is True
    if kind == 'object':
        if not isinstance(value, dict):
            return False
        properties = schema.get('properties', {})
        required = schema.get('required', [])
        require(isinstance(properties, dict) and isinstance(required, list) and
                set(required) <= set(properties), 'object_schema_invalid')
        return (set(required) <= set(value) and
                (schema.get('additional') is True or set(value) <= set(properties)) and
                all(data_matches(item, properties[key]) for key, item in value.items() if key in properties))
    if kind == 'array':
        return isinstance(value, list) and all(data_matches(item, schema['items']) for item in value)
    if kind == 'integer':
        return type(value) is int and value >= schema.get('minimum', -(2**63)) and value <= schema.get('maximum', 2**63-1)
    if kind == 'boolean':
        return type(value) is bool
    if kind == 'string':
        return isinstance(value, str) and len(value) >= schema.get('minLength', 0) and (
            'enum' not in schema or value in schema['enum'])
    if kind == 'null':
        return False
    raise ValueError('unsupported_data_schema_type')


def common_result(reply, schema, expected_http=200, expected_code=0):
    require(isinstance(reply, dict) and set(reply) == {'status', 'body'} and
            type(reply['status']) is int and reply['status'] == expected_http, 'http_status_not_expected')
    body = reply['body']
    require(isinstance(body, dict) and type(body.get('code')) is int and
            body['code'] == expected_code, 'common_result_business_code_not_expected')
    if expected_code == 0:
        require('data' in body and data_matches(body['data'], schema), 'common_result_data_schema_mismatch')
        return body['data']
    require(expected_code != 0, 'nonzero_rejection_code_required')
    return None


def fingerprint(token):
    require(isinstance(token, str) and len(token) >= 16, 'opaque_token_required')
    return hashlib.sha256(token.encode()).hexdigest()


class LoginResponseLost(RuntimeError):
    pass


class FixedLoopbackHttp:
    def __init__(self, plan, exchange=None, sql=None):
        self.plan = copy.deepcopy(plan)
        self.exchange = exchange
        self.sql = sql
        self.lease = None
        self.tokens = {}
        self.logins = set()
        self.case_started = self.cache_started = False

    def bind(self, lease):
        self.lease = lease

    def guard(self):
        require(self.lease is not None, 'lock_first_required')
        self.lease.check()

    def raw(self, host, port, method, path, body=None, session=None):
        self.guard()
        require(host == '127.0.0.1' and port == 48080 and method in ('GET', 'POST', 'PUT', 'DELETE'),
                'fixed_loopback_endpoint_required')
        parsed = urlsplit(path)
        require(path.startswith('/') and not parsed.scheme and not parsed.netloc and
                not parsed.fragment and '\\' not in path and all(ord(c) >= 32 for c in path),
                'invalid_http_path')
        allowed = {(row['method'], row['path']) for row in
                   self.plan.get('acceptanceContract', {}).get('cases', [])}
        allowed |= {('GET', item) for item in GET_PATHS}
        allowed |= {('POST', LOGIN), ('POST', LOGOUT), ('POST', ASSIGN)}
        allowed |= {('GET', ROLE_LIST + '?roleId=' + str(rid)) for rid in (1, 2)}
        require((method, path) in allowed, 'request_not_in_frozen_allowlist')
        headers = {'Content-Type': 'application/json', 'tenant-id': '1'}
        if session is not None:
            key = (session['accessId'], session['accessFingerprint'])
            require(key in self.tokens, 'unregistered_exact_session')
            headers['Authorization'] = 'Bearer ' + self.tokens[key]
        if self.exchange is not None:
            return self.exchange(host, port, method, path, body, headers)
        connection = http.client.HTTPConnection(host, port, timeout=20)
        try:
            connection.request(method, path, None if body is None else json.dumps(body).encode(), headers)
            response = connection.getresponse()
            raw = response.read(1024**2 + 1)
            require(len(raw) <= 1024**2, 'http_response_too_large')
            return {'status': response.status, 'body': json.loads(raw)}
        finally:
            connection.close()

    def credential(self, ref):
        self.guard()
        allowed = {str(BASE / 'runtime/secrets/smoke-admin.json'),
                   str(BASE / 'runtime/secrets/smoke-reader.json')}
        path = pathlib.Path(ref['path'])
        require(str(path) in allowed and all(not p.is_symlink() for p in (path, *path.parents)),
                'credential_path_not_fixed')
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'rb') as stream:
            info = os.fstat(fd)
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
                    and stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 8192,
                    'private_credential_file_required')
            raw = stream.read()
        require(digest(raw) == ref['sha256'], 'credential_bytes_changed')
        value = json.loads(raw)
        require(set(value) == {'username', 'password'} and all(
            isinstance(item, str) and item for item in value.values()), 'credential_schema')
        return value

    def login(self, host, port, path, ref, intent):
        self.guard()
        require(path == LOGIN and intent not in self.logins, 'login_no_retry')
        self.logins.add(intent)
        credentials = self.credential(ref)
        try:
            return self.raw(host, port, 'POST', path, credentials)
        except (OSError, TimeoutError, json.JSONDecodeError):
            raise LoginResponseLost('LOGIN_RESULT_UNKNOWN_NO_RETRY') from None
        finally:
            credentials.clear()

    def bind_session(self, row, access):
        self.guard()
        require(fingerprint(access) == row['accessFingerprint'], 'session_token_binding_mismatch')
        self.tokens[(row['accessId'], row['accessFingerprint'])] = access

    def request(self, host, port, method, path, body, session):
        return self.raw(host, port, method, path, body, session)

    def role_menu_ids(self, host, port, role, session):
        schema = {'type': 'array', 'items': {'type': 'integer', 'minimum': 1}}
        value = common_result(self.raw(host, port, 'GET', ROLE_LIST + '?roleId=' + str(role),
                                      session=session), schema)
        require(len(set(value)) == len(value), 'duplicate_role_menu_id')
        return sorted(value)

    def filtered_menu_ids(self, role, ids):
        self.guard()
        require(self.sql is not None, 'filtered_menu_proof_port_required')
        return self.sql.filtered_menu_ids(role, copy.deepcopy(ids))

    def assign_same_role_menus(self, host, port, role, ids, session):
        require(not self.cache_started and role == 1, 'cache_noop_no_retry')
        self.cache_started = True
        value = common_result(self.raw(host, port, 'POST', ASSIGN,
                              {'roleId': role, 'menuIds': ids}, session), {'type': 'boolean'})
        require(value is True, 'cache_api_ack_false')
        return 'ACK'

    def reviewed_cases(self, contract, sessions):
        self.guard()
        require(not self.case_started and self.sql is not None, 'reviewed_cases_no_replay_or_sql_missing')
        self.case_started = True
        results = []
        for case in contract['cases']:
            require(case['alias'] in sessions and type(case['expectedCode']) is int and
                    type(case['expectedHttp']) is int, 'reviewed_case_contract_invalid')
            before = self.sql.case_readback(case['readbackKey'])
            reply = self.raw('127.0.0.1', 48080, case['method'], case['path'],
                             copy.deepcopy(case.get('body')), sessions[case['alias']])
            data = common_result(reply, case['dataSchema'], case['expectedHttp'], case['expectedCode'])
            after = self.sql.case_readback(case['readbackKey'])
            if case['expectedCode'] != 0:
                require(after == before, 'rejection_had_database_side_effect')
            else:
                require(after == case['expectedReadback'], 'case_not_independently_persisted')
            results.append({'id': case['id'], 'http': reply['status'],
                            'code': reply['body']['code'], 'readbackSha256': digest(canon(after))})
        return {'cases': results}

    def clear_tokens(self):
        self.tokens.clear()


class ExactSessionLedger:
    """Append-only private output. Token SQL/Redis driver exposes only exact-ID operations.

    store.identity(user) validates the sole role/store/active username contract;
    store.token_rows(user) returns access/refresh rows in memory; store.logout_exact,
    revoke_exact and residuals operate only on the associated rows handed to them.
    """
    def __init__(self, plan, store=None, opener=os.open):
        self.plan = copy.deepcopy(plan)
        self.store, self.opener = store, opener
        self.lease = None
        self.fd = None
        self.file_identity = None
        self.started = set()
        self.associated = set()
        self.before, self.rows, self.intent_ids = {}, {}, {}
        self.cleanup_started = False

    def bind(self, lease):
        self.lease = lease

    def guard(self):
        require(self.lease is not None and self.store is not None, 'ledger_ports_not_configured')
        self.lease.check()

    def append(self, event, **safe):
        self.guard()
        path = pathlib.Path(self.plan['sessionContract']['ledgerPath'])
        expected = BASE / 'runtime/private/ordinary-activation' / self.plan['runId'] / 'sessions.jsonl'
        require(path == expected and all(not part.is_symlink() for part in (path, *path.parents)),
                'ledger_path_not_exact')
        parent = path.parent.stat()
        require(stat.S_ISDIR(parent.st_mode) and parent.st_uid == os.getuid() and
                stat.S_IMODE(parent.st_mode) == 0o700, 'ledger_private_parent_required')
        if self.fd is None:
            self.fd = self.opener(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_APPEND |
                                  os.O_NOFOLLOW, 0o600)
            info = os.fstat(self.fd)
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
                    and stat.S_IMODE(info.st_mode) == 0o600, 'ledger_file_owner_mode')
            self.file_identity = (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_uid)
            parent_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(parent_fd)
            finally:
                os.close(parent_fd)
        require(not any(re.search('token|password|credential|secret|authorization', key, re.I)
                        for key in safe), 'ledger_secret_key_forbidden')
        raw = canon({'runId': self.plan['runId'], 'planSha256': digest(canon(self.plan)),
                     'event': event, **safe}) + b'\n'
        self.check_file_identity(path)
        require(os.write(self.fd, raw) == len(raw), 'ledger_short_write')
        os.fsync(self.fd)
        self.check_file_identity(path)

    def check_file_identity(self, path):
        descriptor = os.fstat(self.fd)
        entry = os.lstat(path)
        identity = lambda item: (item.st_dev, item.st_ino, item.st_mode, item.st_nlink, item.st_uid)
        require(self.file_identity is not None and identity(descriptor) == self.file_identity and
                identity(entry) == self.file_identity and stat.S_ISREG(descriptor.st_mode) and
                descriptor.st_nlink == 1 and descriptor.st_uid == os.getuid() and
                stat.S_IMODE(descriptor.st_mode) == 0o600, 'ledger_file_replaced_or_unlinked')

    def intent(self, run, alias, user):
        self.guard()
        require(run == self.plan['runId'] and alias in ('admin', 'reader') and alias not in self.started
                and user == self.plan['users'][alias], 'ledger_login_scope_or_replay')
        self.started.add(alias)
        require(self.store.identity(copy.deepcopy(user)) == 'EXACT_ACTIVE_USER_ROLE_STORE',
                'login_identity_not_exact')
        rows = self.store.token_rows(copy.deepcopy(user))
        require(set(rows) == {'access', 'refresh'}, 'session_row_kinds_required')
        self.before[alias] = copy.deepcopy(rows)
        intent = uuid.uuid4().hex
        self.intent_ids[alias] = intent
        self.append('LOGIN_INTENT', alias=alias, intentId=intent, userId=user['userId'],
                    beforeIds={kind: [row['id'] for row in values] for kind, values in rows.items()})
        return intent

    def associate_once(self, run, alias, intent):
        self.guard()
        require(run == self.plan['runId'] and alias in self.started and alias not in self.associated,
                'ledger_association_no_replay')
        require(self.intent_ids[alias] == intent, 'ledger_intent_binding_mismatch')
        self.associated.add(alias)
        user = self.plan['users'][alias]
        rows = self.store.token_rows(copy.deepcopy(user))
        for kind in ('access', 'refresh'):
            previous = {row['id']: row for row in self.before[alias][kind]}
            current = {row['id']: row for row in rows[kind]}
            require(len(current) == len(rows[kind]) and all(current.get(key) == value
                    for key, value in previous.items()), 'preexisting_session_rows_changed')
        found = {kind: [row for row in rows[kind] if row['id'] not in {
            item['id'] for item in self.before[alias][kind]}] for kind in ('access', 'refresh')}
        require(all(len(values) == 1 for values in found.values()), 'login_session_not_uniquely_associated')
        access, refresh = found['access'][0], found['refresh'][0]
        for row in (access, refresh):
            require(type(row['id']) is int and row['id'] > 0 and row['userId'] == user['userId'] and
                    row['tenantId'] == 1 and row['clientId'] == 'default', 'associated_session_scope_mismatch')
        require(access['refresh'] == refresh['token'], 'access_refresh_relationship_mismatch')
        self.rows[alias] = copy.deepcopy(found)
        result = {'runId': run, 'intentId': intent, 'userId': user['userId'], 'tenantId': 1,
                  'clientId': 'default', 'accessId': access['id'], 'refreshId': refresh['id'],
                  'accessFingerprint': fingerprint(access['token']),
                  'refreshFingerprint': fingerprint(refresh['token'])}
        self.append('SESSION_ASSOCIATED', alias=alias, association=result)
        return result

    def revoke_exact_once(self, run, sessions):
        self.guard()
        require(not self.cleanup_started and run == self.plan['runId'], 'ledger_cleanup_no_replay')
        self.cleanup_started = True
        self.append('SESSION_CLEANUP_INTENT')
        try:
            require(self.started == set(self.rows) == set(sessions), 'unassociated_login_intent_unknown')
            for alias in sorted(self.rows):
                rows = copy.deepcopy(self.rows[alias])
                reply = self.store.logout_exact(rows)
                require(common_result(reply, {'type': 'boolean'}) is True, 'logout_failed_or_unknown')
                proof = self.store.revoke_exact(copy.deepcopy(rows))
                require(proof == {'access': 0, 'refresh': 0, 'redisAccess': 0, 'redisRefresh': 0},
                        'exact_session_cleanup_not_proven')
                require(self.store.residuals(copy.deepcopy(rows)) == proof, 'session_residuals_not_zero')
                self.append('SESSION_EXACT_ZERO', alias=alias,
                            accessId=sessions[alias]['accessId'], refreshId=sessions[alias]['refreshId'])
            self.append('SESSION_CLEANUP_OBSERVED')
            return 'ACK'
        except BaseException:
            self.append('SESSION_CLEANUP_UNKNOWN')
            raise
        finally:
            self.rows.clear()

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None


class ControlledBrowser:
    def __init__(self, verify=None, clear=None):
        self.verifier, self.clearer = verify, clear

    def verify(self, contract, sessions):
        require(self.verifier is not None, 'CONTROLLED_BROWSER_UNAVAILABLE')
        proof = self.verifier(copy.deepcopy(contract), copy.deepcopy(sessions))
        require(proof == contract['expectedProof'] and proof.get('status') == 'PASS_BROWSER',
                'controlled_browser_evidence_missing')
        return 'ACK'

    def clear_auth(self, sessions):
        require(self.clearer is not None, 'CONTROLLED_BROWSER_CLEANUP_UNAVAILABLE')
        require(self.clearer(copy.deepcopy(sessions)) == {'authResiduals': 0},
                'controlled_browser_auth_residuals')
        return 'ACK'


class AcceptanceAdapter:
    def __init__(self, plan, http=None, ledger=None, sql=None, browser=None):
        self.plan = copy.deepcopy(plan)
        self.http, self.ledger, self.sql, self.browser = http, ledger, sql, browser
        self.lease = self.journal = self.receipts = None
        self.sessions, self.tokens = {}, {}
        self.login_started, self.associated = set(), set()
        self.cleanup_started = self.cache_started = False

    def bind(self, lease, journal, receipts):
        self.lease, self.journal, self.receipts = lease, journal, receipts
        for port in (self.http, self.ledger):
            if port is not None and hasattr(port, 'bind'):
                port.bind(lease)

    def guard(self):
        require(self.lease is not None, 'lock_first_required')
        self.lease.check()
        require(all(port is not None for port in (self.http, self.ledger, self.sql, self.browser)),
                'SOURCE_ONLY_ACCEPTANCE_PORTS_NOT_CONFIGURED')

    def event(self, stage, status, **safe):
        self.guard()
        self.journal.append(stage + '_' + status, **safe)
        self.receipts.add(stage, status, **safe)

    fingerprint = staticmethod(fingerprint)

    def associate(self, alias, intent):
        require(alias not in self.associated, 'session_association_no_replay')
        self.associated.add(alias)
        row = self.ledger.associate_once(self.plan['runId'], alias, intent)
        user = self.plan['users'][alias]
        require(set(row) == {'runId', 'intentId', 'userId', 'tenantId', 'clientId', 'accessId',
                            'refreshId', 'accessFingerprint', 'refreshFingerprint'} and
                row['runId'] == self.plan['runId'] and row['intentId'] == intent and
                row['userId'] == user['userId'] and row['tenantId'] == 1 and
                row['clientId'] == 'default' and type(row['accessId']) is int and row['accessId'] > 0 and
                type(row['refreshId']) is int and row['refreshId'] > 0, 'exact_session_association_required')
        require(all(isinstance(row[key], str) and re.fullmatch('[0-9a-f]{64}', row[key]) is not None
                    for key in ('accessFingerprint', 'refreshFingerprint')), 'session_fingerprint_required')
        self.sessions[alias] = row
        self.event('SESSION_ASSOCIATED', 'OBSERVED', alias=alias, accessId=row['accessId'],
                   refreshId=row['refreshId'])
        return row

    def login(self):
        self.guard()
        require(self.sql.oauth_client() == {'clientId': 'default', 'status': 0, 'accessTtl': 1800,
                                            'refreshTtl': 86400}, 'oauth_client_contract_drift')
        schema = {'type': 'object', 'required': ['accessToken', 'refreshToken', 'userId'],
                  'properties': {'accessToken': {'type': 'string', 'minLength': 16},
                                 'refreshToken': {'type': 'string', 'minLength': 16},
                                 'userId': {'type': 'integer', 'minimum': 1},
                                 'expiresTime': {'type': 'integer'},
                                 'openid': {'type': 'string', 'nullable': True}}, 'additional': False}
        for alias in ('admin', 'reader'):
            require(alias not in self.login_started, 'login_no_retry')
            self.login_started.add(alias)
            user = self.plan['users'][alias]
            require(user['tenantId'] == 1 and user['roleId'] == (1 if alias == 'admin' else 2),
                    'user_scope_or_role_mismatch')
            intent = self.ledger.intent(self.plan['runId'], alias, copy.deepcopy(user))
            self.event('LOGIN_' + alias.upper(), 'INTENT', intentId=intent)
            reply = None
            try:
                reply = self.http.login('127.0.0.1', 48080, LOGIN, user['credentialRef'], intent)
                data = common_result(reply, schema)
                require(data['userId'] == user['userId'], 'login_response_user_mismatch')
                self.tokens[alias] = (data['accessToken'], data['refreshToken'])
                row = self.associate(alias, intent)
                require(row['accessFingerprint'] == fingerprint(data['accessToken']) and
                        row['refreshFingerprint'] == fingerprint(data['refreshToken']),
                        'session_fingerprint_mismatch')
                if hasattr(self.http, 'bind_session'):
                    self.http.bind_session(row, data['accessToken'])
                self.event('LOGIN_' + alias.upper(), 'OBSERVED', userId=user['userId'], tenantId=1)
            except BaseException:
                if alias not in self.associated:
                    self.associate(alias, intent)
                self.event('LOGIN_' + alias.upper(), 'UNKNOWN', reason='LOGIN_RESULT_UNKNOWN_NO_RETRY')
                raise
            finally:
                if reply is not None:
                    reply.clear()

    def get(self, alias, path):
        self.guard()
        require(path in GET_PATHS and alias in self.sessions, 'GET_scope_or_path_not_allowed')
        schema = self.plan['acceptanceContract']['getSchemas'][path]
        return common_result(self.http.request('127.0.0.1', 48080, 'GET', path, None,
                                             self.sessions[alias]), schema)

    def cache_noop(self, expected):
        self.guard()
        require(not self.cache_started, 'cache_noop_no_retry')
        self.cache_started = True
        before = self.sql.permission_rows()
        require(before == expected, 'cache_before_db_drift')
        role_sets = self.plan['permissionProposal']['roleAfter']
        package = set(self.plan['permissionProposal']['packageAfter'])
        for rid, ids in role_sets.items():
            require(set(ids) <= package and self.http.role_menu_ids('127.0.0.1', 48080, int(rid),
                    self.sessions['admin']) == ids and self.http.filtered_menu_ids(int(rid), ids) == ids,
                    'filtered_role_set_changed')
        self.event('CACHE_NOOP', 'INTENT')
        require(self.http.assign_same_role_menus('127.0.0.1', 48080, 1, role_sets['1'],
                self.sessions['admin']) == 'ACK', 'cache_result_unknown_no_retry')
        require(self.sql.permission_rows() == before, 'cache_noop_changed_physical_rows')
        self.event('CACHE_NOOP', 'OBSERVED', physicalSha256=digest(canon(before)))

    def accept(self):
        self.guard()
        self.event('HTTP_BROWSER_ACCEPTANCE', 'INTENT')
        for alias in ('admin', 'reader'):
            for path in GET_PATHS:
                self.get(alias, path)
        proof = self.http.reviewed_cases(self.plan['acceptanceContract'], copy.deepcopy(self.sessions))
        require(proof == self.plan['acceptanceContract']['expectedProof'], 'reviewed_HTTP_cases_not_proven')
        require(self.sql.scoped_readback(self.plan['acceptanceContract']) ==
                self.plan['acceptanceContract']['expectedSqlReadback'], 'HTTP_write_not_persisted')
        require(self.browser.verify(self.plan['browserContract'], copy.deepcopy(self.sessions)) == 'ACK',
                'controlled_browser_proof_missing')
        self.event('HTTP_BROWSER_ACCEPTANCE', 'OBSERVED', SLOT_REMOVAL_HTTP='NOT_RUN')

    def cleanup(self):
        self.guard()
        require(not self.cleanup_started, 'cleanup_unknown_no_retry')
        self.cleanup_started = True
        self.event('SESSION_CLEANUP', 'INTENT')
        try:
            require(self.login_started <= set(self.sessions), 'unassociated_login_intent_cleanup_unknown')
            require(self.ledger.revoke_exact_once(self.plan['runId'], copy.deepcopy(self.sessions)) == 'ACK',
                    'exact_session_revoke_unknown')
            require(self.sql.session_residuals(copy.deepcopy(self.sessions)) ==
                    {'access': 0, 'refresh': 0, 'redisAccess': 0, 'redisRefresh': 0},
                    'session_residuals_not_zero')
            require(self.browser.clear_auth(copy.deepcopy(self.sessions)) == 'ACK',
                    'browser_auth_cleanup_unknown')
            self.event('SESSION_CLEANUP', 'OBSERVED')
        except BaseException:
            self.event('SESSION_CLEANUP', 'UNKNOWN')
            raise
        finally:
            self.tokens.clear()
            if self.http is not None and hasattr(self.http, 'clear_tokens'):
                self.http.clear_tokens()


if __name__ == '__main__':
    print('SOURCE_ONLY / CLI_APPLY_DISABLED / LIVE_ACCEPTANCE_NOT_RUN')
