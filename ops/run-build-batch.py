#!/usr/bin/env python3
"""Serialize memory-heavy builds and clean only this invocation's scratch directory."""
import argparse
import datetime
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MIN_FREE = 10 * 1024 ** 3

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command or not args.label.replace('-', '').isalnum():
        parser.error('supply a simple label and command after --')
    scratch_root = ROOT / 'runtime' / 'build-tmp'
    scratch_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (scratch_root / '.build.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('BUILD_ALREADY_RUNNING_STOP')
            return 3
        before = shutil.disk_usage(ROOT).free
        if before < MIN_FREE:
            print('LOW_DISK_STOP')
            return 2
        temp = Path(tempfile.mkdtemp(prefix=args.label + '-', dir=scratch_root))
        record = {'label': args.label, 'startedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'freeBeforeBytes': before, 'minimumFreeBytes': MIN_FREE}
        env = dict(os.environ)
        for key in ('TMPDIR', 'TMP', 'TEMP'):
            env[key] = str(temp)
        env['JAVA_TOOL_OPTIONS'] = env.get('JAVA_TOOL_OPTIONS', '') + ' -Djava.io.tmpdir=' + str(temp)
        result = 1
        try:
            result = subprocess.run(command, cwd=ROOT, env=env).returncode
        finally:
            # Never sweep shared /tmp, caches, source, databases, releases or another batch.
            if temp.is_symlink() or temp.resolve().parent != scratch_root.resolve():
                raise RuntimeError('unsafe scratch path; cleanup stopped')
            size = sum(p.lstat().st_size for p in temp.rglob('*') if p.is_file() and not p.is_symlink())
            shutil.rmtree(temp)
            after = shutil.disk_usage(ROOT).free
            record.update(exitCode=result, scratchBytesRemoved=size, scratchRemoved=not temp.exists(),
                          freeAfterBytes=after, aboveMinimum=after >= MIN_FREE)
            evidence = ROOT / 'evidence' / 'disk'
            evidence.mkdir(parents=True, exist_ok=True)
            (evidence / (args.label + '-latest.json')).write_text(json.dumps(record, indent=2) + '\n')
            print(json.dumps(record))
        return result if result != 0 else (0 if after >= MIN_FREE else 2)

if __name__ == '__main__':
    raise SystemExit(main())
