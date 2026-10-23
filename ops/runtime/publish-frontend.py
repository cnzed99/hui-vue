#!/usr/bin/env python3
"""Publish only generated static assets; keep one previous snapshot for rollback."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'src/frontend/dist'
DEST = Path('/var/www/print-system')

def digest(p):
    value = hashlib.sha256()
    with p.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

def inventory(root):
    result = {}
    for path in root.rglob('*'):
        if path.is_symlink():
            raise RuntimeError('symlink in static tree; stop')
        if path.is_file():
            result[str(path.relative_to(root))] = digest(path)
    return result

def main():
    if shutil.disk_usage(ROOT).free < 10 * 1024**3:
        raise RuntimeError('low disk; stop')
    for root in (SRC, DEST):
        if root.is_symlink() or not root.is_dir() or not (root / 'index.html').is_file():
            raise RuntimeError('invalid static directory')
    if set(p.name for p in DEST.iterdir()) - {'assets', 'favicon.ico', 'index.html', 'logo.gif'}:
        raise RuntimeError('unknown deployed content; stop for review')
    old, new = inventory(DEST), inventory(SRC)
    backups = ROOT / 'runtime/backups'
    backups.mkdir(exist_ok=True)
    temporary = backups / 'frontend-previous.tmp.tar.gz'
    previous = backups / 'frontend-previous.tar.gz'
    with tarfile.open(temporary, 'w:gz') as archive:
        for name in sorted(old):
            archive.add(DEST / name, arcname=name, recursive=False)
    os.chmod(temporary, 0o600)
    temporary.replace(previous)
    # Copy assets before switching index.html, so existing HTML remains usable while copying.
    for name in sorted(new, key=lambda value: (value == 'index.html', value)):
        source, target = SRC / name, DEST / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
        staging = target.with_name(target.name + '.publishing')
        shutil.copyfile(source, staging)
        os.chmod(staging, 0o644)
        if digest(staging) != new[name]:
            raise RuntimeError('copy verification failed')
        staging.replace(target)
    removed = 0
    for name in sorted(set(old) - set(new)):
        target = DEST / name
        if target.is_file() and not target.is_symlink() and digest(target) == old[name]:
            removed += target.stat().st_size
            target.unlink()
    for folder in sorted((p for p in DEST.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        try:
            folder.rmdir()
        except OSError:
            pass
    deployed = inventory(DEST)
    result = {'files': len(new), 'verified': deployed == new, 'obsoleteAssetBytesRemoved': removed,
              'previousSnapshotBytes': previous.stat().st_size, 'freeAfterBytes': shutil.disk_usage(ROOT).free}
    (ROOT / 'evidence/frontend-publication.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    if deployed != new:
        raise RuntimeError('deployment inventory mismatch')

if __name__ == '__main__':
    main()
