#!/usr/bin/env python3
"""Payment acceptance support; importing this module performs no I/O or mutations."""
import contextlib
import fcntl
import hmac
import argparse
import os
import shlex
import stat
import concurrent.futures
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

REQUIRED_PERMISSIONS = ('print:file:upload', 'print:file:query', 'print:quote:create',
                        'print:quote:query', 'print:order:create', 'print:order:query',
                        'print:payment:simulate')
BUSINESS_TABLES = ('print_job', 'print_simulated_journal', 'print_payment_receipt',
                   'print_order_event', 'print_order_item', 'print_order',
                   'print_quote', 'print_price_profile', 'print_file')

class SupportFailure(Exception):
    pass

def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

def api(path, token=None, body=None, method=None, content_type=None, key=None,
        *, tenant, port=48081):
    """Explicit tenant header; never patch file/quote helper globals."""
    if not isinstance(tenant, int) or tenant <= 0:
        raise SupportFailure('invalid_explicit_tenant')
    headers = {'tenant-id': str(tenant)}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if key:
        headers['Idempotency-Key'] = key
    if body is not None:
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
            content_type = 'application/json'
        headers['Content-Type'] = content_type or 'application/octet-stream'
    request = urllib.request.Request('http://127.0.0.1:' + str(port) + '/admin-api' + path,
                                     data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status, raw, response_headers = response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as response:
        status, raw, response_headers = response.code, response.read(), dict(response.headers)
    if 'json' in response_headers.get('Content-Type', ''):
        raw = json.loads(raw)
    return status, raw, response_headers

def parallel(calls):
    """A barrier makes all callers issue their requests together."""
    barrier = threading.Barrier(len(calls))
    def invoke(call):
        barrier.wait(timeout=10)
        return call()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(calls)) as pool:
        return list(pool.map(invoke, calls))

class Provenance:
    def __init__(self, base):
        self.base = base.resolve()
        self.started_utc = utc()
        self.initial = self.snapshot()
        self.processes = []
        pid = int(subprocess.check_output(['systemctl', 'show', 'print-backend',
                                          '--property=MainPID', '--value'], text=True).strip())
        self.observe_process(pid, 'normal_runtime')

    def snapshot(self):
        source = self.base / 'src/backend/yudao-server/src/main/java/cn/iocoder/yudao/server/print'
        paths = []
        for directory in ('file', 'quote', 'order', 'payment', 'scope', 'controller'):
            group = sorted((source / directory).rglob('*.java'))
            if not group:
                raise SupportFailure('missing_provenance_source_group_' + directory)
            paths.extend(group)
        paths.extend(self.base / name for name in (
            'ops/acceptance/payment_http_smoke.py', 'ops/acceptance/payment_smoke_support.py',
            'ops/acceptance/file_http_smoke.py', 'ops/acceptance/quote_http_smoke.py',
            'ops/acceptance/order_http_smoke.py', 'ops/runtime/start-backend.sh',
            'ops/runtime/application-cloud.yaml',
            'src/backend/yudao-server/src/main/resources/application.yaml',
            'src/backend/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/RedisKeyConstants.java',
            'src/backend/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/oauth2/OAuth2AccessTokenRedisDAO.java',
            'src/backend/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java',
            'src/backend/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/RoleServiceImpl.java',
            'src/backend/yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/redis/TenantRedisCacheManager.java',
            'src/backend/yudao-framework/yudao-spring-boot-starter-redis/src/main/java/cn/iocoder/yudao/framework/redis/config/YudaoCacheAutoConfiguration.java'))
        manifest = [{'path': str(path.relative_to(self.base)), 'sha256': digest(path)}
                    for path in sorted(set(paths))]
        canonical = json.dumps(manifest, separators=(',', ':'), sort_keys=True).encode()
        jar = (self.base / 'runtime/releases/current/backend.jar').resolve(strict=True)
        relative = jar.relative_to(self.base / 'runtime/releases')
        if len(relative.parts) != 2 or relative.parts[0] == 'current' or relative.parts[1] != 'backend.jar':
            raise SupportFailure('release_is_not_immutable_jar')
        return {'source_manifest': manifest, 'combined_sha256': hashlib.sha256(canonical).hexdigest(),
                'script_sha256': digest(self.base / 'ops/acceptance/payment_http_smoke.py'),
                'support_sha256': digest(self.base / 'ops/acceptance/payment_smoke_support.py'),
                'resolved_immutable_jar': str(jar.relative_to(self.base)), 'jar_sha256': digest(jar)}

    def assert_stable(self):
        if self.snapshot() != self.initial:
            raise SupportFailure('source_or_current_release_changed_during_acceptance')

    def observe_process(self, pid, label):
        self.assert_stable()
        raw = (pathlib.Path('/proc') / str(pid) / 'cmdline').read_bytes()
        command = [part.decode() for part in raw.split(b'\0') if part]
        if not command or pathlib.Path(command[0]).name != 'java' or command.count('-jar') != 1:
            raise SupportFailure('actual_process_is_not_expected_java_jar')
        jar = pathlib.Path(command[command.index('-jar') + 1])
        expected = self.base / self.initial['resolved_immutable_jar']
        # Require the actual absolute immutable argument, not current/backend.jar.
        if not jar.is_absolute() or jar != expected or jar.resolve(strict=True) != expected:
            raise SupportFailure('actual_process_jar_argument_does_not_match_immutable_release')
        if digest(jar) != self.initial['jar_sha256']:
            raise SupportFailure('actual_process_jar_hash_changed')
        self.processes.append({'observed_utc': utc(), 'pid': pid, 'label': label,
                               'executable': 'java', 'actual_jar_argument': str(jar.relative_to(self.base)),
                               'jar_sha256': digest(jar), 'cmdline_verified': True})

    def report(self):
        final = self.snapshot()
        return {'started_utc': self.started_utc, 'finished_utc': utc(),
                'initial': self.initial, 'final': final,
                'source_and_release_unchanged': final == self.initial,
                'actual_processes': self.processes,
                'model': 'gpt-6.1-sol', 'reasoning_effort': 'high',
                'model_verification': 'parent session turn_context 01a16810-3f51-7363-95fa-d2ac21daa4cb'}

