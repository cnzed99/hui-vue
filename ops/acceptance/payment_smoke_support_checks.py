#!/usr/bin/env python3
"""AST and mocked HTTP/provenance checks; no database or application process access."""
import sys
sys.dont_write_bytecode = True
import ast, csv, gc, inspect, hashlib, hmac, json, os, pathlib, shutil, stat, tempfile, textwrap, threading, types, weakref
from unittest import mock
import payment_http_smoke as main
import payment_smoke_support as support
redis_credentials_patch=mock.patch.object(support,'redis_environment',return_value={'REDISCLI_AUTH':'synthetic-memory-only'});redis_credentials_patch.start()
results=[]
def check(name,value):
 if not value:raise AssertionError(name)
 results.append({'name':name,'passed':True})
def fails(name,call):
 try:call()
 except support.SupportFailure:check(name,True)
 else:raise AssertionError(name)
class Response:
 status=200
 headers={'Content-Type':'application/json'}
 def __enter__(self):return self
 def __exit__(self,*args):return False
 def read(self):return b'{"code":0,"data":{}}'
requests=[]
with mock.patch.object(support.urllib.request,'urlopen',side_effect=lambda req,**kw:requests.append(req) or Response()):
 support.api('/print/orders',tenant=13)
check('explicit_tenant_header_is_used',requests[0].get_header('Tenant-id')=='13')
check('explicit_scope_http_is_loopback_only',requests[0].full_url=='http://127.0.0.1:48081/admin-api/print/orders')
fails('invalid_tenant_is_rejected',lambda:support.api('/print/orders',tenant=0))
check('six_barrier_callers_complete',support.parallel([lambda:True for _ in range(6)])==[True]*6)
root=pathlib.Path(tempfile.mkdtemp(prefix='payment-support-mock-',dir=main.BASE/'runtime/acceptance'))
try:
 jar=root/'runtime/releases/mockrelease/backend.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'nonexecutable test bytes')
 p=object.__new__(support.Provenance);p.base=root;p.initial={'resolved_immutable_jar':'runtime/releases/mockrelease/backend.jar','jar_sha256':support.digest(jar)};p.processes=[];p.assert_stable=lambda:None
 with mock.patch.object(pathlib.Path,'read_bytes',return_value=('java\0-jar\0'+str(jar)+'\0').encode()):p.observe_process(12345,'mock')
 check('immutable_process_argument_is_attested',p.processes[0]['cmdline_verified'])
 with mock.patch.object(pathlib.Path,'read_bytes',return_value=('java\0-jar\0'+str(root/'runtime/releases/current/backend.jar')+'\0').encode()):fails('current_link_argument_is_rejected',lambda:p.observe_process(12345,'mock'))
 p.initial['jar_sha256']='0'*64
 with mock.patch.object(pathlib.Path,'read_bytes',return_value=('java\0-jar\0'+str(jar)+'\0').encode()):fails('actual_process_jar_hash_mismatch_is_rejected',lambda:p.observe_process(12345,'mock'))
 changed=object.__new__(support.Provenance);changed.initial={'combined_sha256':'before'};changed.snapshot=lambda:{'combined_sha256':'after'}
 fails('source_and_release_drift_is_rejected',changed.assert_stable)
finally:
 assert root.resolve().parent==(main.BASE/'runtime/acceptance').resolve() and not root.is_symlink()
 shutil.rmtree(root)
source=pathlib.Path(main.__file__).read_text();tree=ast.parse(source);patterns=[]
for node in ast.walk(tree):
 if isinstance(node,ast.Call) and isinstance(node.func,ast.Name) and node.func.id=='check' and node.args:
  e=node.args[0]
  if isinstance(e,ast.Constant) and isinstance(e.value,str):patterns.append((e.value,False))
  elif isinstance(e,ast.BinOp) and isinstance(e.op,ast.Add) and isinstance(e.left,ast.Constant):patterns.append((e.left.value,True))
