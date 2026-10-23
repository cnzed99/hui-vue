#!/usr/bin/env python3
"""Opt-in scope checks with precisely owned, temporary DB fixtures. Not auto-run."""
import argparse,datetime,fcntl,json,os,pathlib,re,secrets,subprocess,sys,urllib.error,urllib.parse,urllib.request
BASE=pathlib.Path('/home/ubuntu/print-system')
class ScopeCheckError(Exception):pass

def sql(statement):
 p=subprocess.run(['sudo','-n','mysql','--default-character-set=utf8mb4','-N','-B','print_demo'],input=statement,text=True,capture_output=True)
 if p.returncode:raise ScopeCheckError('fixture_sql_failed')
 return p.stdout.rstrip('\n')

def credentials(path):
 p=pathlib.Path(path).resolve()
 if not p.is_relative_to(BASE/'runtime/secrets') or p.stat().st_uid!=os.getuid() or p.stat().st_mode&0o077:raise ScopeCheckError('unsafe_credentials_path_or_mode')
 v=json.loads(p.read_text())
 if not re.fullmatch(r'[A-Za-z0-9]{4,30}',v['username']) or not isinstance(v['tenantId'],int) or v['tenantId']<=0:raise ScopeCheckError('invalid_credential_identity')
 return v

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--allow-fixture-mutations',action='store_true');parser.add_argument('--base-url',default='http://127.0.0.1:48080');parser.add_argument('--admin-credentials',default=str(BASE/'runtime/secrets/smoke-admin.json'));parser.add_argument('--reader-credentials',default=str(BASE/'runtime/secrets/smoke-reader.json'));args=parser.parse_args()
 if not args.allow_fixture_mutations:raise ScopeCheckError('explicit_fixture_mutation_flag_required')
 parsed=urllib.parse.urlparse(args.base_url)
 if parsed.scheme!='http' or parsed.hostname not in ('127.0.0.1','localhost','::1') or parsed.username or parsed.password:raise ScopeCheckError('loopback_base_required')
 admin=credentials(args.admin_credentials);reader=credentials(args.reader_credentials)
 if admin['tenantId']!=reader['tenantId']:raise ScopeCheckError('same_fixture_tenant_required')
 tenant=admin['tenantId'];marker='scope-smoke-'+secrets.token_hex(8)
 result={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':[],'source_site_accessed':False,'secrets_in_report':False,'temporary_fixtures_removed':False}
 tokens=[];fixtures=[];restorations=[]
 def check(name,condition):
  result['checks'].append({'name':name,'passed':bool(condition)})
  if not condition:raise ScopeCheckError(name)
 def api(path,token=None,body=None,tenant_id=None,extra_headers=None):
  headers={'tenant-id':str(tenant if tenant_id is None else tenant_id)}
  if token:headers['Authorization']='Bearer '+token
  if extra_headers:headers.update(extra_headers)
  data=None
  if body is not None:data=json.dumps(body).encode();headers['Content-Type']='application/json'
  req=urllib.request.Request(args.base_url.rstrip('/')+path,data=data,headers=headers)
  try:
   with urllib.request.urlopen(req,timeout=15) as response:raw=response.read(1024*1024)
  except urllib.error.HTTPError as response:raw=response.read(1024*1024)
  try:return json.loads(raw)
  except (ValueError,UnicodeError):raise ScopeCheckError('non_json_api_response')
 def login(cred):
  response=api('/admin-api/system/auth/login',body={'username':cred['username'],'password':cred['password']},tenant_id=cred['tenantId'])
  if response.get('code')!=0:raise ScopeCheckError('fixture_login_failed')
  token=response.get('data',{}).get('accessToken')
  if not token:raise ScopeCheckError('fixture_login_missing_token')
  tokens.append(token);return token
 def context(token,**kwargs):return api('/admin-api/print/context',token,**kwargs)
 def identity(response):
  if response.get('code')!=0:return None
  data=response.get('data') or {}
  try:return (int(data['tenantId']),int(data['storeId']),int(data['userId']),data['actorType'])
  except (ValueError,TypeError,KeyError):return None
 def owned_insert(statement,table):
  identifier=int(sql(statement+'; SELECT LAST_INSERT_ID();'))
  if identifier<=0:raise ScopeCheckError('fixture_id_invalid')
  fixtures.append((table,identifier));return identifier
 def remove_owned(table,identifier):
  if table not in ('print_store','print_staff_store','system_tenant'):raise ScopeCheckError('unsafe_cleanup_table')
  sql("DELETE FROM "+table+" WHERE id="+str(identifier)+" AND creator='"+marker+"';")
  if sql('SELECT COUNT(*) FROM '+table+' WHERE id='+str(identifier)+';')!='0':raise ScopeCheckError('fixture_cleanup_incomplete')
  fixtures.remove((table,identifier))
 def mutation_check(name,statement,restore_statement,expected_code):
  restorations.append(restore_statement)
  try:
   sql(statement)
   check(name,context(admin_token).get('code')==expected_code)
  finally:
   sql(restore_statement);restorations.remove(restore_statement)

 failure=None;cleanup_errors=[]
 locks=BASE/'runtime/locks';locks.mkdir(exist_ok=True)
 with (locks/'fixture-mutation.lock').open('w') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  try:
   anonymous=context(None)
   check('anonymous_context_denied',anonymous.get('code') in (401,1510000001))
   admin_token=login(admin);reader_token=login(reader)
   admin_id=int(sql("SELECT id FROM system_users WHERE tenant_id="+str(tenant)+" AND username='"+admin['username']+"' AND deleted=0;"))
   reader_id=int(sql("SELECT id FROM system_users WHERE tenant_id="+str(tenant)+" AND username='"+reader['username']+"' AND deleted=0;"))
   expected_store=int(sql('SELECT store_id FROM print_staff_store WHERE tenant_id='+str(tenant)+' AND user_id='+str(admin_id)+' AND status=0 AND deleted=0;'))
   expected_admin=(tenant,expected_store,admin_id,'STAFF')
   expected_reader=(tenant,expected_store,reader_id,'STAFF')
   check('admin_context_matches_server_mapping',identity(context(admin_token))==expected_admin)
   check('reader_context_matches_own_identity',identity(context(reader_token))==expected_reader)
   override=api('/admin-api/print/context?storeId=99999999&userId=99999998&tenantId=99999997',admin_token)
   check('query_scope_overrides_ignored',identity(override)==expected_admin)
   mapping_id=int(sql('SELECT id FROM print_staff_store WHERE tenant_id='+str(tenant)+' AND store_id='+str(expected_store)+' AND user_id='+str(admin_id)+' AND status=0 AND deleted=0;'))
   where=' WHERE id='+str(mapping_id)+' AND tenant_id='+str(tenant)+' AND store_id='+str(expected_store)+' AND user_id='+str(admin_id)
   mutation_check('disabled_staff_mapping_denied','UPDATE print_staff_store SET status=1'+where+';','UPDATE print_staff_store SET status=0'+where+';',1510000003)
   mutation_check('missing_staff_mapping_denied',"UPDATE print_staff_store SET deleted=b'1'"+where+';',"UPDATE print_staff_store SET deleted=b'0'"+where+';',1510000004)
   mutation_check('disabled_store_denied','UPDATE print_store SET status=1 WHERE tenant_id='+str(tenant)+' AND id='+str(expected_store)+';','UPDATE print_store SET status=0 WHERE tenant_id='+str(tenant)+' AND id='+str(expected_store)+';',1510000007)
   second_store=owned_insert("INSERT INTO print_store(tenant_id,store_code,name,status,creator,updater) VALUES("+str(tenant)+",'"+marker+"','Scope ambiguity fixture',0,'"+marker+"','"+marker+"')",'print_store')
   second_mapping=owned_insert("INSERT INTO print_staff_store(tenant_id,store_id,user_id,status,creator,updater) VALUES("+str(tenant)+','+str(second_store)+','+str(admin_id)+",0,'"+marker+"','"+marker+"')",'print_staff_store')
   check('ambiguous_active_stores_denied',context(admin_token).get('code')==1510000005)
   remove_owned('print_staff_store',second_mapping);remove_owned('print_store',second_store)
   check('context_recovers_after_ambiguity_cleanup',identity(context(admin_token))==expected_admin)
   second_tenant=owned_insert("INSERT INTO system_tenant(name,contact_name,status,websites,package_id,expire_time,account_count,creator,updater) VALUES('"+marker+"','Scope fixture',0,'[]',1,'2099-12-31 23:59:59',1,'"+marker+"','"+marker+"')",'system_tenant')
   other_store=owned_insert("INSERT INTO print_store(tenant_id,store_code,name,status,creator,updater) VALUES("+str(second_tenant)+",'"+marker+"','Other tenant fixture',0,'"+marker+"','"+marker+"')",'print_store')
   check('other_tenant_and_store_fixture_exist',sql('SELECT COUNT(*) FROM print_store s JOIN system_tenant t ON t.id=s.tenant_id WHERE t.id='+str(second_tenant)+' AND s.id='+str(other_store)+' AND t.status=0 AND t.deleted=0;')=='1')
   check('existing_other_tenant_header_denied',context(admin_token,tenant_id=second_tenant).get('code') in (403,1510000003))
   other_query=api('/admin-api/print/context?tenantId='+str(second_tenant)+'&storeId='+str(other_store),admin_token)
   check('existing_other_store_query_cannot_change_scope',identity(other_query)==expected_admin)
   visit=context(admin_token,extra_headers={'visit-tenant-id':str(second_tenant)})
   check('visit_header_cannot_change_scope',visit.get('code') in (403,1510000003) or identity(visit)==expected_admin)
   remove_owned('print_store',other_store);remove_owned('system_tenant',second_tenant)
   check('context_restored_after_all_fixtures',identity(context(admin_token))==expected_admin)
  except Exception as exc:
   failure=str(exc) if isinstance(exc,ScopeCheckError) else type(exc).__name__
  finally:
   for statement in list(reversed(restorations)):
    try:sql(statement);restorations.remove(statement)
    except Exception:cleanup_errors.append('original_scope_restore_failed')
   for table,identifier in list(reversed(fixtures)):
    try:remove_owned(table,identifier)
    except Exception:cleanup_errors.append('owned_fixture_delete_failed')
   for token in tokens:
    try:
     response=api('/admin-api/system/auth/logout',token,body={})
     if response.get('code')!=0:cleanup_errors.append('test_session_logout_failed')
    except Exception:cleanup_errors.append('test_session_logout_failed')
   result['temporary_fixtures_removed']=not fixtures and not restorations
   result['cleanup_errors']=cleanup_errors
   result['passed']=failure is None and not cleanup_errors and result['temporary_fixtures_removed']
   result['failure']=failure
   result['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
   evidence=BASE/'evidence/acceptance';evidence.mkdir(exist_ok=True)
   (evidence/'print-scope-smoke.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result))
 return 0 if result['passed'] else 1
if __name__=='__main__':
 try:sys.exit(main())
 except Exception as exc:
  print('scope_smoke_not_run='+type(exc).__name__+(':'+str(exc) if isinstance(exc,ScopeCheckError) else ''));sys.exit(1)