RUNTIME_BASE = pathlib.Path(__file__).resolve().parents[2]
REDIS_COMMAND = ['redis-cli', '--raw', '-h', '127.0.0.1', '-p', '6379', '-n', '0']

def redis_environment():
    secret = RUNTIME_BASE / 'runtime/secrets/runtime.env'
    if secret.is_symlink() or secret.resolve() != secret or secret.stat().st_uid != os.getuid() or stat.S_IMODE(secret.stat().st_mode) & 0o077:
        raise SupportFailure('unsafe_redis_credentials_file')
    descriptor = os.open(secret, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor) as stream:
        lines = stream.read().splitlines()
    values = [shlex.split(line.split('=', 1)[1]) for line in lines if line.startswith('PRINT_REDIS_PASSWORD=')]
    if len(values) != 1 or len(values[0]) != 1 or not values[0][0]:
        raise SupportFailure('redis_auth_secret_missing')
    environment = dict(os.environ)
    environment['REDISCLI_AUTH'] = values[0][0]
    return environment

def authenticated_redis(arguments, input_text=None):
    environment = redis_environment()
    try:
        return subprocess.run(REDIS_COMMAND + arguments, input=input_text, text=True,
                              capture_output=True, env=environment, timeout=15)
    finally:
        environment.pop('REDISCLI_AUTH', None)

def redis_auth_preflight(run_id):
    if not re.fullmatch('[a-f0-9]{32}', run_id):
        raise SupportFailure('invalid_preflight_run_id')
    result = authenticated_redis(['PING'])
    if result.returncode != 0 or result.stdout.splitlines() != ['PONG']:
        raise SupportFailure('redis_auth_preflight_failed')
    # An ACL may permit PING but reject EXISTS. This is read-only and run-specific.
    result = authenticated_redis([], input_text='EXISTS acceptance-preflight:' + run_id + '\n')
    if result.returncode != 0 or result.stdout.splitlines() != ['0']:
        raise SupportFailure('redis_exists_preflight_failed')

@contextlib.contextmanager
def fixture_lock(base):
    path = pathlib.Path(base) / 'runtime/locks/fixture-mutation.lock'
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise SupportFailure('unsafe_shared_fixture_lock')
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)

def ledger_hmac_key(base, *, initialize=False):
    parent = pathlib.Path(base).resolve() / 'runtime/secrets'
    info = parent.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700 or parent.resolve() != parent:
        raise SupportFailure('unsafe_hmac_key_parent')
    path = parent / 'acceptance-ledger-hmac.key'
    if initialize:
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        except FileExistsError:
            pass
        else:
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(os.urandom(32)); stream.flush(); os.fsync(stream.fileno())
            directory = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(directory)
            finally: os.close(directory)
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        raise SupportFailure('persistent_hmac_key_missing_no_reinitialization')
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size != 32:
            raise SupportFailure('unsafe_hmac_key_file')
        key = stream.read(33)
    if len(key) != 32:
        raise SupportFailure('invalid_hmac_key_length')
    return key