v1=json.loads((main.BASE/'evidence/acceptance/payment-http-smoke-v1.json').read_text())
check('55_historical_assertion_names_remain_in_source_patterns',len(v1['checks'])==55 and all(any(item['name'].startswith(text) if prefix else item['name']==text for text,prefix in patterns) for item in v1['checks']))
check('same_order_same_key_concurrency_is_six', 'range(6)' in source and 'same_key_same_order_parallelism' in source)
check('new_real_scope_and_race_assertions_are_present',all(name in source for name in ['same_scope_two_orders_one_key_one_success_one_conflict','same_scope_shared_key_loser_remains_v1_without_payment_residue','different_owners_same_literal_key_independently_succeed','second_scope_has_all_required_permissions','different_tenant_store_same_literal_key_independently_succeed','different_tenant_store_different_keys_independently_succeed','same_tenant_other_store_fixture_context']))
class ScopeMock:
 def __init__(self,flaw=None):
  self.orders={'original':{'scope':(1,1,1),'paid':False}};self.context={'admin':(1,1,1),'other':(1,1,2),'scope':(2,2,22)};self.keys={};self.lock=threading.Lock();self.flaw=flaw;self.executed=[]
 def new_order(self,token,*args):
  with self.lock:
   identifier='order'+str(len(self.orders));self.orders[identifier]={'scope':self.context[token],'paid':False}
  return {'id':identifier}
 def visible(self,token,order,write=False):
  current=self.context[token];stored=self.orders[order]['scope']
  if self.flaw=='all_scope_missing':return True
  omit_store=self.flaw=='store_leak' or (write and self.flaw=='store_write_leak')
  # The store mutant retains BOTH tenant and owner predicates.
  return current[0]==stored[0] and current[2]==stored[2] and (omit_store or current[1]==stored[1])
 def pay(self,token,order,key=None):
  with self.lock:
   scope=self.context[token]
   if not self.visible(token,order,write=True):return 404,{'code':1510040003},{}
   key=key or str(__import__('uuid').uuid4());namespace=(scope,key)
   if self.flaw!='duplicate_key' and namespace in self.keys and self.keys[namespace]!=order:return 409,{'code':1510040004},{}
   if self.orders[order]['paid']:return 409,{'code':1510040005},{}
   self.orders[order]['paid']=True;self.keys[namespace]=order
   return 200,{'code':0,'data':{'orderId':order}},{}
 def api(self,path,token=None,**kwargs):
  scope=self.context[token]
  if path=='/print/context':return 200,{'code':0,'data':dict(zip(('tenantId','storeId','userId'),scope))},{}
  if path=='/system/auth/get-permission-info':return 200,{'code':0,'data':{'permissions':list(support.REQUIRED_PERMISSIONS)}},{}
  order=path.split('/')[3]
  if path.endswith('/simulate-payment'):return self.pay(token,order,kwargs.get('key'))
  if self.visible(token,order):return 200,{'code':0,'data':{'orderId':order}},{}
  return 404,{'code':1510040003 if path.endswith('/payment') else 1510030009},{}
 def move(self,user):self.context['other']=(1,3,2);return 3
 def assertion(self,name,value):
  if not value:raise main.Failure(name)
  self.executed.append(name)
 def run(self):
  fixture=types.SimpleNamespace(tenant=2,store=2,user=22,own_order=lambda token,*args:self.new_order(token),move_fixture_owner_store=self.move,capture_fixture_caches=lambda required:None)
  with mock.patch.multiple(main,fixtures=fixture,other_user=2,provenance=types.SimpleNamespace(assert_stable=lambda:None),new_order=self.new_order,pay=self.pay,check=self.assertion,counts=lambda order:[1,2,1] if self.orders[order]['paid'] else [0,0,0],empty_payment=lambda order:not self.orders[order]['paid']),mock.patch.object(main.f,'api',side_effect=self.api),mock.patch.object(main.q,'ready_file',return_value='other-file'),mock.patch.object(support,'api',side_effect=self.api):
   main.added_scope_and_concurrency('admin','other','admin-file','profile','scope','original')
scenario=ScopeMock();scenario.run()
check('16_new_scope_and_race_assertions_execute_under_mock',len(scenario.executed)==16)
mutant=ScopeMock('store_leak');owned=mutant.new_order('other')['id'];mutant.move(2)
check('store_mutant_retains_tenant_and_owner_and_exposes_only_own_old_store',mutant.visible('other',owned) and not mutant.visible('other','original') and not mutant.visible('scope',owned))
for flaw,expected in [('duplicate_key','same_scope_two_orders_one_key_one_success_one_conflict'),('all_scope_missing','cross_tenant_store_order_hidden_get'),('store_leak','same_tenant_other_store_order_hidden_get'),('store_write_leak','same_tenant_other_store_payment_hidden_post')]:
 try:ScopeMock(flaw).run()
 except main.Failure as failure:check('mock_detects_'+flaw,str(failure)==expected)
 else:raise AssertionError('mock_did_not_detect_'+flaw)

class RedisMock:
 def __init__(self,state=None,database=0,deny_delete=False):self.state=dict(state or {});self.database=database;self.deny_delete=deny_delete;self.calls=[]
 def run(self,argv,**kwargs):
  self.calls.append((list(argv),kwargs['input']))
  if argv[-1]=='PING':return types.SimpleNamespace(returncode=0,stdout='PONG\n')
  db=int(argv[argv.index('-n')+1]);state=self.state if db==self.database else {};values=[]
  for line in kwargs['input'].splitlines():
   command,key=line.split(' ',1)
   if command=='EXISTS':values.append(str(int(key in state)))
   elif command=='DEL':
    present=int(key in state);values.append(str(present if not self.deny_delete else 0))
    if not self.deny_delete:state.pop(key,None)
   else:raise AssertionError('unexpected_mock_redis_command')
  return types.SimpleNamespace(returncode=0,stdout='\n'.join(values)+'\n')
