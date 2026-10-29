"""Unified v3 runtime ports. Imports and constructors perform no IO.

There is deliberately no executable main and no connection to the apply CLI.
Every effect requires the caller's held lease and a separately reviewed plan.
"""
import copy
import hashlib
import http.client
import io
import json
import os
import pathlib
import re
import shutil
import stat
import subprocess
import tarfile
import time
import uuid
from html.parser import HTMLParser
from ordinary_activation_common import require, validate_ref, digest, canon, strict_json, BASE

COMMANDS = {
    ('systemctl', action, unit)
    for action in ('start', 'stop')
    for unit in ('nginx.service', 'print-backend.service')
}
STATIC_TOP = {'index.html', 'favicon.ico', 'logo.gif', 'assets'}
MIN_FREE = 10 * 1024**3


def safe_path(value):
    path = pathlib.Path(value)
    require(path.is_absolute() and '..' not in path.parts, 'absolute_path_required')
    require(all(not p.is_symlink() for p in (path, *path.parents)), 'symlink_path_forbidden')
    return path


def file_bytes(value, limit=1024**3, private=False):
    path = safe_path(value)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size <= limit,
                'single_finite_regular_file_required')
        if private:
            require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o600,
                    'private_owner_mode_required')
        return stream.read()


def fsync_dir(path):
    fd = os.open(safe_path(path), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def exclusive(path, raw, mode=0o600):
    path = safe_path(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    fsync_dir(path.parent)


def static_name(value):
    require(isinstance(value, str) and value and '\\' not in value and
            all(ord(c) >= 32 for c in value), 'invalid_static_name')
    path = pathlib.PurePosixPath(value)
    require(not path.is_absolute() and path.as_posix() == value and
            all(p not in ('.', '..') for p in path.parts) and path.parts[0] in STATIC_TOP,
            'static_path_escape')
    require((path.parts[0] == 'assets' and len(path.parts) > 1) or
            (len(path.parts) == 1 and value != 'assets'), 'invalid_static_file')
    return value


def inventory(root):
    root = safe_path(root)
    require(root.is_dir(), 'static_root_required')
    rows = {}
    for current, dirs, files in os.walk(root, followlinks=False):
        for name in dirs:
            require(safe_path(pathlib.Path(current) / name).is_dir(), 'static_directory_required')
        for name in files:
            path = pathlib.Path(current) / name
            relative = static_name(path.relative_to(root).as_posix())
            raw = file_bytes(path)
            rows[relative] = {'path': relative, 'size': len(raw), 'sha256': digest(raw)}
    require('index.html' in rows, 'static_index_required')
    return dict(sorted(rows.items()))


def manifest_rows(raw):
    value = strict_json(raw)
    values = value.get('files') if isinstance(value, dict) else None
    require(isinstance(values, list) and values, 'complete_manifest_files_required')
    rows = {}
    for row in values:
        require(isinstance(row, dict) and set(row) == {'path', 'size', 'sha256'},
                'exact_manifest_member_required')
        name = static_name(row['path'])
        require(name not in rows and type(row['size']) is int and row['size'] >= 0 and
                re.fullmatch('[0-9a-f]{64}', row['sha256']) is not None,
                'invalid_manifest_member')
        rows[name] = row
    require('index.html' in rows, 'manifest_index_required')
    return dict(sorted(rows.items()))


class References(HTMLParser):
    def __init__(self):
        super().__init__()
        self.values = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'script' and attrs.get('src'):
            self.values.append(attrs['src'])
        if tag == 'link' and attrs.get('href') and set(attrs.get('rel', '').split()) & {
                'stylesheet', 'modulepreload', 'preload', 'icon'}:
            self.values.append(attrs['href'])


def verify_references(raw, rows):
    parser = References()
    parser.feed(raw.decode('utf-8'))
    require(parser.values, 'index_references_required')
    for name in parser.values:
        require(not name.startswith(('http:', 'https:', '//', 'data:')) and
                '?' not in name and '#' not in name, 'nonlocal_index_reference')
        require(static_name(name.lstrip('/')) in rows, 'missing_index_reference')


class FixedRuntimeRunner:
    """Concrete fixed commands/files; DB counts and effective gate need explicit proof ports.

    Those proof ports receive no command text and must return typed public evidence.
    A missing port fails closed, so construction never creates a misleading live runner.
    """
    def __init__(self, plan, database_connections=None, gate_evidence=None, execute=None,
                 process_reader=None, ingress_exchange=None):
        self.plan = copy.deepcopy(plan)
        self.database_connections = database_connections
        self.gate_evidence = gate_evidence
        self.execute = execute
        self.process_reader = process_reader
        self.ingress_exchange = ingress_exchange
        self.lease = None
        self.prepared = False
        self.selected = False
        self.installs = set()

    def previous_backend_exited(self):
        self.guard()
        old = self.plan['runtimeBefore']
        path = pathlib.Path('/proc/' + str(old['backendPid']) + '/stat')
        if not path.exists():
            return True
        ticks = path.read_text().rsplit(')', 1)[1].split()[19]
        return ticks != old['backendStartTicks']

    def backend_health(self):
        self.guard()
        connection = http.client.HTTPConnection('127.0.0.1', 48080, timeout=5)
        try:
            connection.request('GET', '/actuator/health')
            response = connection.getresponse()
            raw = response.read(65537)
            require(len(raw) <= 65536, 'health_response_too_large')
            return response.status == 200 and strict_json(raw).get('status') == 'UP'
        except (OSError, ValueError):
            return False
        finally:
            connection.close()

    def bind(self, lease):
        self.lease = lease

    def guard(self):
        require(self.lease is not None, 'lock_first_required')
        self.lease.check()

    def run(self, argv):
        self.guard()
        read_commands = {
            ('systemctl', 'show', unit, '--property=ActiveState,SubState,MainPID', '--no-pager')
            for unit in ('nginx.service', 'print-backend.service')
        } | {('systemctl', 'cat', 'print-backend.service'),
             ('systemctl', 'show', 'print-backend.service',
              '--property=FragmentPath,DropInPaths', '--no-pager'),
             ('sudo', '-n', 'nginx', '-T'),
             ('sudo', '-n', 'ss', '-H', '-ltnp'),
             ('sudo', '-n', 'ss', '-H', '-tnp', 'state', 'established', '( sport = :18080 )')}
        require(tuple(argv) in read_commands or
                (tuple(argv[:2]) == ('sudo', '-n') and tuple(argv[2:]) in COMMANDS),
                'fixed_command_allowlist_required')
        if self.execute is not None:
            result = self.execute(tuple(argv))
            require(isinstance(result, bytes), 'command_bytes_required')
            return result
        result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=45)
        require(result.returncode == 0 and len(result.stdout) <= 4 * 1024**2,
                'fixed_command_failed_or_unknown')
        return result.stdout

    def command(self, argv):
        require(tuple(argv) in COMMANDS, 'service_command_not_allowlisted')
        self.run(('sudo', '-n', *argv))
        return 'ACK'

    def unit(self, name):
        raw = self.run(('systemctl', 'show', name,
                        '--property=ActiveState,SubState,MainPID', '--no-pager'))
        values = dict(line.split('=', 1) for line in raw.decode().splitlines())
        require(set(values) == {'ActiveState', 'SubState', 'MainPID'}, 'unit_snapshot_schema')
        pid = int(values['MainPID'])
        require(pid >= 0, 'invalid_service_pid')
        ticks = None
        if pid:
            ticks = pathlib.Path('/proc/' + str(pid) + '/stat').read_text().rsplit(')', 1)[1].split()[19]
        return values['ActiveState'] == 'active', pid, ticks

    def launch_binding(self):
        """Freeze the effective unit and every declared unit/drop-in/start-script byte."""
        contract = self.plan['runtimeContract']['backendLaunch']
        require(contract['fragmentPath'] == '/etc/systemd/system/print-backend.service' and
                contract['startScript'] == str(BASE / 'ops/runtime/start-backend.sh'),
                'backend_launch_paths_not_fixed')
        unit = self.run(('systemctl', 'cat', 'print-backend.service'))
        require(digest(unit) == contract['unitCatSha256'], 'backend_effective_unit_changed')
        locations = dict(line.split('=', 1) for line in self.run((
            'systemctl', 'show', 'print-backend.service',
            '--property=FragmentPath,DropInPaths', '--no-pager')).decode().splitlines())
        require(set(locations) == {'FragmentPath', 'DropInPaths'} and
                locations['FragmentPath'] == contract['fragmentPath'] and
                locations['DropInPaths'].split() == contract['dropInPaths'],
                'backend_unit_dropin_set_changed')
        expected = {contract['fragmentPath'], contract['startScript'], *contract['dropInPaths']}
        refs = contract['files']
        require(len(refs) == len(expected) and {row['path'] for row in refs} == expected,
                'backend_launch_files_incomplete')
        for ref in refs:
            require(digest(file_bytes(ref['path'])) == ref['sha256'], 'backend_launch_file_changed')
        return digest(canon(contract))

    def process_binding(self, pid, start_ticks, launch_sha):
        contract = self.plan['runtimeContract']['backendLaunch']
        if self.process_reader is not None:
            observed_ticks, argv = self.process_reader(pid)
        else:
            process = pathlib.Path('/proc') / str(pid)
            before = (process / 'stat').read_text().rsplit(')', 1)[1].split()[19]
            raw = (process / 'cmdline').read_bytes()
            require(raw.endswith(b'\0') and len(raw) <= 65536, 'backend_argv_frame_invalid')
            argv = [part.decode('utf-8') for part in raw[:-1].split(b'\0')]
            observed_ticks = (process / 'stat').read_text().rsplit(')', 1)[1].split()[19]
            require(before == observed_ticks, 'backend_pid_changed_during_observation')
        require(observed_ticks == start_ticks and isinstance(argv, list) and argv.count('-jar') == 1,
                'backend_process_identity_changed')
        index = argv.index('-jar')
        require(index + 1 < len(argv), 'backend_jar_argument_missing')
        jar = safe_path(argv[index + 1])
        require(jar.is_relative_to(BASE / 'runtime/releases') and jar.name == 'backend.jar' and
                str(jar.resolve(strict=True)) == str(jar), 'backend_jar_argument_not_physical_release')
        phase = next((phase for phase in ('before', 'after')
                      if argv == contract[phase]['argv'] and str(jar) == contract[phase]['jarPath']), None)
        require(phase is not None, 'backend_argv_not_frozen')
        jar_sha = digest(file_bytes(jar))
        require(jar_sha == contract[phase]['jarSha256'], 'backend_process_jar_changed')
        return {'pid': pid, 'startTicks': observed_ticks, 'argvSha256': digest(canon(argv)),
                'jarPath': str(jar), 'jarSha256': jar_sha, 'launchBindingSha256': launch_sha}

    def snapshot(self):
        self.guard()
        require(self.database_connections is not None and self.gate_evidence is not None,
                'runtime_proof_ports_not_configured')
        nginx, _, _ = self.unit('nginx.service')
        backend, pid, ticks = self.unit('print-backend.service')
        launch_sha = self.launch_binding()
        process = self.process_binding(pid, ticks, launch_sha) if pid else None
        listeners = []
        for line in self.run(('sudo', '-n', 'ss', '-H', '-ltnp')).decode().splitlines():
            fields = line.split()
            require(len(fields) >= 6, 'listener_identity_missing')
            host, port = fields[3].rsplit(':', 1)
            owners = re.findall(r'\("([^\"]+)",pid=(\d+)', line)
            require(owners, 'listener_owner_missing')
            listeners.append({'host': host.strip('[]'), 'port': int(port),
                              'processes': sorted({p for p, _ in owners})})
        baseline = self.plan['runtimeContract']['listenerAllowlist']
        require(all(row in baseline for row in listeners), 'unexpected_listener_or_proxy')
        pending = self.run(('sudo', '-n', 'ss', '-H', '-tnp', 'state', 'established',
                            '( sport = :18080 )'))
        connections = self.database_connections()
        require(type(connections) is int and connections >= 0, 'db_connection_count_required')
        gate = self.gate_evidence()
        require(isinstance(gate, dict) and set(gate) == {
            'effectiveValue', 'configSha256', 'sourceBindingSha256'}, 'effective_gate_proof_required')
        require(gate['effectiveValue'] is None or type(gate['effectiveValue']) is bool,
                'effective_gate_type')
        live = inventory('/var/www/print-system')
        wanted = manifest_rows(file_bytes(self.plan['release']['frontendManifest']['path']))
        manifest_sha = self.plan['release']['frontendManifest']['sha256'] if all(
            live.get(name) == row for name, row in wanted.items()) else digest(canon(live))
        return {'nginxActive': nginx, 'externalListeners': [row for row in listeners
                if row['port'] == 18080 or 'nginx' in row['processes']],
                'pendingRequests': len(pending.splitlines()), 'backendActive': backend,
                'backendPid': pid, 'backendStartTicks': ticks,
                'port48080': sorted(row['host'] for row in listeners if row['port'] == 48080),
                'dbConnections': connections, 'jarSha256': process['jarSha256'] if process else None,
                'backendProcess': process, 'backendLaunchBindingSha256': launch_sha,
                'frontendManifestSha256': manifest_sha, 'gateConfigSha256': gate['configSha256'],
                'gateSourceBindingSha256': gate['sourceBindingSha256'],
                'effectiveGate': gate['effectiveValue']}

    def ingress_get(self, path):
        self.guard()
        require(path == '/login' or path.startswith('/assets/') or path in
                ('/index.html', '/favicon.ico', '/logo.gif'), 'ingress_path_not_fixed')
        if self.ingress_exchange is not None:
            return self.ingress_exchange('127.0.0.1', 18080, path)
        connection = http.client.HTTPConnection('127.0.0.1', 18080, timeout=20)
        try:
            connection.request('GET', path)
            response = connection.getresponse()
            raw = response.read(16 * 1024**2 + 1)
            require(len(raw) <= 16 * 1024**2, 'ingress_resource_too_large')
            return {'status': response.status, 'raw': raw}
        finally:
            connection.close()

    def verify_ingress(self):
        self.guard()
        contract = self.plan['runtimeContract']['ingress']
        require(contract['host'] == '127.0.0.1' and contract['port'] == 18080 and
                contract['sitePath'] == '/etc/nginx/sites-available/print-system' and
                contract['root'] == '/var/www/print-system' and
                contract['proxyPass'] == 'http://127.0.0.1:48080', 'ingress_contract_not_fixed')
        state = self.snapshot()
        require(state['nginxActive'] is True and state['externalListeners'] == [
            {'host': '127.0.0.1', 'port': 18080, 'processes': ['nginx']}],
            'ingress_listener_not_exact')
        site = file_bytes(contract['sitePath'])
        effective = self.run(('sudo', '-n', 'nginx', '-T'))
        require(digest(site) == contract['siteSha256'] and
                digest(effective) == contract['nginxConfigSha256'], 'ingress_site_or_effective_config_changed')
        directives = '\n'.join(line.split('#', 1)[0] for line in site.decode().splitlines())
        require(set(re.findall(r'\broot\s+([^;{}]+);', directives)) == {contract['root']} and
                set(re.findall(r'\bproxy_pass\s+([^;{}]+);', directives)) == {contract['proxyPass']},
                'ingress_root_or_proxy_not_frozen')
        active_site = pathlib.Path('/etc/nginx/sites-enabled/print-system')
        require(active_site.is_symlink() and str(active_site.resolve(strict=True)) == contract['sitePath'],
                'ingress_enabled_site_changed')
        raw_manifest = file_bytes(self.plan['release']['frontendManifest']['path'])
        require(digest(raw_manifest) == self.plan['release']['frontendManifest']['sha256'],
                'ingress_manifest_bytes_changed')
        rows = manifest_rows(raw_manifest)
        live = inventory(contract['root'])
        require(all(live.get(name) == row for name, row in rows.items()), 'ingress_root_manifest_mismatch')
        index = file_bytes(pathlib.Path(contract['root']) / 'index.html')
        verify_references(index, rows)
        parser = References()
        parser.feed(index.decode('utf-8'))
        resources = sorted({static_name(name.lstrip('/')) for name in parser.values})
        require(resources == contract['indexResources'], 'ingress_resource_list_changed')
        for path, expected in [('/login', rows['index.html']), *[('/' + name, rows[name]) for name in resources]]:
            reply = self.ingress_get(path)
            require(set(reply) == {'status', 'raw'} and reply['status'] == 200 and
                    isinstance(reply['raw'], bytes) and len(reply['raw']) == expected['size'] and
                    digest(reply['raw']) == expected['sha256'], 'ingress_http_resource_mismatch')
        return {'status': 'VERIFIED_INGRESS', 'host': '127.0.0.1', 'port': 18080,
                'siteSha256': contract['siteSha256'], 'nginxConfigSha256': contract['nginxConfigSha256'],
                'frontendManifestSha256': self.plan['release']['frontendManifest']['sha256'],
                'indexSha256': rows['index.html']['sha256'], 'resourcesVerified': len(resources)}

    def prepare_preimage(self):
        self.guard()
        if self.prepared:
            return
        run = str(uuid.UUID(self.plan['runId']))
        require(run == self.plan['runId'], 'canonical_run_required')
        folder = safe_path(BASE / 'runtime/backups' / ('ordinary-release-' + run))
        require(not folder.exists() and shutil.disk_usage(folder.parent).free >= MIN_FREE,
                'preimage_replay_or_low_disk')
        os.mkdir(folder, 0o700)
        fsync_dir(folder.parent)
        old = inventory('/var/www/print-system')
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w:gz') as archive:
            for name, row in old.items():
                raw = file_bytes(pathlib.Path('/var/www/print-system') / name)
                require(digest(raw) == row['sha256'], 'preimage_changed')
                entry = tarfile.TarInfo(name)
                entry.size = len(raw)
                archive.addfile(entry, io.BytesIO(raw))
        exclusive(folder / 'frontend.tar.gz', buffer.getvalue())
        current = BASE / 'runtime/releases/current'
        require(current.is_symlink(), 'current_release_pointer_required')
        target = current.resolve(strict=True)
        require(target.is_relative_to(BASE / 'runtime/releases'), 'release_pointer_escape')
        exclusive(folder / 'backend.jar', file_bytes(target / 'backend.jar'))
        exclusive(folder / 'application-cloud.yaml', file_bytes(BASE / 'ops/runtime/application-cloud.yaml'))
        exclusive(folder / 'preimage.json', canon({'runId': run, 'releaseTarget': str(target),
                  'frontend': old, 'frontendArchiveSha256': digest(buffer.getvalue())}))
        self.prepared = True

    def atomic(self, path, raw):
        path = safe_path(path)
        require(path.parent.is_dir(), 'install_parent_required')
        temporary = path.parent / ('.' + path.name + '.ordinary-' + uuid.uuid4().hex)
        exclusive(temporary, raw, 0o644)
        os.replace(temporary, path)
        fsync_dir(path.parent)

    def install(self, ref, target):
        self.guard()
        validate_ref(ref)
        key = next((key for key in ('jar', 'frontendManifest', 'gateConfig')
                    if self.plan['release'][key] == ref), None)
        require(key is not None and key not in self.installs, 'install_ref_or_replay')
        self.installs.add(key)
        raw = file_bytes(ref['path'])
        require(digest(raw) == ref['sha256'], 'install_source_drift')
        self.prepare_preimage()
        release = BASE / 'runtime/releases' / ('ordinary-' + self.plan['runId'])
        if key == 'jar':
            require(target == str(release / 'backend.jar') and not release.exists(),
                    'jar_target_or_run_replay')
            os.mkdir(release, 0o755)
            fsync_dir(release.parent)
            self.atomic(release / 'backend.jar', raw)
        elif key == 'gateConfig':
            require(target == str(BASE / 'ops/runtime/application-cloud.yaml'), 'gate_target_mismatch')
            self.atomic(pathlib.Path(target), raw)
        else:
            require(target == '/var/www/print-system', 'frontend_target_mismatch')
            source = safe_path(self.plan['release']['frontendRoot'])
            require(source == BASE / 'src/frontend/dist', 'frontend_source_not_fixed')
            wanted = manifest_rows(raw)
            require(inventory(source) == wanted, 'frontend_complete_manifest_mismatch')
            verify_references(file_bytes(source / 'index.html'), wanted)
            old = inventory(target)
            require(all(old[name] == wanted[name] for name in set(old) & set(wanted)
                        if name.startswith('assets/')), 'hashed_asset_collision')
            for name in sorted(wanted, key=lambda item: (item == 'index.html', item)):
                destination = pathlib.Path(target) / name
                parent = pathlib.Path(target)
                for part in pathlib.PurePosixPath(name).parts[:-1]:
                    child = safe_path(parent / part)
                    if not child.exists():
                        os.mkdir(child, 0o755)
                        fsync_dir(parent)
                    parent = child
                content = file_bytes(source / name)
                require(digest(content) == wanted[name]['sha256'], 'frontend_changed_at_write')
                self.atomic(destination, content)
            actual = inventory(target)
            require(set(actual) <= set(old) | set(wanted) and all(
                actual.get(name) == row for name, row in wanted.items()), 'frontend_install_not_proven')
        return 'ACK'

    def select_backend_release(self, target, pointer):
        self.guard()
        release = BASE / 'runtime/releases' / ('ordinary-' + self.plan['runId'])
        require(not self.selected and self.installs == {'jar', 'frontendManifest', 'gateConfig'},
                'pointer_requires_single_complete_release')
        require(target == str(release) and pointer == str(BASE / 'runtime/releases/current'),
                'pointer_paths_mismatch')
        require(digest(file_bytes(release / 'backend.jar')) == self.plan['release']['jar']['sha256'],
                'selected_jar_drift')
        self.selected = True
        temporary = release.parent / ('.current-ordinary-' + self.plan['runId'])
        require(not temporary.exists() and not temporary.is_symlink(), 'pointer_temporary_exists')
        os.symlink(release.name, temporary)
        os.replace(temporary, pathlib.Path(pointer))
        fsync_dir(release.parent)
        return 'ACK'


class GeneratedBackupPort:
    """Verifies runtime outputs from the explicitly supplied frozen backup producer.

    The producer is deliberately not an implicit call to the historical rotating
    backup script. Its run-specific output implementation needs final review.
    """
    def __init__(self, plan, produce=None):
        self.plan = copy.deepcopy(plan)
        self.produce = produce
        self.lease = None
        self.started = False

    def bind(self, lease):
        self.lease = lease

    def verify(self, contract):
        require(self.lease is not None and self.produce is not None, 'backup_producer_not_configured')
        self.lease.check()
        require(not self.started and contract == self.plan['backupContract'], 'backup_contract_or_replay')
        self.started = True
        ref = validate_ref(contract['adapterRef'])
        require(digest(file_bytes(ref['path'])) == ref['sha256'], 'backup_program_drift')
        directory = safe_path(contract['outputDirectory'])
        encrypted = safe_path(contract['encryptedPath'])
        receipt = safe_path(contract['restoreReceiptPath'])
        require(directory == BASE / 'runtime/backups' / ('ordinary-' + self.plan['runId']) and
                encrypted == directory / 'backup.aes' and receipt == BASE /
                'runtime/private/ordinary-activation' / self.plan['runId'] / 'restore-proof.json',
                'backup_output_paths_not_exact')
        require(not directory.exists() and not receipt.exists(), 'backup_run_output_already_exists')
        proof = self.produce(copy.deepcopy(contract))
        require(proof['runId'] == self.plan['runId'] and proof['encryptedArtifact'] == {
            'path': str(encrypted), 'sha256': digest(file_bytes(encrypted, private=True))},
            'backup_actual_artifact_sha_mismatch')
        require(proof['restoreVerified'] is True and proof['temporarySchemaRemoved'] is True and
                strict_json(file_bytes(receipt, private=True)) == proof,
                'backup_restore_receipt_not_exact')
        return proof


class RuntimeAdapter:
    def __init__(self, plan, runner=None, backup=None):
        self.plan = copy.deepcopy(plan)
        self.runner, self.backup = runner, backup
        self.lease = self.journal = self.receipts = None
        self.release_started = self.backend_start_attempted = self.backup_started = False
        self.reopen_started = False

    def bind(self, lease, journal, receipts):
        self.lease, self.journal, self.receipts = lease, journal, receipts
        if self.runner is not None and hasattr(self.runner, 'bind'):
            self.runner.bind(lease)
        if self.backup is not None and hasattr(self.backup, 'bind'):
            self.backup.bind(lease)

    def guard(self):
        require(self.lease is not None, 'lock_first_required')
        self.lease.check()
        require(self.runner is not None, 'SOURCE_ONLY_NO_COMMAND_RUNNER')

    def event(self, stage, status, **safe):
        self.guard()
        self.journal.append(stage + '_' + status, **safe)
        self.receipts.add(stage, status, **safe)

    def command(self, argv):
        self.guard()
        require(argv in COMMANDS, 'command_not_fixed_allowlist')
        require(self.runner.command(argv) == 'ACK', 'command_result_unknown')

    def preflight(self):
        self.guard()
        require(self.runner.snapshot() == self.plan['runtimeBefore'], 'runtime_baseline_drift')
        self.event('RUNTIME_BASELINE', 'OBSERVED')

    def observe_until(self, predicate, reason, seconds=40):
        deadline = time.monotonic() + seconds
        while True:
            state = self.runner.snapshot()
            if predicate(state):
                return state
            require(time.monotonic() < deadline, reason)
            time.sleep(0.25)

    def close_ingress(self):
        self.event('CLOSE_INGRESS', 'INTENT')
        self.command(('systemctl', 'stop', 'nginx.service'))
        self.observe_until(lambda state: state['nginxActive'] is False and
                           state['externalListeners'] == [] and state['pendingRequests'] == 0,
                           'ingress_close_not_proven')
        self.assert_closed()
        self.event('CLOSE_INGRESS', 'OBSERVED')

    def stop_backend(self):
        self.event('STOP_BACKEND', 'INTENT')
        self.command(('systemctl', 'stop', 'print-backend.service'))
        self.observe_until(lambda state: state['backendActive'] is False and state['backendPid'] == 0
                           and state['port48080'] == [] and state['dbConnections'] == 0,
                           'backend_or_connections_not_zero')
        if hasattr(self.runner, 'previous_backend_exited'):
            require(self.runner.previous_backend_exited(), 'previous_backend_process_not_exited')
        self.event('STOP_BACKEND', 'OBSERVED')

    def assert_closed(self):
        self.guard()
        state = self.runner.snapshot()
        require(state['nginxActive'] is False and state['externalListeners'] == [] and
                state['pendingRequests'] == 0, 'ingress_not_closed')

    def backup_verified(self):
        self.assert_closed()
        require(not self.backup_started and self.backup is not None, 'backup_no_replay_or_missing_port')
        self.backup_started = True
        contract = self.plan['backupContract']
        require(contract['outputLifecycle'] == 'RUN_GENERATED_OUTPUT' and
                'encryptedSha256' not in contract and 'expectedProof' not in contract,
                'backup_runtime_output_contract_required')
        self.event('BACKUP_RESTORE', 'INTENT')
        try:
            proof = self.backup.verify(copy.deepcopy(contract))
            artifact = proof['encryptedArtifact']
            require(proof['runId'] == self.plan['runId'] and artifact['path'] == contract['encryptedPath']
                    and re.fullmatch('[0-9a-f]{64}', artifact['sha256']) is not None and
                    all(proof.get(key) is value for key, value in contract['successCriteria'].items()) and
                    proof['restoreVerified'] is True and proof['temporarySchemaRemoved'] is True,
                    'backup_restore_proof_mismatch')
            # This observed event is durable before the executor may enter its first DDL stage.
            self.event('BACKUP_RESTORE', 'OBSERVED', proofSha256=digest(canon(proof)),
                       encryptedArtifact=artifact, restoreVerified=True, temporarySchemaRemoved=True)
            return proof
        except BaseException:
            self.event('BACKUP_RESTORE', 'UNKNOWN')
            raise

    def install_release(self):
        self.assert_closed()
        require(not self.release_started, 'release_no_replay')
        self.release_started = True
        release_dir = BASE / 'runtime/releases' / ('ordinary-' + self.plan['runId'])
        for stage, key, target in (
                ('INSTALL_JAR', 'jar', str(release_dir / 'backend.jar')),
                ('INSTALL_FRONTEND', 'frontendManifest', '/var/www/print-system'),
                ('INSTALL_GATE_CONFIG', 'gateConfig', str(BASE / 'ops/runtime/application-cloud.yaml'))):
            ref = validate_ref(self.plan['release'][key])
            self.event(stage, 'INTENT', inputSha256=ref['sha256'])
            try:
                require(self.runner.install(copy.deepcopy(ref), target) == 'ACK', 'partial_install_unknown')
            except BaseException:
                self.event(stage, 'UNKNOWN')
                raise
            self.event(stage, 'OBSERVED', inputSha256=ref['sha256'])
        self.event('SELECT_BACKEND_RELEASE', 'INTENT')
        require(self.runner.select_backend_release(str(release_dir), str(BASE / 'runtime/releases/current'))
                == 'ACK', 'release_pointer_result_unknown')
        self.event('SELECT_BACKEND_RELEASE', 'OBSERVED')
        self.event('RELEASE_FROZEN_UNIT', 'OBSERVED')

    def start_backend(self):
        self.assert_closed()
        require(not self.backend_start_attempted, 'backend_start_no_replay')
        self.backend_start_attempted = True
        self.event('START_BACKEND_LOOPBACK', 'INTENT')
        self.command(('systemctl', 'start', 'print-backend.service'))
        def ready(state):
            launch = self.plan['runtimeContract']['backendLaunch']
            process = state.get('backendProcess')
            if state['backendActive'] is True and state['backendPid'] > 0:
                require(isinstance(process, dict) and process['pid'] == state['backendPid'] and
                        process['startTicks'] == state['backendStartTicks'] and
                        process['jarPath'] == launch['after']['jarPath'] and
                        process['jarSha256'] == self.plan['release']['jar']['sha256'] and
                        process['argvSha256'] == digest(canon(launch['after']['argv'])) and
                        process['launchBindingSha256'] == digest(canon(launch)) and
                        state['backendLaunchBindingSha256'] == digest(canon(launch)),
                        'running_process_not_frozen_candidate')
            return (state['backendActive'] is True and state['backendPid'] > 0 and
                (state['backendPid'], state.get('backendStartTicks')) !=
                (self.plan['runtimeBefore']['backendPid'], self.plan['runtimeBefore'].get('backendStartTicks'))
                and state['port48080'] == ['127.0.0.1'] and
                state['jarSha256'] == self.plan['release']['jar']['sha256'] and
                state['frontendManifestSha256'] == self.plan['release']['frontendManifest']['sha256'] and
                state['effectiveGate'] is True and
                state['gateConfigSha256'] == self.plan['release']['gateConfig']['sha256'] and
                state.get('gateSourceBindingSha256') == self.plan['runtimeContract']['gateSourceBindingSha256']
                and (not hasattr(self.runner, 'backend_health') or self.runner.backend_health()))
        state = self.observe_until(ready, 'new_pid_loopback_release_gate_not_proven', seconds=120)
        self.event('START_BACKEND_LOOPBACK', 'OBSERVED', pid=state['backendPid'], effectiveGate=True)

    def keep_closed(self):
        self.event('KEEP_INGRESS_CLOSED', 'INTENT')
        self.command(('systemctl', 'stop', 'nginx.service'))
        self.assert_closed()
        self.event('KEEP_INGRESS_CLOSED', 'OBSERVED')

    def reopen(self):
        self.assert_closed()
        require(not self.reopen_started and callable(getattr(self.runner, 'verify_ingress', None)),
                'ingress_verification_port_required_or_replay')
        self.reopen_started = True
        self.event('REOPEN_INGRESS', 'INTENT')
        try:
            self.command(('systemctl', 'start', 'nginx.service'))
            proof = self.runner.verify_ingress()
            require(proof == self.plan['runtimeContract']['ingress']['expectedProof'] and
                    proof.get('status') == 'VERIFIED_INGRESS', 'ingress_verification_not_proven')
            self.event('REOPEN_INGRESS', 'OBSERVED', proofSha256=digest(canon(proof)))
        except BaseException:
            self.event('REOPEN_INGRESS', 'UNKNOWN')
            self.keep_closed()
            raise