class SessionLedger:
    """One payment batch's signed, private, atomic recovery record."""
    DOMAIN = b'print-payment-acceptance-ledger:v2\x00'
    FIXTURE_FIELDS = {'tenant','store','user','role','extra_store','identities','permission_menus'}
    def __init__(self, root, run_id, marker, script_sha256):
        self.root = root; self.path = root / 'private/sessions.json'
        self.key = ledger_hmac_key(root.parents[2], initialize=False)
        self.body = {'schema_version':2,'run_id':run_id,'fixture_marker':marker,
            'script_sha256':script_sha256,'created_utc':utc(),'sessions':[],
            'fixtures':{'tenant':None,'store':None,'user':None,'role':None,'extra_store':None,'identities':[],'permission_menus':{}},
            'baseline_entries':[],'cache_baseline_complete':False,
            'cleanup':{'sessions_verified':False,'caches_verified':False,'sql_verified':False}}
        self.last_sha256 = None
        self.path.parent.mkdir(mode=0o700)
        self.persist()

    @staticmethod
    def canonical(body):
        return json.dumps(body,separators=(',',':'),sort_keys=True,ensure_ascii=True).encode()

    @classmethod
    def candidate_keys(cls, body):
        fixtures=body['fixtures'];session_keys=set();cache_keys=set()
        for entry in body['sessions']:
            session_keys.update(('oauth2_access_token:'+entry['access'],'oauth2_access_token:'+entry['refresh']))
        for tenant,user in fixtures['identities']:cache_keys.add('user_role_ids:'+str(user))
        if fixtures['role'] is not None:cache_keys.add('role:'+str(fixtures['tenant'])+':'+str(fixtures['role']))
        for menu in fixtures['permission_menus'].values():cache_keys.add('menu_role_ids:'+str(fixtures['tenant'])+':'+str(menu))
        return session_keys,cache_keys

    @classmethod
    def validate_body(cls, body, run_id, marker, script_sha256):
        if set(body)!={'schema_version','run_id','fixture_marker','script_sha256','created_utc','sessions','fixtures','baseline_entries','cache_baseline_complete','cleanup'} or body['schema_version']!=2:
            raise SupportFailure('ledger_schema_mismatch')
        if body['run_id']!=run_id or body['fixture_marker']!=marker or body['script_sha256']!=script_sha256 or not re.fullmatch('[a-f0-9]{32}',run_id) or not re.fullmatch('[A-Za-z0-9]{4,30}',marker) or not re.fullmatch('[a-f0-9]{64}',script_sha256):
            raise SupportFailure('ledger_invalid_batch_identity')
        if datetime.datetime.fromisoformat(body['created_utc']).tzinfo is None:raise SupportFailure('ledger_timestamp_must_have_timezone')
        fixtures=body['fixtures']
        if set(fixtures)!=cls.FIXTURE_FIELDS:raise SupportFailure('ledger_fixture_schema_mismatch')
        for name in ('tenant','store','user','role','extra_store'):
            if fixtures[name] is not None and (type(fixtures[name]) is not int or fixtures[name]<=0):raise SupportFailure('ledger_invalid_fixture_identifier')
        if fixtures['tenant'] is not None and fixtures['tenant']==1:raise SupportFailure('ledger_fixture_tenant_must_be_generated')
        if not isinstance(fixtures['identities'],list) or len(fixtures['identities'])>2:raise SupportFailure('ledger_identity_limit')
        for pair in fixtures['identities']:
            if not isinstance(pair,list) or len(pair)!=2 or any(type(v)is not int or v<=0 for v in pair):raise SupportFailure('ledger_invalid_fixture_identity')
            if pair[0] not in (1,fixtures['tenant']):raise SupportFailure('ledger_unbound_fixture_identity')
            if pair[0]==fixtures['tenant'] and pair[1]!=fixtures['user']:raise SupportFailure('ledger_fixture_user_mismatch')
        menus=fixtures['permission_menus']
        if not isinstance(menus,dict) or not set(menus).issubset(REQUIRED_PERMISSIONS) or any(type(v)is not int or v<=0 for v in menus.values()) or (menus and fixtures['tenant'] is None):raise SupportFailure('ledger_invalid_menu_identifiers')
        if not isinstance(body['sessions'],list) or len(body['sessions'])>10:raise SupportFailure('ledger_session_limit')
        unique=set()
        for entry in body['sessions']:
            if set(entry)!={'access','refresh','tenant','user','label','baseline_verified','cleanup_verified'}:raise SupportFailure('ledger_session_schema_mismatch')
            if type(entry['tenant'])is not int or type(entry['user'])is not int or entry['tenant']<=0 or entry['user']<=0:raise SupportFailure('ledger_invalid_expected_identity')
            if entry['label'] not in ('admin','reader','same_owner','other_scope') or any(type(entry[k])is not bool for k in ('baseline_verified','cleanup_verified')):raise SupportFailure('ledger_invalid_session_state')
            if entry['label'] in ('admin','reader') and entry['tenant']!=1:raise SupportFailure('ledger_default_identity_tenant_mismatch')
            if entry['label']=='same_owner' and [entry['tenant'],entry['user']] not in fixtures['identities']:raise SupportFailure('ledger_same_owner_not_owned_fixture')
            if entry['label']=='other_scope' and (entry['tenant'],entry['user'])!=(fixtures['tenant'],fixtures['user']):raise SupportFailure('ledger_other_scope_identity_mismatch')
            if entry['label'] in ('admin','reader') and any(pair[1]==entry['user'] for pair in fixtures['identities']):raise SupportFailure('ledger_official_user_cannot_be_fixture')
            if any(not isinstance(entry[k],str) or not re.fullmatch('[A-Za-z0-9_-]{16,200}',entry[k]) for k in ('access','refresh')) or entry['access'] in unique:raise SupportFailure('ledger_invalid_or_duplicate_token')
            unique.add(entry['access'])
        if type(body['cache_baseline_complete'])is not bool or set(body['cleanup'])!={'sessions_verified','caches_verified','sql_verified'} or any(type(v)is not bool for v in body['cleanup'].values()):raise SupportFailure('ledger_invalid_cleanup_state')
        sessions,caches=cls.candidate_keys(body)
        seen=set()
        for entry in body['baseline_entries']:
            if set(entry)!={'key','category','ever_existed','required','required_baseline_seen','removed_by_logout','removed_by_explicit_delete','final_remaining'}:raise SupportFailure('ledger_baseline_schema_mismatch')
            allowed=sessions if entry['category']=='sessions' else caches if entry['category']=='fixture_caches' else set()
            if entry['key'] not in allowed or entry['key'] in seen:raise SupportFailure('ledger_baseline_key_not_derived_from_owned_identifiers')
            if any(type(entry[k])is not bool for k in ('ever_existed','required','required_baseline_seen','removed_by_logout','removed_by_explicit_delete')) or entry['final_remaining'] not in (None,0,1):raise SupportFailure('ledger_invalid_baseline_state')
            seen.add(entry['key'])

    @staticmethod
    def safe_chain(path, base):
        path=pathlib.Path(path);base=pathlib.Path(base).resolve()
        if not path.is_relative_to(base/'runtime/acceptance'):raise SupportFailure('ledger_path_outside_acceptance')
        for item in (base/'runtime/acceptance',path.parent.parent,path.parent):
            info=item.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o700:raise SupportFailure('ledger_unsafe_parent_directory')
        if path.exists() or path.is_symlink():
            info=path.lstat()
            if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600:raise SupportFailure('ledger_unsafe_file')

    def persist(self):
        self.validate_body(self.body,self.body['run_id'],self.body['fixture_marker'],self.body['script_sha256'])
        if self.root.name!='payment-http-'+self.body['run_id']:raise SupportFailure('ledger_root_run_mismatch')
        self.safe_chain(self.path,self.root.parents[2])
        if self.last_sha256 is not None and (not self.path.exists() or digest(self.path)!=self.last_sha256):raise SupportFailure('ledger_modified_outside_this_process')
        if not hmac.compare_digest(ledger_hmac_key(self.root.parents[2],initialize=False),self.key):raise SupportFailure('persistent_hmac_key_changed')
        signature=hmac.new(self.key,self.DOMAIN+self.canonical(self.body),hashlib.sha256).hexdigest()
        envelope={'format':'payment-ledger-hmac-v2','payload':self.body,'hmac_sha256':signature}
        temporary=self.path.with_name('.sessions-'+uuid.uuid4().hex+'.tmp')
        descriptor=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        try:
            with os.fdopen(descriptor,'wb') as stream:
                stream.write(self.canonical(envelope)+b'\n');stream.flush();os.fsync(stream.fileno())
            os.replace(temporary,self.path)
            directory=os.open(self.path.parent,os.O_RDONLY|os.O_DIRECTORY)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if temporary.exists():temporary.unlink()
        self.last_sha256=digest(self.path)

    def append(self,entry):self.body['sessions'].append(dict(entry));self.persist()
    def update(self,entry):
        for index,current in enumerate(self.body['sessions']):
            if current['access']==entry['access']:self.body['sessions'][index]=dict(entry);self.persist();return
        raise SupportFailure('session_not_in_owned_ledger')
    def remove_verified(self):
        if not all(self.body['cleanup'].values()) or not self.body['cache_baseline_complete'] or any(not v['baseline_verified'] or not v['cleanup_verified'] for v in self.body['sessions']):raise SupportFailure('ledger_has_unverified_session_cache_or_sql')
        self.safe_chain(self.path,self.root.parents[2])
        if digest(self.path)!=self.last_sha256:raise SupportFailure('ledger_modified_before_removal')
        self.path.unlink();self.path.parent.rmdir()

    @classmethod
    def recover(cls,base,path,*,run_id):
        base=pathlib.Path(base).resolve();path=pathlib.Path(path)
        if not re.fullmatch('[a-f0-9]{32}',run_id) or path!=base/'runtime/acceptance'/('payment-http-'+run_id)/'private/sessions.json':raise SupportFailure('ledger_recovery_path_not_bound_to_run')
        cls.safe_chain(path,base)
        key=ledger_hmac_key(base,initialize=False)
        descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(descriptor,'rb') as stream:
            info=os.fstat(stream.fileno())
            if info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600:raise SupportFailure('ledger_unsafe_open_file')
            raw=stream.read(131073)
        if len(raw)>131072:raise SupportFailure('ledger_too_large')
        envelope=json.loads(raw)
        if set(envelope)!={'format','payload','hmac_sha256'} or envelope['format']!='payment-ledger-hmac-v2' or not isinstance(envelope['hmac_sha256'],str):raise SupportFailure('ledger_signed_envelope_schema_mismatch')
        expected=hmac.new(key,cls.DOMAIN+cls.canonical(envelope['payload']),hashlib.sha256).hexdigest()
        # Authenticate the entire payload BEFORE interpreting any fixture/session identifiers.
        if not hmac.compare_digest(expected,envelope['hmac_sha256']):raise SupportFailure('ledger_hmac_authentication_failed')
        body=envelope['payload'];cls.validate_body(body,run_id,body['fixture_marker'],body['script_sha256'])
        value=object.__new__(cls);value.root=path.parent.parent;value.path=path;value.key=key;value.body=body;value.last_sha256=hashlib.sha256(raw).hexdigest()
        return value