access='oauth2_access_token:'+'a'*32;refresh='oauth2_access_token:'+'b'*32
redis=RedisMock({access:True});tracker=support.ExactRedisEvidence()
with mock.patch.object(support.subprocess,'run',side_effect=redis.run):
 tracker.observe([access,refresh],'sessions',required=[access])
 tracker.remove([access,refresh],'sessions',logout=lambda:redis.state.pop(access,None))
 summary=tracker.summary('sessions')
check('session_counts_real_baseline_logout_deletion_and_optional_not_generated',summary=={'candidate_count':2,'actual_existed':1,'actual_deleted':1,'removed_by_logout':1,'removed_by_explicit_delete':0,'final_remaining':0,'not_generated':1,'required_baselines_verified':1,'status':'VERIFIED'})
check('redis_tokens_never_enter_argv_or_public_summary','a'*32 not in json.dumps(summary) and all('a'*32 not in ' '.join(argv) for argv,_ in redis.calls))
for label,server,key in [('wrong_prefix',RedisMock({access:True}),'incorrect_access_prefix:'+'a'*32),('wrong_database',RedisMock({access:True},database=1),access),('always_absent',RedisMock(),access)]:
 tracker=support.ExactRedisEvidence()
 with mock.patch.object(support.subprocess,'run',side_effect=server.run):
  fails('redis_'+label+'_cannot_pass_required_baseline',lambda:tracker.observe([key],'sessions',required=[key]))
  fails('redis_'+label+'_cannot_report_cleanup_verified',lambda:tracker.summary('sessions'))
server=RedisMock();tracker=support.ExactRedisEvidence()
with mock.patch.object(support.subprocess,'run',side_effect=server.run):
 tracker.observe([refresh],'sessions');tracker.remove([refresh],'sessions');optional=tracker.summary('sessions')
check('absent_optional_key_is_not_claimed_as_deleted',optional['status']=='NOT_GENERATED' and optional['actual_existed']==optional['actual_deleted']==0 and optional['not_generated']==1)
server=RedisMock({access:True},deny_delete=True);tracker=support.ExactRedisEvidence()
with mock.patch.object(support.subprocess,'run',side_effect=server.run):
 tracker.observe([access],'sessions',required=[access]);fails('failed_exact_delete_with_remaining_key_is_detected',lambda:tracker.remove([access],'sessions'))
# Independent, source-derived canonical keys; do not derive mock expectations from cache_candidates().
cache_state={'user_role_ids:22':True,'user_role_ids:12':True,'role:2:222':True}
for menu in [2001,2002,2003,2005,2006,2008]:cache_state['menu_role_ids:2:'+str(menu)]=True
fixture=support.Fixtures(main.BASE,lambda statement:'', 'paytestmock123');fixture.tenant=2;fixture.user=22;fixture.role=222;fixture.identities=[(2,22),(1,12)]
fixture.permission_menus=dict(zip(support.REQUIRED_PERMISSIONS,[2001,2002,2003,2004,2005,2006,2008]))
server=RedisMock(cache_state)
with mock.patch.object(support.subprocess,'run',side_effect=server.run):
 fixture.capture_fixture_caches(required=True);fixture.cleanup_caches()
summary=fixture.cleaned['redis_fixture_caches']
check('canonical_fixture_cache_baselines_and_actual_deletion_counts',summary['candidate_count']==10 and summary['actual_existed']==summary['actual_deleted']==summary['required_baselines_verified']==9 and summary['not_generated']==1 and summary['final_remaining']==0)
# Repair 2: only temporary external keys and mocked SQL/HTTP/Redis are used.
run_id='a'*32
with mock.patch.object(support.subprocess,'run',return_value=types.SimpleNamespace(returncode=0,stdout='NOAUTH Authentication required.\n')):
 fails('redis_noauth_exit_zero_fails_preflight',lambda:support.redis_auth_preflight(run_id))
for label,output,exit_code in [('acl_rejects_exists','NOPERM command denied\n',0),('extra_exists_reply','0\n0\n',0),('exists_nonzero_exit','0\n',1),('run_key_already_exists','1\n',0),('nonnumeric_exists_reply','invalid\n',0)]:
 def preflight_mock(argv,_output=output,_code=exit_code,**kwargs):
  return types.SimpleNamespace(returncode=0,stdout='PONG\n') if argv[-1]=='PING' else types.SimpleNamespace(returncode=_code,stdout=_output)
 with mock.patch.object(support.subprocess,'run',side_effect=preflight_mock):fails('preflight_'+label,lambda:support.redis_auth_preflight(run_id))
