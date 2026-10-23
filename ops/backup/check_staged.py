#!/usr/bin/env python3
"""Fail closed if staged blobs contain configured runtime secrets or private keys."""
import json,pathlib,re,shlex,subprocess
BASE=pathlib.Path(__file__).resolve().parents[2]
def main():
 secrets=[]
 for line in (BASE/'runtime/secrets/runtime.env').read_text().splitlines():
  if '=' not in line:continue
  key,value=line.split('=',1)
  if not re.search('PASSWORD|SECRET|TOKEN|KEY',key):continue
  parts=shlex.split(value)
  if parts and len(parts[0])>6:secrets.append(parts[0].encode())
 def collect(obj):
  if isinstance(obj,dict):
   for key,value in obj.items():
    if any(w in key.lower() for w in ['password','secret','token']) and isinstance(value,str) and len(value)>6:secrets.append(value.encode())
    else:collect(value)
  elif isinstance(obj,list):
   for value in obj:collect(value)
 for path in (BASE/'runtime/secrets').glob('*.json'):collect(json.loads(path.read_text()))
 count=0
 for repo in [BASE,BASE/'src/backend',BASE/'src/frontend']:
  files=subprocess.check_output(['git','diff','--cached','--name-only','--diff-filter=ACMR','-z'],cwd=repo).split(b'\0')
  for name in files:
   if not name:continue
   blob=subprocess.check_output(['git','show',':'+name.decode()],cwd=repo)
   if any(secret in blob for secret in secrets) or re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',blob):raise RuntimeError('staged_secret_detected')
   count+=1
  result=subprocess.run(['git','diff','--cached','--check'],cwd=repo,capture_output=True)
  if result.returncode:raise RuntimeError('staged_whitespace_error')
 print(json.dumps({'passed':True,'staged_files_scanned':count}))
if __name__=='__main__':
 try:main()
 except Exception as exc:print(json.dumps({'passed':False,'exception_class':type(exc).__name__}));raise SystemExit(1)