class ExactRedisEvidence:
    """Observe only owned exact keys; public summaries contain counts, never keys."""
    def __init__(self):
        self.entries = {}

    @staticmethod
    def numbers(commands):
        for command, key in commands:
            if command not in ('EXISTS', 'DEL') or not re.fullmatch('[A-Za-z0-9_:-]{1,300}', key):
                raise SupportFailure('unsafe_exact_redis_operation')
        if not commands:
            return []
        # Actual cloud configuration uses loopback Redis database 0. A wrong
        # endpoint/database/prefix cannot pass required access-key baselines.
        text = ''.join(command + ' ' + key + '\n' for command, key in commands)
        result = authenticated_redis([], input_text=text)
        values = result.stdout.splitlines()
        if result.returncode or len(values) != len(commands) or any(value not in ('0', '1') for value in values):
            raise SupportFailure('exact_redis_command_failed')
        return [int(value) for value in values]

    def observe(self, keys, category, required=()):
        required = set(required)
        values = self.numbers([('EXISTS', key) for key in keys])
        missing = False
        for key, value in zip(keys, values):
            entry = self.entries.setdefault(key, {'category': category, 'ever_existed': False,
                'required': False, 'required_baseline_seen': False, 'removed_by_logout': False,
                'removed_by_explicit_delete': False, 'final_remaining': None})
            if entry['category'] != category:
                raise SupportFailure('redis_candidate_category_conflict')
            entry['ever_existed'] = entry['ever_existed'] or bool(value)
            if key in required:
                entry['required'] = True
                entry['required_baseline_seen'] = entry['required_baseline_seen'] or bool(value)
                missing = missing or not value
        if missing:
            raise SupportFailure('required_' + category + '_redis_baseline_missing')
        return values

    def remove(self, keys, category, logout=None):
        # Re-observe before the action, so earlier expiry is never counted as deletion.
        before = self.observe(keys, category)
        if logout is not None:
            logout()
        after_action = self.observe(keys, category)
        for key, was, now in zip(keys, before, after_action):
            if was and not now and logout is not None:
                self.entries[key]['removed_by_logout'] = True
        removed = self.numbers([('DEL', key) for key in keys])
        remaining = self.numbers([('EXISTS', key) for key in keys])
        for key, count, final in zip(keys, removed, remaining):
            self.entries[key]['removed_by_explicit_delete'] |= bool(count)
            self.entries[key]['final_remaining'] = final
        if any(remaining):
            raise SupportFailure('owned_redis_keys_remaining')

    def summary(self, category):
        keys = [key for key, value in self.entries.items() if value['category'] == category]
        remaining = self.numbers([('EXISTS', key) for key in keys])
        for key, value in zip(keys, remaining):
            self.entries[key]['final_remaining'] = value
        entries = [self.entries[key] for key in keys]
        result = {'candidate_count': len(entries),
            'actual_existed': sum(value['ever_existed'] for value in entries),
            'actual_deleted': sum(value['removed_by_logout'] or value['removed_by_explicit_delete'] for value in entries),
            'removed_by_logout': sum(value['removed_by_logout'] for value in entries),
            'removed_by_explicit_delete': sum(value['removed_by_explicit_delete'] for value in entries),
            'final_remaining': sum(remaining),
            'not_generated': sum(not value['ever_existed'] for value in entries),
            'required_baselines_verified': sum(value['required'] and value['required_baseline_seen'] for value in entries)}
        result['status'] = 'NOT_GENERATED' if result['actual_existed'] == 0 else 'VERIFIED'
        if any(value['required'] and not value['required_baseline_seen'] for value in entries):
            raise SupportFailure('required_' + category + '_redis_baseline_was_never_present')
        if any(remaining):
            raise SupportFailure('owned_redis_summary_has_remaining_keys')
        return result