check('read_only_preflight_precedes_key_init_and_fixtures',source.index('support.redis_auth_preflight(run_id)')<source.index('support.ledger_hmac_key(BASE,initialize=True)')<source.index('provenance=support.Provenance(BASE)'))
real_key_path=main.BASE/'runtime/secrets/acceptance-ledger-hmac.key';real_key_existed=real_key_path.exists()
ledger_scratch=pathlib.Path(tempfile.mkdtemp(prefix='payment-ledger-mock-',dir=main.BASE/'runtime/acceptance'))
try:
 fake_base=ledger_scratch;acceptance=fake_base/'runtime/acceptance';acceptance.mkdir(parents=True,mode=0o700)
 (fake_base/'runtime/secrets').mkdir(mode=0o700);(fake_base/'runtime/locks').mkdir(mode=0o700)
 support.ledger_hmac_key(fake_base,initialize=True)
 key_path=fake_base/'runtime/secrets/acceptance-ledger-hmac.key'
 key_digest=support.digest(key_path)
 check('mock_external_key_is_0600_and_32_bytes',key_path.stat().st_mode&0o777==0o600 and key_path.stat().st_size==32)
 support.ledger_hmac_key(fake_base,initialize=True)
 check('initialization_never_rotates_existing_key',support.digest(key_path)==key_digest)
 marker='paytestledger123';script_sha='d'*64
 class FakeSQL:
  def __init__(self,fail_capture=False,fixture_remaining=0):self.fail_capture=fail_capture;self.fixture_remaining=fixture_remaining;self.calls=[]
  def __call__(self,statement):
   self.calls.append(statement)
   if statement.startswith('SELECT CONCAT'):
    if self.fail_capture:raise support.SupportFailure('synthetic_sql_query_failure')
    return ('12|'+'d'*32) if 'c'*32 in statement else ('7|'+'b'*32)
   if statement.startswith('SELECT COUNT') and "WHERE creator='" in statement:return str(self.fixture_remaining)
   if statement.startswith('SELECT COUNT'):return '0'
   return ''
 def make_fixture(character,db):
  run=character*32;batch=acceptance/('payment-http-'+run);batch.mkdir(mode=0o700)
  ledger=support.SessionLedger(batch,run,marker,script_sha);fixture=support.Fixtures(fake_base,db,marker,ledger=ledger)
  fixture.tenant=2;fixture.store=2;fixture.user=22;fixture.role=222;fixture.identities=[(2,22),(1,12)]
  fixture.permission_menus=dict(zip(support.REQUIRED_PERMISSIONS,[2001,2002,2003,2004,2005,2006,2008]));fixture.sync_ledger()
  return fixture,ledger
 payload={'accessToken':'a'*32,'refreshToken':'b'*32};second={'accessToken':'c'*32,'refreshToken':'d'*32}
 db=FakeSQL();fixture,ledger=make_fixture('a',db);server=RedisMock(dict(cache_state,**{access:True,'oauth2_access_token:'+'c'*32:True}))
 check('signed_ledger_directory_and_file_modes_are_private',ledger.path.parent.stat().st_mode&0o777==0o700 and ledger.path.stat().st_mode&0o777==0o600)
 with mock.patch.object(support.subprocess,'run',side_effect=server.run):
  fixture.register_login(payload,1,7,'admin');fixture.register_login(second,1,12,'same_owner');fixture.capture_fixture_caches(required=True)
 envelope=json.loads(ledger.path.read_text());body=envelope['payload']
 check('signed_payload_persists_fixture_identifiers_and_session_cache_baselines',body['fixtures']['role']==222 and len(body['fixtures']['identities'])==2 and body['cache_baseline_complete'] and len(body['sessions'])==2 and len(body['baseline_entries'])==14)
 expected_signature=hmac.new(support.ledger_hmac_key(fake_base),support.SessionLedger.DOMAIN+support.SessionLedger.canonical(body),hashlib.sha256).hexdigest()
 check('whole_payload_has_domain_separated_hmac',hmac.compare_digest(envelope['hmac_sha256'],expected_signature))
 record_path=ledger.path;record_run='a'*32;fixture_weak=weakref.ref(fixture);ledger_weak=weakref.ref(ledger)
 del fixture,ledger,envelope,body,expected_signature;gc.collect()
 check('all_original_fixture_and_ledger_objects_are_destroyed',fixture_weak() is None and ledger_weak() is None)
 attempts=[]
 def logout(*args,**kwargs):
  attempts.append(1)
  for key in (access,'oauth2_access_token:'+'c'*32):server.state.pop(key,None)
  return 200,{'code':0},{}
 with mock.patch.object(support.subprocess,'run',side_effect=server.run),mock.patch.object(support,'api',side_effect=logout):
  recovered=support.recover_owned_sessions(fake_base,run_id=record_run,sql=db)
  repeated=support.recover_owned_sessions(fake_base,run_id=record_run,sql=db)
 check('disk_and_external_key_only_recovery_restores_sessions_caches_and_sql',recovered['status']=='EXACT_OWNED_CLEANUP_VERIFIED' and recovered['cleanup']['redis_fixture_caches']['actual_deleted']==9 and recovered['cleanup']['owned_session_pairs_removed']==2 and recovered['cleanup_absence_verified'])
 check('repeated_signed_recovery_is_idempotent',repeated['status']==recovered['status'] and repeated['ledger_sha256']==recovered['ledger_sha256'] and len(attempts)==4)
 check('recovery_is_cleanup_only_not_business_acceptance',repeated['acceptance_status']=='NOT_RUN')
 check('recovery_has_only_derived_owned_keys',all(line.split(' ',1)[1] in {access,refresh,'oauth2_access_token:'+'c'*32,'oauth2_access_token:'+'d'*32,*cache_state,'menu_role_ids:2:2004','acceptance-preflight:'+record_run} for argv,text in server.calls if text for line in text.splitlines()))
 with support.fixture_lock(fake_base):
  before=len(db.calls)
  try:support.recover_owned_sessions(fake_base,run_id=record_run,sql=db)
  except BlockingIOError:check('recovery_respects_same_fixture_lock_without_sql_side_effects',len(db.calls)==before)
  else:raise AssertionError('shared_lock_not_enforced')
 raw=record_path.read_bytes();original=json.loads(raw)
 changed=json.loads(raw);changed['payload']['fixtures']['tenant']=99;record_path.write_text(json.dumps(changed))
 with mock.patch.object(support.SessionLedger,'validate_body',side_effect=AssertionError('must_authenticate_first')) as validate:
  fails('payload_identity_tamper_fails_before_identifier_parsing',lambda:support.SessionLedger.recover(fake_base,record_path,run_id=record_run))
  check('unauthenticated_identifiers_are_never_validated_or_derived',validate.call_count==0)
 record_path.write_bytes(raw)
 wrong_path=fake_base/'elsewhere.json'
 fails('signed_record_path_outside_fixed_batch_rejected',lambda:support.SessionLedger.recover(fake_base,wrong_path,run_id=record_run))
 fails('signed_record_wrong_run_identity_rejected',lambda:support.SessionLedger.recover(fake_base,record_path,run_id='b'*32))
 record_path.chmod(0o644);fails('signed_record_loose_mode_rejected',lambda:support.SessionLedger.recover(fake_base,record_path,run_id=record_run));record_path.chmod(0o600)
 saved_record=record_path.with_name('saved.json');record_path.rename(saved_record);record_path.symlink_to(saved_record.name)
 fails('signed_record_symlink_rejected',lambda:support.SessionLedger.recover(fake_base,record_path,run_id=record_run));record_path.unlink();saved_record.rename(record_path)
 saved_key=key_path.with_name('saved-key');key_path.rename(saved_key)
 fails('missing_external_key_fails_without_regeneration',lambda:support.SessionLedger.recover(fake_base,record_path,run_id=record_run))
 check('recovery_does_not_recreate_missing_key',not key_path.exists());saved_key.rename(key_path)
 key_path.chmod(0o644);fails('external_key_loose_mode_rejected',lambda:support.ledger_hmac_key(fake_base));key_path.chmod(0o600)
 key_path.rename(saved_key);key_path.write_bytes(b'x'*32);key_path.chmod(0o600)
 fails('replaced_external_key_cannot_authenticate_signed_record',lambda:support.SessionLedger.recover(fake_base,record_path,run_id=record_run));key_path.unlink();saved_key.rename(key_path)
 key_path.rename(saved_key);key_path.write_bytes(b'x'*31);key_path.chmod(0o600)
 fails('external_key_wrong_length_rejected_without_rotation',lambda:support.ledger_hmac_key(fake_base));key_path.unlink();saved_key.rename(key_path)
 with mock.patch.object(support.os,'fstat',return_value=types.SimpleNamespace(st_mode=stat.S_IFREG|0o600,st_uid=os.getuid()+1,st_size=32)):
  fails('external_key_open_fstat_owner_is_enforced',lambda:support.ledger_hmac_key(fake_base))
 # Even authenticated but structurally invalid records cannot create arbitrary targets.
 def write_signed(body):
  signature=hmac.new(support.ledger_hmac_key(fake_base),support.SessionLedger.DOMAIN+support.SessionLedger.canonical(body),hashlib.sha256).hexdigest()
  record_path.write_bytes(support.SessionLedger.canonical({'format':'payment-ledger-hmac-v2','payload':body,'hmac_sha256':signature})+b'\n')
 changed=json.loads(raw)['payload'];changed['baseline_entries'][0]['key']='foreign:unowned';write_signed(changed)
 fails('signed_arbitrary_cache_key_is_rejected',lambda:support.SessionLedger.recover(fake_base,record_path,run_id=record_run));record_path.write_bytes(raw)
 changed=json.loads(raw)['payload'];changed['fixtures']['tenant']=1;write_signed(changed)
 fails('signed_official_tenant_fixture_identity_is_rejected',lambda:support.SessionLedger.recover(fake_base,record_path,run_id=record_run));record_path.write_bytes(raw)
 loaded=support.SessionLedger.recover(fake_base,record_path,run_id=record_run);loaded.body['cleanup']['caches_verified']=False;loaded.persist()
 fails('ledger_cannot_be_removed_before_cache_proof',loaded.remove_verified)
 loaded.body['cleanup']['caches_verified']=True;loaded.body['cleanup']['sql_verified']=False;loaded.persist()
 fails('ledger_cannot_be_removed_before_corresponding_sql_proof',loaded.remove_verified)
 del loaded
 # Without original baselines, exact zero after cleanup is still UNVERIFIED.
 db2=FakeSQL(fail_capture=True);fixture2,ledger2=make_fixture('b',db2)
 fails('sql_failure_occurs_after_signed_login_response_is_durable',lambda:fixture2.register_login(payload,1,7,'admin'))
 check('unverified_response_is_in_signed_disk_record',len(json.loads(ledger2.path.read_text())['payload']['sessions'])==1)
 del fixture2,ledger2;gc.collect();db2.fail_capture=False;server=RedisMock({access:True});attempts.clear()
 with mock.patch.object(support.subprocess,'run',side_effect=server.run),mock.patch.object(support,'api',side_effect=logout):
  unverified=support.recover_owned_sessions(fake_base,run_id='b'*32,sql=db2)
 check('missing_baselines_never_become_green_from_current_zero',unverified['status']=='UNVERIFIED' and unverified['cleanup_absence_verified'] and not unverified['baseline_verified'] and unverified['ledger_retained'])
 unverified_ledger=support.SessionLedger.recover(fake_base,acceptance/('payment-http-'+'b'*32)/'private/sessions.json',run_id='b'*32)
 fails('unverified_baseline_prevents_ledger_deletion',unverified_ledger.remove_verified);del unverified_ledger
 # Redis or disk failure still attempts logout; record remains for independent recovery.
 db3=FakeSQL();fixture3,ledger3=make_fixture('c',db3);attempts.clear()
 with mock.patch.object(ledger3,'persist',side_effect=support.SupportFailure('synthetic_disk_failure')),mock.patch.object(support.subprocess,'run',side_effect=server.run),mock.patch.object(support,'api',side_effect=logout):
  fails('ledger_write_failure_rejects_login',lambda:fixture3.register_login(payload,1,7,'admin'))
  fails('ledger_write_failure_is_not_hidden_during_cleanup',fixture3.cleanup_sessions)
 check('ledger_write_failure_still_attempts_exact_logout',len(attempts)==1)
 db4=FakeSQL(fail_capture=True);fixture4,ledger4=make_fixture('d',db4)
 fails('sql_failure_preserves_signed_record_before_baseline',lambda:fixture4.register_login(payload,1,7,'admin'));db4.fail_capture=False;attempts.clear()
 with mock.patch.object(support.subprocess,'run',return_value=types.SimpleNamespace(returncode=0,stdout='NOAUTH Authentication required.\n')),mock.patch.object(support,'api',side_effect=logout):fails('redis_failure_is_reported_independently',fixture4.cleanup_sessions)
 check('redis_failure_attempts_logout_and_retains_signed_ledger',len(attempts)==1 and ledger4.path.exists() and fixture4.cleaned['redis_sessions']['status']=='UNVERIFIED')
 # A stale prior SQL success cannot authorize deletion when SQL rows now remain.
 db.fixture_remaining=1;server=RedisMock()
 with mock.patch.object(support.subprocess,'run',side_effect=server.run),mock.patch.object(support,'api',side_effect=logout):
  sql_unverified=support.recover_owned_sessions(fake_base,run_id=record_run,sql=db)
 check('corresponding_fixture_sql_remaining_keeps_record_unverified',sql_unverified['status']=='UNVERIFIED' and not sql_unverified['cleanup_absence_verified'] and sql_unverified['ledger_retained'])
