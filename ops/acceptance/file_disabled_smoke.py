import json,pathlib,urllib.request,urllib.error
base=pathlib.Path(__file__).resolve().parents[2]
checks=[];tokens=[]
def api(path,token=None,body=None,method=None,multipart=False):
 h={'tenant-id':'1'}
 if token:h['Authorization']='Bearer '+token
 if multipart:h['Content-Type']='multipart/form-data; boundary=printdisabled';data=b'--printdisabled--\r\n'
 elif body is not None:h['Content-Type']='application/json';data=json.dumps(body).encode()
 else:data=None
 req=urllib.request.Request('http://127.0.0.1:48080/admin-api'+path,data=data,headers=h,method=method)
 try:
  with urllib.request.urlopen(req,timeout=10) as r:status=r.status;value=r.read()
 except urllib.error.HTTPError as r:status=r.code;value=r.read()
 return status,json.loads(value)
def login(name):
 c=json.loads((base/'runtime/secrets'/('smoke-'+name+'.json')).read_text())
 _,v=api('/system/auth/login',body={k:c[k] for k in ('username','password')})
 assert v.get('code')==0
 t=v['data']['accessToken'];tokens.append(t);return t
def check(name,result,expected):
 status,data=result
 checks.append({'name':name,'http':status,'code':data.get('code'),'passed':data.get('code')==expected})
try:
 a=login('admin');r=login('reader')
 check('anonymous_denied',api('/print/files/unknown'),401)
 check('admin_disabled',api('/print/files/unknown',a),1510010000)
 check('reader_query_disabled',api('/print/files/unknown',r),1510010000)
 check('admin_delete_disabled',api('/print/files/unknown',a,method='DELETE'),1510010000)
 check('reader_delete_denied',api('/print/files/unknown',r,method='DELETE'),403)
 check('reader_upload_denied',api('/print/files',r,method='POST',multipart=True),403)
finally:
 for t in tokens:
  _,v=api('/system/auth/logout',t,body={})
  checks.append({'name':'session_revoked','passed':v.get('code')==0})
report={'checks':checks,'passed':all(x['passed'] for x in checks),'files_uploaded':0}
(base/'evidence/acceptance/file-disabled-smoke.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report));raise SystemExit(0 if report['passed'] else 1)