class Fixtures:
    """Only generated identifiers and exact marker-owned fixtures are deleted."""
    def __init__(self, base, sql, marker, ledger=None):
        if not re.fullmatch('[A-Za-z0-9]{4,30}', marker):
            raise SupportFailure('fixture_username_must_be_alphanumeric')
        self.base, self.sql, self.marker = base, sql, marker
        self.ledger = ledger
        self.recovery_mode = False
        self.tenant = self.store = self.user = self.role = None
        self.identities = []
        self.business = {'orders': [], 'quotes': [], 'profiles': [], 'files': {}}
        self.sessions = []
        self.extra_store = None
        self.menu_ids = []
        self.permission_menus = {}
        self.redis = ExactRedisEvidence()
        self.cleaned = {}
        self.cache_baseline_complete = False

    def sync_ledger(self):
        if self.ledger is None:return
        self.ledger.body['fixtures']={'tenant':self.tenant,'store':self.store,'user':self.user,
            'role':self.role,'extra_store':self.extra_store,'identities':[list(pair) for pair in self.identities],
            'permission_menus':dict(self.permission_menus)}
        self.ledger.body['baseline_entries']=[dict(value,key=key) for key,value in sorted(self.redis.entries.items())]
        self.ledger.body['cache_baseline_complete']=self.cache_baseline_complete
        self.ledger.persist()

    def inserted(self, statement):
        identifier = int(self.sql(statement + '; SELECT LAST_INSERT_ID();'))
        if identifier <= 0:
            raise SupportFailure('fixture_insert_missing_id')
        return identifier

    def validate_permission_fixture(self):
        """Read-only prerequisite gate; no fixture INSERT may precede it."""
        package_rows=self.sql('SELECT status,CAST(deleted AS UNSIGNED),menu_ids FROM system_tenant_package WHERE id=1;').splitlines()
        if len(package_rows)!=1:raise SupportFailure('fixture_package_not_unique')
        package=package_rows[0].split('\t',2)
        if len(package)!=3 or package[:2]!=['0','0']:raise SupportFailure('fixture_package_not_enabled')
        allowed=json.loads(package[2])
        if not isinstance(allowed,list) or any(type(value)is not int for value in allowed):raise SupportFailure('fixture_package_menu_ids_invalid')
        allowed=set(allowed);nodes={};permission_menus={}
        def parse(rows):
            if len(rows)!=1:raise SupportFailure('fixture_menu_not_unique_or_missing')
            fields=rows[0].split('\t')
            if len(fields)!=6:raise SupportFailure('fixture_menu_metadata_invalid')
            node={'id':int(fields[0]),'parent':int(fields[1]),'permission':fields[2],'type':int(fields[3]),'status':int(fields[4]),'deleted':int(fields[5])}
            if node['status']!=0 or node['deleted']!=0 or node['id'] not in allowed:raise SupportFailure('fixture_menu_disabled_deleted_or_outside_package')
            nodes[node['id']]=node;return node
        for permission in REQUIRED_PERMISSIONS:
            node=parse(self.sql("SELECT id,parent_id,permission,type,status,CAST(deleted AS UNSIGNED) FROM system_menu WHERE permission='"+permission+"';").splitlines())
            if node['permission']!=permission or node['type']!=3:raise SupportFailure('fixture_permission_leaf_invalid')
            permission_menus[permission]=node['id']
        if len(set(permission_menus.values()))!=7:raise SupportFailure('fixture_permission_leaf_ids_not_unique')
        for identifier in list(permission_menus.values()):
            seen=set();current=identifier
            while current!=0:
                if current in seen:raise SupportFailure('fixture_menu_ancestor_cycle')
                seen.add(current)
                node=nodes.get(current)
                if node is None:node=parse(self.sql('SELECT id,parent_id,permission,type,status,CAST(deleted AS UNSIGNED) FROM system_menu WHERE id='+str(current)+';').splitlines())
                if node['id']!=current:raise SupportFailure('fixture_menu_ancestor_identity_mismatch')
                current=node['parent']
        ancestor=nodes.get(2000)
        if len(nodes)!=8 or set(nodes)!=set(permission_menus.values())|{2000} or ancestor is None or ancestor['permission']!='' or ancestor['type']!=1 or ancestor['parent']!=0:
            raise SupportFailure('fixture_menu_closure_not_exact_seven_leaves_and_root')
        self.approved_permission_menus=permission_menus
        return sorted(nodes)

    def seed_scope(self, admin_id):
        grant_ids=self.validate_permission_fixture()
        m = self.marker
        self.tenant = self.inserted("INSERT INTO system_tenant(name,contact_name,status,websites,package_id,expire_time,account_count,creator,updater) VALUES('" + m + "','Payment scope fixture',0,'[]',1,'2099-12-31 23:59:59',10,'" + m + "','" + m + "')")
        t = str(self.tenant)
        self.store = self.inserted("INSERT INTO print_store(tenant_id,store_code,name,status,creator,updater) VALUES(" + t + ",'" + m + "','Payment other tenant fixture',0,'" + m + "','" + m + "')")
        self.user = self.inserted("INSERT INTO system_users(username,password,nickname,status,tenant_id,creator,updater) SELECT '" + m + "t',password,'Payment scope fixture',0," + t + ",'" + m + "','" + m + "' FROM system_users WHERE id=" + str(admin_id) + " AND tenant_id=1 AND deleted=0")
        self.identities.append((self.tenant, self.user))
        self.role = self.inserted("INSERT INTO system_role(name,code,sort,status,type,data_scope,data_scope_dept_ids,tenant_id,creator,updater) VALUES('Payment scope fixture','" + m + "',0,0,2,1,'[]'," + t + ",'" + m + "','" + m + "')")
        self.sql("INSERT INTO system_user_role(user_id,role_id,tenant_id,creator,updater) VALUES(" + str(self.user) + ',' + str(self.role) + ',' + t + ",'" + m + "','" + m + "'); INSERT INTO print_staff_store(tenant_id,store_id,user_id,status,creator,updater) VALUES(" + t + ',' + str(self.store) + ',' + str(self.user) + ",0,'" + m + "','" + m + "');")
        # Only this generated role receives the verified ancestor closure.
        self.sql("INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,updater) SELECT " + str(self.role) + ',id,' + t + ",'" + m + "','" + m + "' FROM system_menu WHERE id IN (" + ','.join(str(value) for value in grant_ids) + ');')
        self.permission_menus=dict(self.approved_permission_menus)
        self.menu_ids=list(self.permission_menus.values())
        self.sync_ledger()

    def register_login(self, payload, tenant, expected_user, label):
        entry = {'access': payload['accessToken'], 'refresh': payload['refreshToken'],
                 'tenant': tenant, 'user': expected_user, 'label': label,
                 'baseline_verified': False, 'cleanup_verified': False}
        # Track in memory before disk I/O; even a write failure gets exact logout in finally.
        if self.ledger is not None:
            candidate=dict(self.ledger.body);candidate['sessions']=list(self.ledger.body['sessions'])+[entry]
            SessionLedger.validate_body(candidate,candidate['run_id'],candidate['fixture_marker'],candidate['script_sha256'])
        self.sessions.append(entry)
        if self.ledger is None:
            raise SupportFailure('private_session_ledger_required')
        self.ledger.append(entry)
        self.capture_session(entry['access'], tenant)
        return entry['access']

    def capture_session(self, token, tenant):
        matching = [entry for entry in self.sessions if entry['access'] == token and entry['tenant'] == tenant]
        if len(matching) != 1:
            raise SupportFailure('login_response_not_safely_journaled')
        entry = matching[0]
        row = self.sql("SELECT CONCAT(user_id,'|',refresh_token) FROM system_oauth2_access_token WHERE access_token='" + token + "' AND tenant_id=" + str(tenant) + ' AND deleted=0;')
        if row != str(entry['user']) + '|' + entry['refresh']:
            raise SupportFailure('session_sql_identity_does_not_match_private_ledger')
        access_key = 'oauth2_access_token:' + token
        self.redis.observe([access_key, 'oauth2_access_token:' + entry['refresh']], 'sessions', required=[access_key])
        entry['baseline_verified'] = True
        self.ledger.update(entry)
        self.sync_ledger()

    def login_scope(self, get_data):
        credentials = json.loads((self.base / 'runtime/secrets/smoke-admin.json').read_text())
        payload = get_data(api('/system/auth/login', body={'username': self.marker + 't',
                        'password': credentials['password']}, tenant=self.tenant))
        return self.register_login(payload, self.tenant, self.user, 'other_scope')

    def own_order(self, token, get_data, pdf):
        t = self.tenant
        content = pdf(3, self.marker + uuid.uuid4().hex[:8])
        boundary = 'paymentScopeFixtureBoundary'
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="fixture.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode() + content + ('\r\n--' + boundary + '--\r\n').encode()
        value = get_data(api('/print/files', token, body, 'POST', 'multipart/form-data; boundary=' + boundary, str(uuid.uuid4()), tenant=t))
        identifier = value['id']; uuid.UUID(identifier)
        self.business['files'][identifier] = hashlib.sha256(content).hexdigest()
        until = time.monotonic() + 20
        while time.monotonic() < until:
            state = get_data(api('/print/files/' + identifier, token, tenant=t))
            if state['status'] == 'READY':
                break
            if state['status'] in ('FAILED', 'DELETED'):
                raise SupportFailure('scope_file_not_ready')
            time.sleep(.25)
        else:
            raise SupportFailure('scope_file_processing_timeout')
        profile = str(uuid.uuid4()); self.business['profiles'].append(profile)
        self.sql("INSERT INTO print_price_profile(id,tenant_id,store_id,profile_key,version,mode,currency,simplex_price_per_sheet,duplex_price_per_sheet,tier_json,created_at_ms) VALUES('" + profile + "'," + str(t) + ',' + str(self.store) + ",'" + self.marker + "','v1-" + profile + "','DEMO','CNY','0.20','0.20',NULL,1);")
        quote = get_data(api('/print/quotes', token, body={'fileId': identifier, 'priceProfileId': profile, 'copies': 2, 'side': 'DUPLEX'}, key=str(uuid.uuid4()), tenant=t))['id']
        uuid.UUID(quote); self.business['quotes'].append(quote)
        order = get_data(api('/print/orders', token, body={'quoteId': quote}, key=str(uuid.uuid4()), tenant=t))
        uuid.UUID(order['id']); self.business['orders'].append(order['id'])
        return order

    def move_fixture_owner_store(self, user):
        m = self.marker
        self.extra_store = self.inserted("INSERT INTO print_store(tenant_id,store_code,name,status,creator,updater) VALUES(1,'" + m + "','Payment same tenant other store',0,'" + m + "','" + m + "')")
        where = "tenant_id=1 AND user_id=" + str(user) + " AND creator='" + m + "' AND deleted=0"
        if self.sql('SELECT COUNT(*) FROM print_staff_store WHERE ' + where + ';') != '1':
            raise SupportFailure('fixture_owner_requires_one_mapping')
        self.sql('UPDATE print_staff_store SET store_id=' + str(self.extra_store) + ' WHERE ' + where + ';')
        self.sync_ledger()
        return self.extra_store

    def cache_candidates(self):
        # user_role_ids is excluded from tenant suffixing; role/menu_role_ids are not.
        keys = ['user_role_ids:' + str(user) for _, user in self.identities]
        required = set(keys)
        if self.role is not None:
            key = 'role:' + str(self.tenant) + ':' + str(self.role)
            keys.append(key); required.add(key)
        for permission, menu in self.permission_menus.items():
            key = 'menu_role_ids:' + str(self.tenant) + ':' + str(menu)
            keys.append(key)
            # own_order performs quote creation, not the optional quote-query call.
            if permission != 'print:quote:query':
                required.add(key)
        return keys, required

    def capture_fixture_caches(self, required):
        keys, expected = self.cache_candidates()
        try:
            self.redis.observe(keys, 'fixture_caches', required=expected if required else ())
            if required:self.cache_baseline_complete=True
        finally:
            self.sync_ledger()

    def cleanup_caches(self):
        keys,_=self.cache_candidates();verified=False
        try:
            self.redis.remove(keys,'fixture_caches')
            summary=self.redis.summary('fixture_caches')
            verified=not any(self.redis.numbers([('EXISTS',key) for key in keys]))
            if not self.cache_baseline_complete:summary['status']='UNVERIFIED'
            self.cleaned['redis_fixture_caches']=summary
        except Exception:
            self.cleaned['redis_fixture_caches']={'status':'UNVERIFIED'}
            raise
        finally:
            if self.ledger is not None:self.ledger.body['cleanup']['caches_verified']=verified
            self.sync_ledger()
        if not self.cache_baseline_complete and not self.recovery_mode:
            raise SupportFailure('fixture_cache_baseline_unverified')

    def cleanup_sessions(self):
        errors = []; verified_pairs = 0; logout_attempts = 0
        for session in self.sessions:
            t, u = session['tenant'], session['user']
            keys = ['oauth2_access_token:' + session['access'], 'oauth2_access_token:' + session['refresh']]
            # Baseline measurement failure must never suppress the logout attempt.
            try:
                before = self.redis.observe(keys, 'sessions')
            except Exception:
                before = None
            logout_attempts += 1
            try:
                api('/system/auth/logout', session['access'], body={}, tenant=t, port=48080)
            except Exception:
                pass
            redis_verified = database_verified = False
            try:
                after = self.redis.observe(keys, 'sessions')
                if before is not None:
                    for key, old, new in zip(keys, before, after):
                        if old and not new: self.redis.entries[key]['removed_by_logout'] = True
                self.redis.remove(keys, 'sessions')
                redis_verified = not any(self.redis.numbers([('EXISTS', key) for key in keys]))
            except Exception:
                errors.append('owned_session_redis_cleanup_failed')
            try:
                self.sql("DELETE FROM system_oauth2_access_token WHERE access_token='" + session['access'] + "' AND tenant_id=" + str(t) + ' AND user_id=' + str(u) + "; DELETE FROM system_oauth2_refresh_token WHERE refresh_token='" + session['refresh'] + "' AND tenant_id=" + str(t) + ' AND user_id=' + str(u) + ';')
                for table, column, value in [('system_oauth2_access_token', 'access_token', session['access']), ('system_oauth2_refresh_token', 'refresh_token', session['refresh'])]:
                    if self.sql('SELECT COUNT(*) FROM ' + table + ' WHERE ' + column + "='" + value + "' AND tenant_id=" + str(t) + ';') != '0':
                        raise SupportFailure('owned_session_rows_remaining')
                database_verified = True
            except Exception:
                errors.append('owned_session_database_cleanup_failed')
            session['cleanup_verified'] = redis_verified and database_verified
            if session['cleanup_verified']: verified_pairs += 1
            if self.ledger is not None:
                try: self.ledger.update(session)
                except Exception: errors.append('private_ledger_cleanup_update_failed')
        try:
            summary = self.redis.summary('sessions')
            if any(not session['baseline_verified'] for session in self.sessions):
                summary['status'] = 'UNVERIFIED'
            self.cleaned['redis_sessions'] = summary
        except Exception:
            self.cleaned['redis_sessions'] = {'status': 'UNVERIFIED'}
            errors.append('owned_session_redis_evidence_failed')
        self.cleaned['owned_session_pairs_removed'] = verified_pairs
        self.cleaned['logout_attempts'] = logout_attempts
        self.cleaned['session_cleanup_errors'] = errors
        if self.ledger is not None:self.ledger.body['cleanup']['sessions_verified']=verified_pairs==len(self.sessions) and not errors
        self.sync_ledger()
        if any(not session['baseline_verified'] for session in self.sessions) and not self.recovery_mode:errors.append('session_baseline_unverified')
        if errors or verified_pairs != len(self.sessions):
            raise SupportFailure('owned_session_cleanup_failed')

    def cleanup_business(self):
        if self.tenant is None:
            return
        t = str(self.tenant)
        for identifier in self.business['orders']:
            for table in BUSINESS_TABLES[:5]:
                self.sql("DELETE FROM " + table + " WHERE order_id='" + identifier + "' AND tenant_id=" + t + ';')
            self.sql("DELETE FROM print_order WHERE id='" + identifier + "' AND tenant_id=" + t + ';')
        for identifier in self.business['quotes']:
            self.sql("DELETE FROM print_quote WHERE id='" + identifier + "' AND tenant_id=" + t + ';')
        for identifier in self.business['profiles']:
            self.sql("DELETE FROM print_price_profile WHERE id='" + identifier + "' AND profile_key='" + self.marker + "' AND tenant_id=" + t + ';')
        for identifier, sha in self.business['files'].items():
            self.sql("DELETE FROM print_file WHERE id='" + identifier + "' AND content_sha256='" + sha + "' AND tenant_id=" + t + ';')

    def cleanup_identities(self):
        m = self.marker
        if self.user is not None:
            t = str(self.tenant); u = str(self.user)
            for table in ('print_staff_store', 'system_user_role'):
                self.sql('DELETE FROM ' + table + ' WHERE tenant_id=' + t + ' AND user_id=' + u + " AND creator='" + m + "';")
            self.sql('DELETE FROM system_users WHERE tenant_id=' + t + ' AND id=' + u + " AND username='" + m + "t' AND creator='" + m + "';")
        if self.role is not None:
            self.sql('DELETE FROM system_role_menu WHERE tenant_id=' + str(self.tenant) + ' AND role_id=' + str(self.role) + " AND creator='" + m + "'; DELETE FROM system_role WHERE tenant_id=" + str(self.tenant) + ' AND id=' + str(self.role) + " AND code='" + m + "' AND creator='" + m + "';")
        for store, tenant in ((self.store, self.tenant), (self.extra_store, 1)):
            if store is not None:
                self.sql('DELETE FROM print_store WHERE id=' + str(store) + ' AND tenant_id=' + str(tenant) + " AND creator='" + m + "';")
        if self.tenant is not None:
            self.sql('DELETE FROM system_tenant WHERE id=' + str(self.tenant) + " AND name='" + m + "' AND creator='" + m + "';")
        counts = {}
        for table in ('system_tenant', 'print_store', 'system_users', 'system_role',
                      'system_role_menu', 'system_user_role', 'print_staff_store'):
            counts[table] = int(self.sql('SELECT COUNT(*) FROM ' + table + " WHERE creator='" + m + "';"))
        for t, u in self.identities:
            for table in ('system_oauth2_access_token', 'system_oauth2_refresh_token'):
                counts[table + '_fixture_user_' + str(t) + '_' + str(u)] = int(self.sql('SELECT COUNT(*) FROM ' + table + ' WHERE tenant_id=' + str(t) + ' AND user_id=' + str(u) + ';'))
        self.cleaned['identity_rows_remaining'] = counts
        if any(counts.values()):
            raise SupportFailure('fixture_identity_or_session_rows_remaining')
        if self.ledger is not None:self.ledger.body['cleanup']['sql_verified']=True
        self.sync_ledger()