finally:
 assert ledger_scratch.resolve().parent==(main.BASE/'runtime/acceptance').resolve() and not ledger_scratch.is_symlink()
 shutil.rmtree(ledger_scratch)
check('real_hmac_key_was_not_initialized_or_changed_by_mocks',real_key_path.exists()==real_key_existed)
check('old_failed_attempt_and_open_orphan_remain_explicit',"'attempt_name':'payment-http-v5'" in source and "'status':'OPEN'" in source and '28b7e94aea0a40ce29e17093eb57fdf2863d0bd8d4399728c85bb8b0856f9c39' in source)
# Permission-fixture repair 1: faithful ancestor filtering and pre-write gates.
class PermissionFixtureSQL:
 def __init__(self,defect=None):
  mapping={2001:'print:file:query',2002:'print:file:upload',2004:'print:quote:create',2005:'print:quote:query',2006:'print:order:create',2007:'print:order:query',2008:'print:payment:simulate'}
  self.nodes={key:{'id':key,'parent':2000,'permission':value,'type':3,'status':0,'deleted':0} for key,value in mapping.items()}
  self.nodes[2000]={'id':2000,'parent':0,'permission':'','type':1,'status':0,'deleted':0}
  self.allowed=set(self.nodes);self.package_status=0;self.package_deleted=0;self.writes=[];self.next_id=10
  if defect=='missing_ancestor':self.nodes.pop(2000)
  elif defect=='disabled_ancestor':self.nodes[2000]['status']=1
  elif defect=='deleted_ancestor':self.nodes[2000]['deleted']=1
  elif defect=='outside_package':self.allowed.remove(2000)
  elif defect=='duplicate_permission':
   self.nodes[2999]=dict(self.nodes[2008],id=2999);self.allowed.add(2999)
  elif defect=='cycle':self.nodes[2000]['parent']=2001
  elif defect=='nonempty_ancestor_permission':self.nodes[2000]['permission']='unapproved:ancestor'
  elif defect=='disabled_package':self.package_status=1
  elif defect=='deleted_leaf':self.nodes[2008]['deleted']=1
  elif defect=='extra_ancestor':
   self.nodes[2000]['parent']=3000;self.nodes[3000]={'id':3000,'parent':0,'permission':'','type':1,'status':0,'deleted':0};self.allowed.add(3000)
 def __call__(self,statement):
  if statement.startswith('SELECT status,CAST(deleted AS UNSIGNED),menu_ids'):
   return str(self.package_status)+'\t'+str(self.package_deleted)+'\t'+json.dumps(sorted(self.allowed))
  if statement.startswith('SELECT id,parent_id,permission,type,status,CAST(deleted AS UNSIGNED)'):
   if "WHERE permission='" in statement:
    permission=statement.split("WHERE permission='",1)[1].split("'",1)[0];rows=[n for n in self.nodes.values() if n['permission']==permission]
   else:
    identifier=int(statement.split('WHERE id=',1)[1].rstrip(';'));rows=[self.nodes[identifier]] if identifier in self.nodes else []
   return '\n'.join('\t'.join(str(node[key]) for key in ('id','parent','permission','type','status','deleted')) for node in rows)
  if statement.startswith('SELECT'):raise AssertionError('unexpected_metadata_read')
  self.writes.append(statement)
  if 'LAST_INSERT_ID' in statement:self.next_id+=1;return str(self.next_id)
  return ''

