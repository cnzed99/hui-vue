#!/usr/bin/env python3
"""Read selected runtime logs with secrets/tokens redacted before SSH output."""
import argparse,collections,json,pathlib,re,shlex,sys
BASE=pathlib.Path('/home/ubuntu/print-system').resolve()
def collect():
 values=set()
 env=BASE/'runtime/secrets/runtime.env'
 if env.exists():
  for line in env.read_text().splitlines():
   if '=' not in line: continue
   k,v=line.split('=',1)
   if re.search('PASSWORD|SECRET|KEY|TOKEN',k):
    parsed=shlex.split(v)
    if parsed and parsed[0]: values.add(parsed[0])
 def walk(v,key=''):
  if isinstance(v,dict):
   for k,x in v.items(): walk(x,k)
  elif isinstance(v,list):
   for x in v: walk(x,key)
  elif isinstance(v,str) and re.search(r'password|secret|token|key|hash',key,re.I) and v: values.add(v)
 for file in (BASE/'runtime/secrets').glob('*.json'):
  walk(json.loads(file.read_text()))
 return values

def redact(text):
 for value in sorted(collect(),key=len,reverse=True): text=text.replace(value,'[REDACTED]')
 text=re.sub(r'\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}','[REDACTED_BCRYPT]',text)
 text=re.sub(r'(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+',r'\1[REDACTED_TOKEN]',text)
 text=re.sub(r'(?i)([\"\x27]?(?:password|passwd|secret|accessToken|refreshToken|access_token|refresh_token|apiKey|api_key|authorization)[\"\x27]?\s*[:=]\s*)(?:[\"\x27][^\"\x27\r\n]*[\"\x27]|[^\s,;}]+)',r'\1[REDACTED]',text)
 text=re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+','[REDACTED_JWT]',text)
 return text

def main():
 parser=argparse.ArgumentParser(); parser.add_argument('path');parser.add_argument('--lines',type=int,default=80);args=parser.parse_args()
 path=(BASE/args.path).resolve()
 allowed=any(path.is_relative_to(BASE/p) for p in ('runtime/logs','evidence'))
 if not allowed or path.suffix not in ('.log','.txt') or not 1<=args.lines<=300: raise ValueError('unsupported_log_path')
 with path.open(errors='replace') as stream: lines=collections.deque(stream,maxlen=args.lines)
 print(redact(''.join(lines)),end='')
if __name__=='__main__':
 try: main()
 except Exception as exc:
  print('safe_log_failed='+type(exc).__name__);sys.exit(1)