def verify_fixture_sql_absent(fixture):
    fixture.ledger.body['cleanup']['sql_verified']=False
    fixture.sync_ledger()
    counts={}
    for table in ('system_tenant','print_store','system_users','system_role','system_role_menu','system_user_role','print_staff_store'):
        counts[table]=int(fixture.sql('SELECT COUNT(*) FROM '+table+" WHERE creator='"+fixture.marker+"';"))
    for tenant,user in fixture.identities:
        for table in ('system_oauth2_access_token','system_oauth2_refresh_token'):
            counts[table+'_'+str(tenant)+'_'+str(user)]=int(fixture.sql('SELECT COUNT(*) FROM '+table+' WHERE tenant_id='+str(tenant)+' AND user_id='+str(user)+';'))
    fixture.cleaned['identity_rows_remaining']=counts
    if any(counts.values()):raise SupportFailure('signed_fixture_sql_rows_remaining')
    fixture.ledger.body['cleanup']['sql_verified']=True
    fixture.sync_ledger()


def recover_owned_sessions(base, *, run_id, sql):
    """Only a signed fixed-batch record and the fixed external key authorize recovery."""
    base=pathlib.Path(base).resolve()
    with fixture_lock(base):
        redis_auth_preflight(run_id)
        path=base/'runtime/acceptance'/('payment-http-'+run_id)/'private/sessions.json'
        ledger=SessionLedger.recover(base,path,run_id=run_id)
        ledger.body['cleanup']={'sessions_verified':False,'caches_verified':False,'sql_verified':False};ledger.persist()
        fixture=Fixtures(base,sql,ledger.body['fixture_marker'],ledger=ledger)
        fixture.recovery_mode=True
        for name,value in ledger.body['fixtures'].items():
            if name=='identities':fixture.identities=[tuple(pair) for pair in value]
            else:setattr(fixture,name,value)
        fixture.menu_ids=list(fixture.permission_menus.values())
        fixture.sessions=[dict(value) for value in ledger.body['sessions']]
        fixture.cache_baseline_complete=ledger.body['cache_baseline_complete']
        fixture.redis.entries={value['key']:{k:v for k,v in value.items() if k!='key'} for value in ledger.body['baseline_entries']}
        errors=[]
        for operation in (fixture.cleanup_sessions,fixture.cleanup_caches,lambda:verify_fixture_sql_absent(fixture)):
            try:operation()
            except Exception:errors.append('owned_recovery_stage_unverified')
        absence=all(ledger.body['cleanup'].values()) and not errors
        baseline=fixture.cache_baseline_complete and all(session['baseline_verified'] for session in fixture.sessions)
        return {'status':'EXACT_OWNED_CLEANUP_VERIFIED' if absence and baseline else 'UNVERIFIED',
            'cleanup_absence_verified':absence,'baseline_verified':baseline,'acceptance_status':'NOT_RUN',
            'sessions_count':len(fixture.sessions),'cleanup':fixture.cleaned,'errors':errors,
            'ledger_sha256':ledger.last_sha256,'ledger_retained':ledger.path.exists()}
