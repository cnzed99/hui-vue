#!/usr/bin/env python3
"""Run an immutable allowlist of existing backend synthetic checks, serially."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import resource
import shutil
import signal
import subprocess
import tempfile
import time
import zipfile

ROOT = pathlib.Path('/home/ubuntu/print-system')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def tree_index(root):
    return {str(p.relative_to(root)): digest(p) for p in sorted(root.rglob('*')) if p.is_file()}


def git(backend, *args):
    return subprocess.check_output(['git', '-C', str(backend), *args], text=True).strip()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def child_limits():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (512, 512))
    resource.setrlimit(resource.RLIMIT_CPU, (125, 130))


def stop_group(process):
    # Include renderer/lock child JVMs; never clear scratch while any remain.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        process.poll()
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        time.sleep(.1)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()
    for _ in range(50):
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        time.sleep(.1)
    raise RuntimeError('Child process group survived termination; scratch retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', type=pathlib.Path, required=True)
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    parser.add_argument('--group', choices=['core', 'filesystem', 'render', 'all'], default='core')
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('terminated')))
    manifest_path = pathlib.Path(__file__).with_name('backend-print-checks.json')
    manifest = json.loads(manifest_path.read_text())
    backend = args.backend.resolve(strict=True)
    evidence = args.evidence.resolve()
    if not backend.is_relative_to(ROOT / 'runtime/worktrees'):
        raise ValueError('Backend must be an isolated cloud worktree')
    if not evidence.is_relative_to(ROOT / 'evidence/testing'):
        raise ValueError('Evidence must be under cloud evidence/testing')
    if evidence.exists():
        raise ValueError('Use a new evidence directory; never overwrite an earlier attempt')
    temp_parent = pathlib.Path(os.environ['TMPDIR']).resolve(strict=True)
    if temp_parent.parent != ROOT / 'runtime/build-tmp' or temp_parent.is_symlink():
        raise ValueError('Standard build-window scratch required')
    cgroup_relative = next(line.split(':', 2)[2] for line in pathlib.Path('/proc/self/cgroup').read_text().splitlines()
                           if line.startswith('0::'))
    cgroup = pathlib.Path('/sys/fs/cgroup') / cgroup_relative.lstrip('/')
    actual_memory_max = (cgroup / 'memory.max').read_text().strip()
    actual_swap_max = (cgroup / 'memory.swap.max').read_text().strip()
    if actual_memory_max == 'max' or int(actual_memory_max) > 3221225472:
        raise ValueError('Required whole-runner cgroup MemoryMax <= 3GiB')
    if actual_swap_max == 'max' or int(actual_swap_max) > 268435456:
        raise ValueError('Required whole-runner cgroup MemorySwapMax <= 256MiB')
    for service in ['print-backend.service', 'mysql', 'redis-server', 'nginx']:
        state = subprocess.run(['systemctl', 'is-active', '--quiet', service]).returncode
        if state == 0:
            raise ValueError('Run inside the standard stopped-service window')
    if git(backend, 'rev-parse', 'HEAD') != manifest['backendCommit'] or git(backend, 'status', '--porcelain'):
        raise ValueError('Backend commit or clean-worktree precondition failed')
    target = backend / 'yudao-server/target'
    jar = target / 'yudao-server.jar'
    classes = target / 'classes'
    tests = target / 'test-classes'
    if digest(jar) != manifest['runtimeJarSha256']:
        raise ValueError('Candidate runtime JAR does not match manifest')
    source_root = backend / 'yudao-server/src/test/java'
    expected_sources = {
        (manifest['classPrefix'] + c['class']).replace('.', '/') + '.java'
        for c in manifest['checks']
    } | {c['source'] for c in manifest['nonExecutableSources']}
    actual_sources = {str(p.relative_to(source_root)) for p in source_root.rglob('*.java')}
    if len(manifest['checks']) != 16 or expected_sources != actual_sources:
        raise ValueError('Source inventory differs from the fixed 16+2 allowlist')
    for check in manifest['checks']:
        class_file = tests / ((manifest['classPrefix'] + check['class']).replace('.', '/') + '.class')
        if not class_file.is_file():
            raise ValueError('Compiled test class missing: ' + check['id'])
    mockito = pathlib.Path('/home/ubuntu/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar')
    agent = pathlib.Path('/home/ubuntu/.m2/repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar')
    for path in (mockito, agent):
        if not path.is_file():
            raise ValueError('Missing cached test dependency: ' + path.name)
    evidence.mkdir(parents=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix='backend-checks-', dir=temp_parent)).resolve()
    summary = {
        'schemaVersion': 1, 'status': 'RUNNING', 'backendCommit': manifest['backendCommit'],
        'group': args.group, 'startedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'implementationModel': 'gpt-6.1-sol', 'effort': 'INHERITED_NOT_SEPARATELY_EXPOSED',
        'manifestSha256': digest(manifest_path), 'runnerSha256': digest(pathlib.Path(__file__)),
        'runtimeJar': str(jar), 'runtimeJarSha256': digest(jar),
        'testClasses': tree_index(tests), 'mainClasses': tree_index(classes),
        'testDependencies': {str(p): digest(p) for p in (mockito, agent)},
        'nonExecutableSources': manifest['nonExecutableSources'], 'checks': [],
        'scratch': str(work), 'scratchRemoved': False, 'boundary': manifest['boundary'],
        'resources': {'javaHeapMiB': 512, 'javaMetaspaceMiB': 192,
                      'cgroup': str(cgroup),
                      'wholeRunnerCgroupMemoryMaxBytes': int(actual_memory_max),
                      'wholeRunnerCgroupSwapMaxBytes': int(actual_swap_max),
                      'serial': True, 'coreDump': False},
    }
    font = ROOT / 'runtime/fonts/wqy-zenhei.ttc'
    font_ok = font.is_file() and digest(font) == manifest['fontSha256']
    summary['font'] = {'path': str(font), 'sha256': digest(font) if font.is_file() else None, 'approved': font_ok}
    selected = [c for c in manifest['checks'] if args.group == 'all' or c['group'] == args.group]
    summary['selectedCount'] = len(selected)
    live = None
    cleanable = True
    try:
        libs = work / 'runtime-libs'
        libs.mkdir()
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                    relative = name.removeprefix('BOOT-INF/classes/')
                    path = classes / relative
                    if not path.is_file() or hashlib.sha256(archive.read(name)).hexdigest() != digest(path):
                        raise ValueError('target/classes differs from runtime JAR: ' + relative)
                if re.fullmatch(r'BOOT-INF/lib/[^/]+\.jar', name):
                    (libs / pathlib.PurePosixPath(name).name).write_bytes(archive.read(name))
        summary['runtimeLibraries'] = tree_index(libs)
        cp = ':'.join(map(str, [tests, classes, libs / '*', mockito, agent]))
        stopped = False
        for check in selected:
            item = {'id': check['id'], 'class': manifest['classPrefix'] + check['class'], 'group': check['group']}
            summary['checks'].append(item)
            if stopped:
                item.update(status='NOT_RUN_PREVIOUS_FAILURE')
                continue
            if check['args'] == 'font' and not font_ok:
                item.update(status='SKIP_WITH_REASON', reason='APPROVED_FONT_MISSING_OR_HASH_MISMATCH')
                stopped = True
                continue
            case = work / check['id']
            case.mkdir(mode=0o700)
            case_tmp = case / 'tmp'
            case_tmp.mkdir(mode=0o700)
            case_home = case / 'home'
            case_home.mkdir(mode=0o700)
            case_args = []
            if check['args'] == 'existingEmptyRoot':
                pdf_root = case / 'pdf-root'
                pdf_root.mkdir(mode=0o700)
                case_args = [str(pdf_root)]
            elif check['args'] == 'nonexistentLockRoot':
                lock_root = case / 'real-lock-root'
                if lock_root.exists():
                    raise ValueError('Real lock root must not exist before execution')
                case_args = ['run', str(lock_root)]
            elif check['args'] == 'runtimeJar':
                case_args = [str(jar)]
            elif check['args'] == 'font':
                case_args = [str(font)]
            stdout_path = evidence / (check['id'] + '.stdout.log')
            stderr_path = evidence / (check['id'] + '.stderr.log')
            time_path = evidence / (check['id'] + '.time.log')
            command = ['/usr/bin/time', '-v', '-o', str(time_path), '/usr/bin/java',
                       '-Xms32m', '-Xmx512m', '-XX:MaxMetaspaceSize=192m', '-XX:+UseSerialGC',
                       '-Djava.awt.headless=true', '-Djava.io.tmpdir=' + str(case_tmp),
                       '-Duser.home=' + str(case_home), '-cp', cp, item['class'], *case_args]
            item.update(command=command, timeoutSeconds=check['timeoutSeconds'])
            env = {'PATH': '/usr/bin:/bin', 'HOME': str(case_home), 'LANG': 'C.UTF-8',
                   'TMPDIR': str(case_tmp), 'TMP': str(case_tmp), 'TEMP': str(case_tmp)}
            start = time.monotonic()
            with stdout_path.open('wb') as out, stderr_path.open('wb') as err:
                live = subprocess.Popen(command, cwd=case, env=env, stdout=out, stderr=err,
                                        start_new_session=True, preexec_fn=child_limits)
                try:
                    exit_code = live.wait(timeout=check['timeoutSeconds'])
                    item['timedOut'] = False
                except subprocess.TimeoutExpired:
                    item['timedOut'] = True
                    exit_code = None
                stop_group(live)
                live = None
            output = stdout_path.read_text(errors='replace')
            marker = check['success'] in output
            item.update(exitCode=exit_code, durationSeconds=round(time.monotonic() - start, 3),
                        successMarker=check['success'], successMarkerFound=marker,
                        stdout=stdout_path.name, stderr=stderr_path.name, resourceLog=time_path.name,
                        status='PASS' if exit_code == 0 and marker and not item['timedOut'] else 'FAIL')
            print(check['id'], item['status'], item['durationSeconds'], flush=True)
            if item['status'] != 'PASS':
                stopped = True
            # Process group is gone before any per-case removal.
            shutil.rmtree(case)
            item['scratchRemoved'] = not case.exists()
            write_json(evidence / 'summary.json', summary)
        summary['status'] = 'PASS' if all(c['status'] == 'PASS' for c in summary['checks']) else 'PARTIAL'
        summary['all16Passed'] = args.group == 'all' and len(summary['checks']) == 16 and summary['status'] == 'PASS'
    except BaseException as exc:
        summary.update(status='ERROR', error=type(exc).__name__ + ': ' + str(exc))
        if live is not None:
            try:
                stop_group(live)
            except RuntimeError:
                cleanable = False
        raise
    finally:
        if cleanable and work.parent == temp_parent and not work.is_symlink():
            shutil.rmtree(work)
            summary['scratchRemoved'] = not work.exists()
        summary['finishedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        write_json(evidence / 'summary.json', summary)
    return 0 if summary['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
