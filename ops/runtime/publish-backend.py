#!/usr/bin/env python3
"""Publish a built jar to an immutable release, restart, retain two releases."""
import hashlib,json,os,pathlib,re,shutil,subprocess,datetime
base=pathlib.Path('/home/ubuntu/print-system').resolve()
releases=base/'runtime/releases';releases.mkdir(parents=True,exist_ok=True)
assert shutil.disk_usage(base).free >= 10*1024**3, 'less_than_10_GiB_free'
jars=list((base/'src/backend/yudao-server/target').glob('*.jar'));assert len(jars)==1
sha=hashlib.sha256(jars[0].read_bytes()).hexdigest();target=releases/sha[:16];target.mkdir(exist_ok=True)
jar=target/'backend.jar'
if not jar.exists():
 temp=target/'backend.jar.next';shutil.copy2(jars[0],temp)
 assert hashlib.sha256(temp.read_bytes()).hexdigest()==sha
 temp.replace(jar)
else: assert hashlib.sha256(jar.read_bytes()).hexdigest()==sha
(target/'manifest.json').write_text(json.dumps({'sha256':sha,'published_at':datetime.datetime.now(datetime.timezone.utc).isoformat()},indent=2)+'\n')
link=releases/'current.next'
if link.is_symlink():link.unlink()
link.symlink_to(target.name);os.replace(link,releases/'current')
subprocess.run(['sudo','-n','systemctl','restart','print-backend.service'],check=True)
# Retention covers our hash-named releases only; never remove unknown files.
candidates=sorted([p for p in releases.iterdir() if p.is_dir() and not p.is_symlink() and re.fullmatch('[0-9a-f]{16}',p.name)],key=lambda p:p.stat().st_mtime,reverse=True)
keep={target,*[p for p in candidates if p!=target][:1]};removed=[]
for p in candidates:
 if p in keep:continue
 assert p.resolve().parent==releases.resolve()
 if any(q.name not in ('backend.jar','manifest.json') for q in p.iterdir()):continue
 shutil.rmtree(p);removed.append(p.name)
print(json.dumps({'published_sha256':sha,'service_restart_requested':True,'readiness_verified':False,'removed_old_release_count':len(removed),'free_disk_gib':round(shutil.disk_usage(base).free/1024**3,2)}))
