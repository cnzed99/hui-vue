#!/usr/bin/env python3
"""Real source-verified role/tenant/disabled-user checks on isolated loopback service."""
import argparse,datetime,hashlib,json,os,pathlib,stat,sys,urllib.error,urllib.request

BASE=pathlib.Path('/home/ubuntu/print-system')
ROUTES={'login':'/admin-api/system/auth/login','permission':'/admin-api/system/auth/get-permission-info',
        'logout':'/admin-api/system/auth/logout','users':'/admin-api/system/user/page?pageNo=1&pageSize=1'}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):return None

def read_credentials(label):
    p=BASE/('runtime/secrets/smoke-'+label+'.json')
    st=p.stat()
    if p.is_symlink() or st.st_uid!=os.getuid() or stat.S_IMODE(st.st_mode)&0o077 or st.st_size>8192:raise ValueError('private_credentials_required')
    value=json.loads(p.read_text())
    if not isinstance(value,dict) or not all(isinstance(value.get(k),str) and value[k] for k in ['username','password']):raise ValueError('credential_schema_invalid')
    if type(value.get('tenantId')) is not int:raise ValueError('tenant_id_required')
    return value

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',default=str(BASE/'evidence/acceptance/m1-role-permissions.json'))
    args=parser.parse_args()
    if pathlib.Path(args.report).exists():raise FileExistsError('use_a_new_report_path_to_preserve_previous_evidence')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    cases=[];tokens={};fixtures={label:read_credentials(label) for label in ('admin','reader','disabled')}
    tenant=fixtures['admin']['tenantId']
    if tenant!=1 or any(c['tenantId']!=tenant for c in fixtures.values()):raise ValueError('fixture_tenant_differs_from_readonly_seed_check')
    def request(route,token=None,tenant_id=1,method='GET',data=None):
        headers={'Accept':'application/json'}
        if tenant_id is not None:headers['tenant-id']=str(tenant_id)
        if token:headers['Authorization']='Bearer '+token
        body=None
        if data is not None:headers['Content-Type']='application/json';body=json.dumps(data).encode()
        req=urllib.request.Request('http://127.0.0.1:48080'+ROUTES[route],headers=headers,data=body,method=method)
        try:response=opener.open(req,timeout=10)
        except urllib.error.HTTPError as e:response=e
        except Exception as e:return {'transport_error':type(e).__name__}
        with response:raw=response.read(1048577);status=response.code
        if len(raw)>1048576:return {'http':status,'payload_error':'oversize'}
        try:decoded=json.loads(raw)
        except Exception:return {'http':status,'payload_error':'not_json'}
        if not isinstance(decoded,dict):return {'http':status,'payload_error':'not_object'}
        return {'http':status,'body':decoded}
    def code(r):
        c=r.get('body',{}).get('code')
        return c if type(c) is int else None
    def record(case,r,expected,extra=True,details=None):
        passed=r.get('http')==200 and code(r)==expected and bool(extra)
        item={'id':case,'status':'PASS' if passed else 'FAIL','http_status':r.get('http'),'business_code':code(r),'expected_business_code':expected}
        for key in ('transport_error','payload_error'):
            if key in r:item[key]=r[key]
        if details:item['nonsecret_observations']=details
        cases.append(item)
    try:
        r=request('permission');record('READY-auth-filter-real-route',r,401)
        r=request('users');record('ROLE-no-token-user-page',r,401)
        r=request('users',tenant_id=None);record('AUTH-before-tenant-no-token-no-header',r,401,details={'claim':'Authentication rejects first; this does not prove missing-tenant validation.'})
        for label in ('admin','reader'):
            c=fixtures[label]
            r=request('login',method='POST',data={'username':c['username'],'password':c['password']})
            data=r.get('body',{}).get('data')
            token=data.get('accessToken') if isinstance(data,dict) else None
            if isinstance(token,str) and token:tokens[label]=token
            record('ROLE-'+label+'-login',r,0,bool(tokens.get(label)))
            if not tokens.get(label):continue
            r=request('permission',token=tokens[label])
            permission=r.get('body',{}).get('data')
            names=permission.get('permissions') if isinstance(permission,dict) else None
            has_query=isinstance(names,list) and 'system:user:query' in names
            has_wildcard=isinstance(names,list) and '*:*:*' in names
            expected_query=label=='admin'
            record('ROLE-'+label+'-permission-fixture',r,0,isinstance(names,list) and has_query==expected_query and not has_wildcard,
                   {'query_permission_present':has_query,'wildcard_permission_present':has_wildcard})
            r=request('users',token=tokens[label])
            data=r.get('body',{}).get('data')
            shape=isinstance(data,dict) and isinstance(data.get('list'),list) and type(data.get('total')) is int and data['total']>=1
            record('ROLE-'+label+'-protected-user-page',r,0 if label=='admin' else 403,shape if label=='admin' else data is None)
            r=request('users',token=tokens[label],tenant_id=2)
            record('TENANT-'+label+'-header-token-mismatch',r,403,
                   details={'tenant_2_exists':False,'claim':'token/header mismatch rejection only; not existing-second-tenant data isolation'})
        if tokens.get('admin'):
            r=request('users',token=tokens['admin'],tenant_id=None)
            record('TENANT-valid-token-infers-own-tenant',r,0)
        c=fixtures['admin']
        r=request('login',tenant_id=2,method='POST',data={'username':c['username'],'password':c['password']})
        record('TENANT-login-nonexistent-tenant',r,1002015000,
               details={'tenant_2_exists':False,'claim':'nonexistent tenant rejection only'})
        c=fixtures['disabled']
        r=request('login',method='POST',data={'username':c['username'],'password':c['password']})
        data=r.get('body',{}).get('data')
        issued=isinstance(data,dict) and bool(data.get('accessToken'))
        if issued:tokens['disabled']=data['accessToken']
        record('ACCOUNT-disabled-login-rejected',r,1002000001,not issued)
    finally:
        for label,token in tokens.items():
            r=request('logout',token=token,method='POST')
            record('CLEANUP-'+label+'-session-logout',r,0,r.get('body',{}).get('data') is True)
            r=request('users',token=token)
            record('CLEANUP-'+label+'-old-token-rejected',r,401)
    source_paths=[
      'src/backend/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/user/UserController.java',
      'src/backend/yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/security/TenantSecurityWebFilter.java',
      'src/backend/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/enums/ErrorCodeConstants.java']
    report={'executed_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Real isolated Yudao role authorization, disabled account and tenant header checks',
      'source_sha256':{p:hashlib.sha256((BASE/p).read_bytes()).hexdigest() for p in source_paths},
      'readonly_seed_observation':{'tenants':1,'tenant2_exists':False,'enabled_users':2,'disabled_users':1,'admin_query_permission':True,'reader_query_permission':False},
      'cases':cases,'out_of_scope':['existing_second_tenant_data_isolation','print_store_ownership','file_owner_access','all_business_action_roles','refresh_token_revocation','user_acceptance']}
    target=pathlib.Path(args.report);target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(report,ensure_ascii=False,indent=2));os.chmod(target,0o600)
    print(json.dumps({'passed':sum(x['status']=='PASS' for x in cases),'failed':sum(x['status']=='FAIL' for x in cases),
       'sessions_created':len(tokens),'cleanup_cases_passed':all(x['status']=='PASS' for x in cases if x['id'].startswith('CLEANUP-')),
       'failures':[x for x in cases if x['status']=='FAIL']}))
    return 1 if any(x['status']=='FAIL' for x in cases) else 0

if __name__=='__main__':
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({'error_type':type(e).__name__,'status':'ERROR'}));sys.exit(2)
