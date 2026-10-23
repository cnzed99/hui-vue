#!/usr/bin/env python3
"""Publish immutable jar, verify real authentication, then retain current and previous."""
import datetime, hashlib, json, os, pathlib, re, shutil, subprocess, time, urllib.request
BASE=pathlib.Path(__file__).resolve().parents[2]
RELEASES=BASE/'runtime/releases'

def switch(target):
    link=RELEASES/'current.next'
    if link.is_symlink(): link.unlink()
    link.symlink_to(target.name)
    os.replace(link,RELEASES/'current')

def restart():
    subprocess.run(['sudo','-n','systemctl','reset-failed','print-backend.service'],check=True,capture_output=True)
    subprocess.run(['sudo','-n','systemctl','restart','print-backend.service'],check=True,capture_output=True)

def ready():
    until=time.monotonic()+45
    while time.monotonic()<until:
        try:
            with urllib.request.urlopen('http://127.0.0.1:48080/admin-api/system/auth/get-permission-info',timeout=2) as r:
                if json.loads(r.read(65536)).get('code')==401:return True
        except Exception:pass
        time.sleep(1)
    return False

def main():
    if shutil.disk_usage(BASE).free<10*1024**3:raise RuntimeError('low_disk')
    RELEASES.mkdir(parents=True,exist_ok=True)
    previous=(RELEASES/'current').resolve() if (RELEASES/'current').is_symlink() else None
    if previous is not None and (previous.parent!=RELEASES.resolve() or not re.fullmatch('[0-9a-f]{16}',previous.name)):
        raise RuntimeError('invalid_previous_release')
    jars=list((BASE/'src/backend/yudao-server/target').glob('*.jar'))
    if len(jars)!=1:raise RuntimeError('ambiguous_built_jar')
    sha=hashlib.sha256(jars[0].read_bytes()).hexdigest()
    target=RELEASES/sha[:16];target.mkdir(exist_ok=True)
    jar=target/'backend.jar'
    if not jar.exists():
        temp=target/'backend.jar.next';shutil.copy2(jars[0],temp)
        if hashlib.sha256(temp.read_bytes()).hexdigest()!=sha:raise RuntimeError('jar_copy_mismatch')
        temp.replace(jar)
    elif hashlib.sha256(jar.read_bytes()).hexdigest()!=sha:raise RuntimeError('existing_jar_mismatch')
    record={'sha256':sha,'published_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'readiness_verified':False}
    try:
        switch(target);restart()
        if not ready():raise RuntimeError('startup_not_ready')
        check=subprocess.run(['python3','ops/acceptance/auth_smoke.py','--allow-login',
            '--credentials','runtime/secrets/smoke-admin.json','--report','evidence/acceptance/deployment-auth.json'],
            cwd=BASE,capture_output=True,timeout=20)
        if check.returncode:raise RuntimeError('authentication_smoke_failed')
        record['readiness_verified']=True
    except Exception:
        record['rollback_requested']=previous is not None and previous!=target
        (target/'manifest.json').write_text(json.dumps(record,indent=2)+'\n')
        if record['rollback_requested']:
            switch(previous);restart()
        print(json.dumps(record));return 1
    (target/'manifest.json').write_text(json.dumps(record,indent=2)+'\n')
    keep={target,previous};removed=0
    for path in RELEASES.iterdir():
        if path in keep or path.is_symlink() or not path.is_dir() or not re.fullmatch('[0-9a-f]{16}',path.name):continue
        if path.resolve().parent!=RELEASES.resolve():continue
        if {p.name for p in path.iterdir()}-{'backend.jar','manifest.json'}:continue
        shutil.rmtree(path);removed+=1
    record.update(removed_old_release_count=removed,free_disk_gib=round(shutil.disk_usage(BASE).free/1024**3,2))
    print(json.dumps(record));return 0

if __name__=='__main__':
    raise SystemExit(main())
