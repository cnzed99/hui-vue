#!/usr/bin/env python3
"""Bounded third-round experiment. Never declares full-project typecheck PASS."""
import argparse
import datetime
import json
import os
import pathlib
import re
import signal
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--admin', required=True)
parser.add_argument('--evidence', required=True)
args = parser.parse_args()
admin = pathlib.Path(args.admin).resolve()
evidence = pathlib.Path(args.evidence).resolve()
evidence.mkdir(parents=True, exist_ok=True)
scratch = pathlib.Path(os.environ['TMPDIR']).resolve() / 'admin-typecheck'
scratch.mkdir()
node = '/home/ubuntu/print-system/runtime/node/node-v22.23.4-linux-x64/bin/node'
tsc = str(admin / 'node_modules/vue-tsc/bin/vue-tsc.js')
results = []
status = 'INSUFFICIENT_EVIDENCE'


def run(label, command, timeout=180):
    """Called inside a systemd scope with MemoryMax=3GiB; every child gets a group."""
    log_path = evidence / (label + '.log')
    time_path = evidence / (label + '.time.txt')
    started = time.monotonic()
    timed_out = False
    with log_path.open('wb') as out:
        process = subprocess.Popen(['/usr/bin/time', '-v', '-o', str(time_path), *command],
                                   cwd=admin, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            code = 124
    log = log_path.read_text(errors='replace')
    memory = bool(re.search(r'heap out of memory|Reached heap limit|Allocation failed|Command terminated by signal 9', log + time_path.read_text(errors='replace'), re.I))
    record = {'label': label, 'command': command, 'exitCode': code, 'durationSeconds': round(time.monotonic()-started, 3),
              'timedOut': timed_out, 'memoryLimit': memory, 'log': log_path.name, 'time': time_path.name}
    results.append(record)
    print(json.dumps(record), flush=True)
    return code, memory or timed_out or code in (-9, 137)


def write_fixture(name, files):
    directory = scratch / name
    directory.mkdir()
    names = []
    for file, text in files.items():
        target = directory / file
        target.write_text(text)
        names.append(str(target))
    config = directory / 'tsconfig.json'
    config.write_text(json.dumps({'compilerOptions': {'strict': True, 'noEmit': True, 'skipLibCheck': False,
                        'types': [], 'target': 'es2020', 'ignoreDeprecations': '6.0'}, 'files': names}))
    return config


try:
    # Meaningful runner controls: compiler errors and cross-file merge conflicts must fail.
    for name, files, expected_code in [
        ('type-error', {'bad.ts': 'export const n: number = "bad";\n'}, 'TS2322'),
        ('global-conflict', {'a.ts': 'export {}; declare global { interface Window { r11Value: string } }\n',
                             'b.ts': 'export {}; declare global { interface Window { r11Value: number } }\n'}, 'TS2717')]:
        cfg = write_fixture(name, files)
        code, limited = run('fixture-' + name, [node, '--max-old-space-size=2048', tsc, '--noEmit', '--project', str(cfg)], 60)
        if code == 0 or limited or expected_code not in (evidence / ('fixture-' + name + '.log')).read_text():
            raise RuntimeError('Fixture failed to detect required diagnostic: ' + name)
    official_gate = admin / 'scripts/check-dom7-types.cjs'
    if official_gate.exists():
        code, limited = run('dom7-official-types', [node, '--max-old-space-size=2048', str(official_gate)], 60)
        if code or limited:
            raise RuntimeError('Official dom7 types gate failed')
    code, limited = run('generate-declarations', [node, '--max-old-space-size=2048', str(admin / 'scripts/generate-typecheck-declarations.mjs')])
    if limited:
        status = 'RESOURCE_LIMIT'
    elif code:
        status = 'DECLARATION_GENERATION_FAILED'
    else:
        frozen = evidence / 'generated-declarations'
        frozen.mkdir(exist_ok=True)
        for name in ('auto-imports.d.ts', 'auto-components.d.ts'):
            (frozen / name).write_bytes((admin / 'src/types' / name).read_bytes())
        code, limited = run('dependency-graph', [node, '--max-old-space-size=2048', tsc, '--listFilesOnly', '--project', str(admin / 'tsconfig.json')])
        if limited:
            status = 'RESOURCE_LIMIT'
        elif code:
            status = 'DEPENDENCY_GRAPH_FAILED'
        else:
            plan_dir = scratch / 'plan'
            code, limited = run('plan-shards', [node, '--max-old-space-size=2048', str(admin / 'scripts/plan-typecheck-shards.cjs'), str(admin), str(evidence / 'dependency-graph.log'), str(plan_dir)])
            if limited:
                status = 'RESOURCE_LIMIT'
            elif code:
                status = 'INSUFFICIENT_EVIDENCE'
            else:
                manifest = json.loads((plan_dir / 'manifest.json').read_text())
                (evidence / 'coverage-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
                status = 'LIMITED_EXPERIMENT_PASSED_FULL_SINGLE_PROCESS_NOT_RUN'
                for shard in manifest['shards'][:2]:
                    code, limited = run(shard['id'], [node, '--max-old-space-size=2048', tsc, '--noEmit', '--project', shard['config']])
                    if limited:
                        status = 'RESOURCE_LIMIT'
                        break
                    if code:
                        status = 'TYPE_ERRORS'
                        break
except Exception as error:
    status = 'INSUFFICIENT_EVIDENCE'
    (evidence / 'executor-error.txt').write_text(type(error).__name__ + ': ' + str(error) + '\n')
finally:
    summary = {'schemaVersion': 1, 'status': status, 'finishedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'admin': str(admin), 'heapMiB': 2048, 'systemdMemoryMaxBytes': 3221225472, 'systemdMemorySwapMaxBytes': 268435456,
               'results': results, 'fullSingleProcessTypecheck': 'NOT_RUN', 'businessDeployment': 'UNCHANGED',
               'semanticBoundary': 'Finite representative experiment only. Root coverage does not establish equivalence to full-program global semantics.'}
    (evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('THIRD_ROUND_RESULT=' + status, flush=True)