def filtered_permission_names(nodes,grant_ids):
 menu_map={identifier:nodes[identifier] for identifier in grant_ids};disabled_cache=set()
 def disabled(node):
  if node['id'] in disabled_cache:return True
  if node['status']!=0:disabled_cache.add(node['id']);return True
  if node['parent']==0:return False
  parent=menu_map.get(node['parent'])
  if parent is None or disabled(parent):disabled_cache.add(node['id']);return True
  return False
 return {node['permission'] for node in menu_map.values() if not disabled(node) and node['permission']}
metadata=PermissionFixtureSQL();leaf_ids=set(metadata.nodes)-{2000}
check('faithful_filter_seven_leaves_without_parent_yields_zero_permissions',filtered_permission_names(metadata.nodes,leaf_ids)==set())
check('faithful_filter_adding_ancestor_2000_retains_all_seven_permissions',filtered_permission_names(metadata.nodes,leaf_ids|{2000})==set(support.REQUIRED_PERMISSIONS))
fixture=support.Fixtures(main.BASE,metadata,'paytestpermission123');fixture.seed_scope(1)
grant_sql=[statement for statement in metadata.writes if statement.startswith('INSERT INTO system_role_menu')]
expected_ids=sorted(leaf_ids|{2000})
check('generated_role_grant_is_exact_seven_leaves_plus_2000',len(grant_sql)==1 and 'id IN ('+','.join(map(str,expected_ids))+')' in grant_sql[0] and len(expected_ids)==8)
check('ancestor_does_not_expand_permission_cache_candidates',len(fixture.permission_menus)==7 and 2000 not in fixture.permission_menus.values() and all(not key.endswith(':2000') for key in fixture.cache_candidates()[0]))
check('fixture_permission_fix_never_updates_existing_roles_or_packages',all(statement.startswith('INSERT INTO ') for statement in metadata.writes) and not any('UPDATE ' in statement for statement in metadata.writes))
for defect in ['missing_ancestor','disabled_ancestor','deleted_ancestor','outside_package','duplicate_permission','cycle','nonempty_ancestor_permission','disabled_package','deleted_leaf','extra_ancestor']:
 metadata=PermissionFixtureSQL(defect);fixture=support.Fixtures(main.BASE,metadata,'paytestpermission123')
 fails('permission_preflight_rejects_'+defect,lambda:fixture.seed_scope(1))
 check('permission_preflight_'+defect+'_fails_before_any_fixture_write',metadata.writes==[])
check('new_attempt_v5_preserves_prior_failure_references',"payment-http-smoke-v5.json" in source and "'permission_fixture_repair_round':1" in source and '1af6d3a14475b9e545e815a723bfe09b696d07bc752c76622af729a5d01cb746' in source)

# Profile-fixture repair 1: exercise the real creator and the actual unique tuple.
class DuplicateProfileVersion(support.SupportFailure):pass
class ProfileFixtureSQL:
 def __init__(self):self.rows=[];self.unique=set();self.deletes=[]
 def __call__(self,statement):
  if statement.startswith('DELETE FROM '):self.deletes.append(statement);return ''
  prefix='INSERT INTO print_price_profile('
  if not statement.startswith(prefix):raise AssertionError('unexpected_profile_fixture_sql')
  columns,values=statement[len(prefix):].split(') VALUES(',1)
  fields=next(csv.reader([values.removesuffix(');')],quotechar="'",skipinitialspace=True))
  row=dict(zip(columns.split(','),fields));assert len(row)==len(fields)==11
  unique=tuple(row[key] for key in ('tenant_id','store_id','profile_key','version'))
  if unique in self.unique:raise DuplicateProfileVersion('mock_mysql_1062_uk_print_price_version')
  assert len(row['version'])<=64
  self.unique.add(unique);self.rows.append(row);return ''
class ProfileFixtureHTTP:
 def __init__(self):self.calls=[]
 def __call__(self,path,token=None,body=None,*args,**kwargs):
  assert kwargs['tenant']==2;self.calls.append(path)
  if path=='/print/files':payload={'id':str(support.uuid.uuid4())}
  elif path.startswith('/print/files/'):payload={'status':'READY'}
  elif path in ('/print/quotes','/print/orders'):payload={'id':str(support.uuid.uuid4())}
  else:raise AssertionError('unexpected_profile_fixture_http')
  return 200,{'code':0,'data':payload},{}
def exercise_original_creator(creator=None):
 sql=ProfileFixtureSQL();http=ProfileFixtureHTTP();fixture=support.Fixtures(main.BASE,sql,'paytestprofile123');fixture.tenant=2;fixture.store=3
 if creator is not None:fixture.own_order=types.MethodType(creator,fixture)
 with mock.patch.object(support,'api',side_effect=http):
  first=fixture.own_order('synthetic',main.data,lambda *args:b'%PDF-synthetic')
  try:second=fixture.own_order('synthetic',main.data,lambda *args:b'%PDF-synthetic')
  except DuplicateProfileVersion:return fixture,sql,http,True
 return fixture,sql,http,False
fixture,sql,http,duplicate=exercise_original_creator()
check('original_own_order_twice_succeeds_under_real_four_column_unique_constraint',not duplicate and len(sql.rows)==2 and len(sql.unique)==2 and len(fixture.business['orders'])==2 and http.calls.count('/print/orders')==2)
check('each_profile_version_is_stable_uuid_derived_and_within_schema_limit',all(row['version']=='v1-'+row['id'] and len(row['version'])<=64 for row in sql.rows) and sql.rows[0]['profile_key']==sql.rows[1]['profile_key']=='paytestprofile123' and sql.rows[0]['tenant_id']==sql.rows[1]['tenant_id'] and sql.rows[0]['store_id']==sql.rows[1]['store_id'])
fixture.cleanup_business()
profile_deletes=[statement for statement in sql.deletes if statement.startswith('DELETE FROM print_price_profile ')]
check('unique_version_fix_preserves_marker_id_tenant_exact_cleanup',len(profile_deletes)==2 and all("id='"+row['id']+"' AND profile_key='paytestprofile123' AND tenant_id=2;" in statement for row,statement in zip(sql.rows,profile_deletes)))
creator_source=textwrap.dedent(inspect.getsource(support.Fixtures.own_order))
fixed_version_fragment='self.marker + "\',\'v1-" + profile + "\',\'DEMO\''
old_version_fragment='self.marker + "\',\'v1\',\'DEMO\''
assert creator_source.count(fixed_version_fragment)==1
namespace={};exec(compile(creator_source.replace(fixed_version_fragment,old_version_fragment),'<isolated-fixed-v1-mutant>','exec'),support.__dict__,namespace)
fixture,sql,http,duplicate=exercise_original_creator(namespace['own_order'])
check('real_creator_fixed_v1_mutant_fails_second_insert_at_unique_index',duplicate and len(sql.rows)==1 and len(fixture.business['files'])==2 and len(fixture.business['profiles'])==2 and len(fixture.business['orders'])==1 and http.calls.count('/print/quotes')==1)

files={}
for name in ['payment_http_smoke.py','payment_smoke_support.py','payment_smoke_support_checks.py']:
 path=main.BASE/'ops/acceptance'/name;ast.parse(path.read_text());files[name]=hashlib.sha256(path.read_bytes()).hexdigest()
report={'passed':True,'finished_utc':support.utc(),'scope':'AST and mocked HTTP/provenance only; no DB, real HTTP, build or application process start','checks':results,'check_count':len(results),'real_acceptance':'NOT_RUN','repair_round':1,'review_findings':'P2 store-axis masking and Redis absent-key false cleanup evidence','reviewed_old_manifest_sha256':'411f7b7e8ae2aebb0eef11fe8203748f7cabdc82315f3a14c0969dccad283945','auth_ledger_repair_round':2,'permission_fixture_repair_round':1,'profile_fixture_repair_round':1,'prior_v4_failed_report_sha256':'6a4ee7c3cb087e05de1359272d79381741d1616205c143d9157d2163802598fb','prior_v3_failed_report_sha256':'1af6d3a14475b9e545e815a723bfe09b696d07bc752c76622af729a5d01cb746','old_failed_report_sha256':'28b7e94aea0a40ce29e17093eb57fdf2863d0bd8d4399728c85bb8b0856f9c39','files':files}
(main.BASE/'evidence/acceptance/payment-smoke-v5-static-profile-r1.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
