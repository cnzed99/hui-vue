#!/usr/bin/env python3
"""Synthetic real-HTTP payment transaction acceptance. No real payment or printing."""
import sys
sys.dont_write_bytecode=True
import argparse,concurrent.futures,decimal,fcntl,json,os,pathlib,re,shlex,shutil,subprocess,tempfile,time,uuid
import file_http_smoke as f
import quote_http_smoke as q
import order_http_smoke as o
import payment_smoke_support as support
BASE=f.BASE
checks=[];cleanup=[];triggers=set();other_user=None
marker='paytest'+uuid.uuid4().hex[:12]
fixtures=None;provenance=None
tables=['print_job','print_simulated_journal','print_payment_receipt','print_order_event','print_order_item','print_order','print_quote','print_price_profile','print_file']
class Failure(Exception):pass
def check(name,value):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise Failure(name)
def data(result):
 if result[0]!=200 or not isinstance(result[1],dict) or result[1].get('code')!=0:
  raise Failure('unexpected_success_response_'+str(result[0])+'_'+str(result[1].get('code') if isinstance(result[1],dict) else 'non_json'))
 return result[1]['data']
def pay(token,order,key=None,body=None,suffix=''):
 return f.api('/print/orders/'+order+'/simulate-payment'+suffix,token,body={} if body is None else body,key=key or str(uuid.uuid4()))
def new_order(token,file_id,profile,copies=2,side='DUPLEX'):
 quote=data(q.quote(token,file_id,profile,copies,side))
 return data(o.create(token,quote['id']))
def counts(order):
 return [int(f.sql("SELECT COUNT(*) FROM "+t+" WHERE order_id='"+order+"';")) for t in ['print_payment_receipt','print_simulated_journal','print_job']]
def empty_payment(order):
 return counts(order)==[0,0,0] and f.sql("SELECT CONCAT(payment_state,'|',print_state,'|',version) FROM print_order WHERE id='"+order+"';")=='UNPAID|NOT_CREATED|1' and f.sql("SELECT COUNT(*) FROM print_order_event WHERE order_id='"+order+"';")=='1' and f.sql("SELECT CONCAT(event_type,'|',aggregate_version) FROM print_order_event WHERE order_id='"+order+"';")=='ORDER_CREATED|1'
def app_denied(statement,expected):
 env=dict(os.environ)
 for line in (BASE/'runtime/secrets/runtime.env').read_text().splitlines():
  if line.startswith('PRINT_DB_PASSWORD='):env['MYSQL_PWD']=shlex.split(line.split('=',1)[1])[0]
 if 'MYSQL_PWD' not in env:raise Failure('test_database_secret_missing')
 result=subprocess.run(['mysql','-h','127.0.0.1','-u','print_app','-N','-B','print_demo'],input='START TRANSACTION; '+statement+' ROLLBACK;',env=env,text=True,capture_output=True)
 env.pop('MYSQL_PWD',None)
 return result.returncode!=0 and '1644 (45000)' in result.stderr and expected in result.stderr
def install_trigger(table,order,condition='',action="SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic payment rollback'"):
 name='acpt_pay_'+uuid.uuid4().hex[:16];triggers.add(name)
 field='id' if table=='print_order' else 'order_id'
 event='UPDATE' if table=='print_order' else 'INSERT'
 f.sql("DELIMITER $$\nCREATE TRIGGER "+name+" BEFORE "+event+" ON "+table+" FOR EACH ROW BEGIN IF NEW."+field+"='"+order+"'"+condition+" THEN "+action+"; END IF; END$$\nDELIMITER ;\n")
 return name
def drop_trigger(name):
 f.sql('DROP TRIGGER IF EXISTS '+name+';')
 if f.sql("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='print_demo' AND TRIGGER_NAME='"+name+"';")!='0':raise Failure('trigger_cleanup_failed')
 triggers.discard(name)
def second_owner(admin):
 global other_user
 context=data(f.api('/print/context',admin));admin_id=int(context['userId'])
 other_user=int(f.sql("INSERT INTO system_users(username,password,nickname,status,tenant_id,creator,updater) SELECT '"+marker+"',password,'Payment acceptance',0,1,'"+marker+"','"+marker+"' FROM system_users WHERE id="+str(admin_id)+" AND tenant_id=1; SELECT LAST_INSERT_ID();"))
 fixtures.identities.append((1,other_user));fixtures.sync_ledger()
 f.sql("INSERT INTO system_user_role(user_id,role_id,tenant_id,creator,updater) SELECT "+str(other_user)+",id,1,'"+marker+"','"+marker+"' FROM system_role WHERE code='tenant_admin' AND tenant_id=1 AND deleted=0; INSERT INTO print_staff_store(tenant_id,store_id,user_id,status,creator,updater) VALUES(1,1,"+str(other_user)+",0,'"+marker+"','"+marker+"');")
 credentials=json.loads((BASE/'runtime/secrets/smoke-admin.json').read_text())
 payload=data(f.api('/system/auth/login',body={'username':marker,'password':credentials['password']}))
 token=fixtures.register_login(payload,1,other_user,'same_owner')
 f.tokens.append(token);return token
def start(root,max_pages=20):
 provenance.assert_stable()
 f.start(root,max_pages=max_pages)
 provenance.observe_process(f.process.pid,'temporary_payment_acceptance')

def login(label):
 credentials=json.loads((BASE/'runtime/secrets'/('smoke-'+label+'.json')).read_text())
 if label not in ('admin','reader') or not re.fullmatch('[A-Za-z0-9]{4,30}',credentials['username']) or credentials['tenantId']!=1:raise Failure('invalid_login_fixture_identity')
 expected=int(f.sql("SELECT id FROM system_users WHERE tenant_id=1 AND username='"+credentials['username']+"' AND deleted=0;"))
 payload=data(f.api('/system/auth/login',body={'username':credentials['username'],'password':credentials['password']}))
 token=fixtures.register_login(payload,1,expected,label);f.tokens.append(token);return token

def scoped_pay(token,order,key):
 return support.api('/print/orders/'+order+'/simulate-payment',token,body={},key=key,tenant=fixtures.tenant)

def added_scope_and_concurrency(admin,other,file_id,profile,scope_token,original_order):
 # Two distinct, lawful orders in one scope race for one literal key.
 left=new_order(admin,file_id,profile)['id'];right=new_order(admin,file_id,profile)['id'];literal=str(uuid.uuid4())
 results=support.parallel([lambda:pay(admin,left,literal),lambda:pay(admin,right,literal)])
 check('same_scope_two_orders_one_key_one_success_one_conflict',sorted(r[0] for r in results)==[200,409] and all(r[1].get('code')==1510040004 for r in results if r[0]==409))
 winner=left if results[0][0]==200 else right;loser=right if winner==left else left
 check('same_scope_shared_key_loser_remains_v1_without_payment_residue',counts(winner)==[1,2,1] and empty_payment(loser))
 # Separate owners, same tenant/store, each own a real HTTP-created file and order.
 other_file=q.ready_file(other,3,'payment-other-owner')
 own_admin=new_order(admin,file_id,profile)['id'];own_other=new_order(other,other_file,profile)['id'];literal=str(uuid.uuid4())
 results=support.parallel([lambda:pay(admin,own_admin,literal),lambda:pay(other,own_other,literal)])
 check('different_owners_same_literal_key_independently_succeed',all(r[0]==200 and r[1].get('code')==0 for r in results) and {data(r)['orderId'] for r in results}=={own_admin,own_other} and counts(own_admin)==[1,2,1] and counts(own_other)==[1,2,1])
 # A genuinely different tenant AND store has full permissions and its own legal order.
 context=data(support.api('/print/context',scope_token,tenant=fixtures.tenant))
 check('real_second_tenant_store_context',context['tenantId']==fixtures.tenant and context['storeId']==fixtures.store and context['userId']==fixtures.user and fixtures.tenant!=1 and fixtures.store!=1)
 permission=data(support.api('/system/auth/get-permission-info',scope_token,tenant=fixtures.tenant))
 check('second_scope_has_all_required_permissions',set(support.REQUIRED_PERMISSIONS).issubset(set(permission['permissions'])))
 for suffix,expected in [('',1510030009),('/payment',1510040003)]:
  result=support.api('/print/orders/'+original_order+suffix,scope_token,tenant=fixtures.tenant)
  check('cross_tenant_store_order_hidden_get'+suffix,result[0]==404 and result[1].get('code')==expected)
 result=scoped_pay(scope_token,original_order,str(uuid.uuid4()))
 check('cross_tenant_store_payment_hidden_post',result[0]==404 and result[1].get('code')==1510040003)
 scoped_order=fixtures.own_order(scope_token,data,q.pdf)['id'];admin_order=new_order(admin,file_id,profile)['id'];literal=str(uuid.uuid4())
 results=support.parallel([lambda:pay(admin,admin_order,literal),lambda:scoped_pay(scope_token,scoped_order,literal)])
 check('different_tenant_store_same_literal_key_independently_succeed',all(r[0]==200 and r[1].get('code')==0 for r in results) and {data(r)['orderId'] for r in results}=={admin_order,scoped_order} and counts(admin_order)==[1,2,1] and counts(scoped_order)==[1,2,1])
 scoped_other=fixtures.own_order(scope_token,data,q.pdf)['id'];admin_other=new_order(admin,file_id,profile)['id'];key_a=str(uuid.uuid4());key_b=str(uuid.uuid4())
 results=support.parallel([lambda:pay(admin,admin_other,key_a),lambda:scoped_pay(scope_token,scoped_other,key_b)])
 check('different_tenant_store_different_keys_independently_succeed',all(r[0]==200 and r[1].get('code')==0 for r in results) and {data(r)['orderId'] for r in results}=={admin_other,scoped_other} and counts(admin_other)==[1,2,1] and counts(scoped_other)==[1,2,1])
 # Keep the same owner on both sides of the store boundary.
 # Paid old-store order exercises GET; unpaid old-store order exercises POST.
 own_other_unpaid=new_order(other,other_file,profile)['id']
 check('same_owner_old_store_unpaid_fixture_is_v1',empty_payment(own_other_unpaid))
 # Move ONLY this batch's second owner's mapping, never admin/reader mappings.
 moved_store=fixtures.move_fixture_owner_store(other_user)
 context=data(f.api('/print/context',other))
 check('same_tenant_other_store_fixture_context',context['tenantId']==1 and context['storeId']==moved_store and context['userId']==other_user and moved_store!=1)
 for suffix,expected in [('',1510030009),('/payment',1510040003)]:
  result=f.api('/print/orders/'+own_other+suffix,other)
  check('same_tenant_other_store_order_hidden_get'+suffix,result[0]==404 and result[1].get('code')==expected)
 result=pay(other,own_other_unpaid)
 check('same_tenant_other_store_payment_hidden_post',result[0]==404 and result[1].get('code')==1510040003)
 check('same_owner_old_store_denied_post_preserves_v1_without_residue',empty_payment(own_other_unpaid))
 fixtures.capture_fixture_caches(required=True)
 provenance.assert_stable()

def restart(root,enabled=True,mode='DEMO'):
 f.stop();os.environ['PRINT_PAYMENTS_ENABLED']=str(enabled).lower()
 if mode is None:os.environ.pop('PRINT_PAYMENTS_MODE',None)
 else:os.environ['PRINT_PAYMENTS_MODE']=mode
 start(root,max_pages=20)
def run(root):
 credentials=json.loads((BASE/'runtime/secrets/smoke-admin.json').read_text())
 if not re.fullmatch('[A-Za-z0-9]{4,30}',credentials['username']):raise Failure('invalid_fixture_admin_username')
 admin_id=int(f.sql("SELECT id FROM system_users WHERE tenant_id=1 AND username='"+credentials['username']+"' AND deleted=0;"))
 fixtures.seed_scope(admin_id)
 (root/'multipart').mkdir(mode=0o700)
 (root/'logback.xml').write_text('<configuration><root level="OFF"/></configuration>')
 os.environ.update(PRINT_QUOTES_ENABLED='true',PRINT_QUOTES_TTL_SECONDS='60',PRINT_ORDERS_ENABLED='true',PRINT_PAYMENTS_ENABLED='true',PRINT_PAYMENTS_MODE='DEMO')
 start(root,max_pages=20)
 admin=login('admin');reader=login('reader');other=second_owner(admin)
 fixtures.capture_session(other,1)
 scope_token=fixtures.login_scope(data)
 file_id=q.ready_file(admin,3,'payment-three');profile=q.profile()
 first=new_order(admin,file_id,profile);order=first['id'];key=str(uuid.uuid4())
 check('payment_absent_before_simulation',f.api('/print/orders/'+order+'/payment',admin)[1].get('code')==1510040013)
 check('reader_payment_permission_denied',pay(reader,order)[1].get('code')==403)
 for method in ['read','write']:
  result=f.api('/print/orders/'+order+'/payment',other) if method=='read' else pay(other,order)
  check('authorized_other_owner_hidden_'+method,result[0]==404 and result[1].get('code')==1510040003)
 for name,body,suffix,key_input in [('amount',{'amount':'0'},'',None),('scope',{'tenantId':2},'',None),('provider',{'provider':'WECHAT'},'',None),('query',{},'?x=1',None),('key_spaces',{},'','bad key')]:
  result=pay(admin,order,key_input,body,suffix)
  check('client_contract_rejected_'+name,result[0]==400 and result[1].get('code')==1510040001)
 check('invalid_attempts_leave_initial_state',empty_payment(order))
 results=support.parallel([lambda:pay(admin,order,key) for _ in range(6)])
 receipt=data(results[0])
 check('concurrent_same_key_returns_identical_receipt',all(data(r)==receipt for r in results))
 check('exactly_one_receipt_two_entries_one_job',counts(order)==[1,2,1])
 check('receipt_is_explicitly_simulated',receipt['provider']=='SIMULATED' and receipt['state']=='SUCCEEDED' and receipt['simulation'] is True and receipt['amount']=='0.80' and receipt['currency']=='CNY' and len(receipt['jobIds'])==1)
 rows=f.sql("SELECT entry_side,account_kind,amount,currency FROM print_simulated_journal WHERE order_id='"+order+"' ORDER BY entry_side;").splitlines()
 check('paired_exact_amount_ledger',rows==['CREDIT\tSIMULATED_ORDER_RECEIPT\t0.80\tCNY','DEBIT\tSIMULATED_EXTERNAL_FUNDS\t0.80\tCNY'])
 updated=data(f.api('/print/orders/'+order,admin))
 check('paid_order_is_queued_not_printed',updated['paymentState']=='SUCCEEDED' and updated['printState']=='QUEUED')
 events=data(f.api('/print/orders/'+order+'/events',admin))
 check('payment_event_once_version_two',events['total']==2 and f.sql("SELECT COUNT(*) FROM print_order_event WHERE order_id='"+order+"' AND event_type='PAYMENT_SIMULATED' AND aggregate_version=2;")=='1')
 check('job_has_no_attempt_or_lease',f.sql("SELECT CONCAT(dispatch_state,'|',print_result,'|',attempt_count,'|',fencing_version,'|',CAST(terminal_id IS NULL AND lease_owner IS NULL AND lease_token_hash IS NULL AND lease_expires_at_ms IS NULL AS UNSIGNED)) FROM print_job WHERE order_id='"+order+"';")=='QUEUED|NOT_REPORTED|0|0|1')
 result=pay(admin,order)
 check('new_key_cannot_charge_paid_order',result[0]==409 and result[1].get('code')==1510040005 and counts(order)==[1,2,1])
 for table in ['print_payment_receipt','print_simulated_journal','print_job']:
  field='amount' if table!='print_job' else 'dispatch_state'
  check('app_cannot_update_'+table,app_denied("UPDATE "+table+" SET "+field+"="+field+" WHERE order_id='"+order+"';",'immutable'))
  check('app_cannot_delete_'+table,app_denied("DELETE FROM "+table+" WHERE order_id='"+order+"';",'local root maintenance'))
 check('guard_denials_preserve_payment',counts(order)==[1,2,1])
 second=new_order(admin,file_id,profile)
 check('app_cannot_fake_paid_state',app_denied("UPDATE print_order SET payment_state='SUCCEEDED',print_state='QUEUED',version=2 WHERE id='"+second['id']+"';",'requires matching simulated receipt'))
 check('app_cannot_change_order_amount',app_denied("UPDATE print_order SET total_amount='0' WHERE id='"+second['id']+"';",'immutable'))
 result=pay(admin,second['id'],key)
 check('same_key_other_order_conflicts',result[0]==409 and result[1].get('code')==1510040004 and empty_payment(second['id']))
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
  results=list(pool.map(lambda _:pay(admin,second['id']),range(2)))
 check('different_keys_race_one_payment',sorted(r[0] for r in results)==[200,409] and counts(second['id'])==[1,2,1])
 # Trigger failures after receipt+first journal, after both journals, and after job+event.
 for label,table,condition in [('second_journal','print_simulated_journal'," AND NEW.entry_side='CREDIT'"),('job','print_job',''),('order_update','print_order','')]:
  fault_order=new_order(admin,file_id,profile)['id'];fault_key=str(uuid.uuid4())
  trigger=install_trigger(table,fault_order,condition)
  try:
   result=pay(admin,fault_order,fault_key)
   check('failure_response_'+label,result[0]==503 and result[1].get('code')==1510040010)
   check('full_transaction_rollback_'+label,empty_payment(fault_order))
  finally:drop_trigger(trigger)
  check('same_key_retry_after_rollback_'+label,data(pay(admin,fault_order,fault_key))['orderId']==fault_order and counts(fault_order)==[1,2,1])
 # Exact arbitrary precision from frozen quote; these are test-only profile values.
 for label,price,expected in [('fraction','0.123','0.369'),('zero','0','0')]:
  precise_profile=q.profile(version=label,simplex=price,duplex=price)
  precise=new_order(admin,file_id,precise_profile,1,'SIMPLEX')
  precise_receipt=data(pay(admin,precise['id']))
  check('exact_frozen_amount_'+label,precise_receipt['amount']==expected and precise['totalAmount']==expected)
  amounts=f.sql("SELECT amount FROM print_simulated_journal WHERE order_id='"+precise['id']+"';").splitlines()
  check('journal_preserves_exact_amount_'+label,amounts==[expected,expected])
 added_scope_and_concurrency(admin,other,file_id,profile,scope_token,order)
 # Prepare an unpaid order to test file deletion separately from historical receipt replay.
 expired_quote_order=new_order(admin,file_id,profile)
 # Real process restart must preserve committed receipt and idempotency.
 restart(root)
 check('receipt_survives_restart',data(f.api('/print/orders/'+order+'/payment',admin))==receipt)
 check('same_key_replay_survives_restart',data(pay(admin,order,key))==receipt)
 # Historical replay must survive referenced file deletion.
 f.api('/print/files/'+file_id,admin,method='DELETE')
 check('paid_receipt_replay_after_file_deleted',data(pay(admin,order,key))==receipt)
 result=pay(admin,expired_quote_order['id'])
 check('unpaid_deleted_file_rejected',result[0]==409 and result[1].get('code')==1510040008 and empty_payment(expired_quote_order['id']))
 # Reject changed file digest and expiry during transaction, not just before entry.
 damaged=q.ready_file(admin,2,'payment-damaged');damaged_order=new_order(admin,damaged,profile)['id']
 digest=f.owned[damaged]
 try:
  f.sql("UPDATE print_file SET content_sha256='"+'0'*64+"' WHERE id='"+damaged+"';")
  result=pay(admin,damaged_order)
  check('file_digest_mismatch_cannot_pay',result[0]==409 and result[1].get('code')==1510040009 and empty_payment(damaged_order))
 finally:f.sql("UPDATE print_file SET content_sha256='"+digest+"' WHERE id='"+damaged+"';")
 expiring=q.ready_file(admin,1,'payment-expiring');expiring_order=new_order(admin,expiring,profile)['id']
 trigger=install_trigger('print_job',expiring_order,action='DO SLEEP(2)')
 try:
  f.sql("UPDATE print_file SET expires_at_ms="+str(int(time.time()*1000)+1200)+" WHERE id='"+expiring+"';")
  result=pay(admin,expiring_order)
  check('expiry_during_transaction_rolls_back',result[0]==409 and result[1].get('code')==1510040008 and empty_payment(expiring_order))
 finally:drop_trigger(trigger)
 # Existing receipt remains readable with write switch off or invalid environment.
 for label,enabled,mode,expected in [('off',False,'DEMO',1510040000),('missing_mode',True,None,1510040002),('production_mode',True,'PRODUCTION',1510040002)]:
  restart(root,enabled,mode)
  result=pay(admin,order,key)
  check('write_environment_gate_'+label,result[1].get('code')==expected)
  check('history_readable_'+label,data(f.api('/print/orders/'+order+'/payment',admin))==receipt)
 check('normal_runtime_orders_remain_disabled',f.api('/print/orders/'+order,admin,port=48080)[1].get('code')==1510030000)
def main():
 global fixtures,provenance
 parser=argparse.ArgumentParser();parser.add_argument('--allow-fixture-mutations',action='store_true');args=parser.parse_args()
 if not args.allow_fixture_mutations:raise Failure('explicit_fixture_mutation_flag_required')
 os.umask(0o077)
 run_id=uuid.uuid4().hex
 support.redis_auth_preflight(run_id)
 acceptance=BASE/'runtime/acceptance';acceptance.mkdir(exist_ok=True,mode=0o700)
 with support.fixture_lock(BASE):
  support.ledger_hmac_key(BASE,initialize=True)
  if shutil.disk_usage(BASE).free<10*1024**3:raise Failure('low_disk')
  if any(f.sql('SELECT COUNT(*) FROM '+t+';')!='0' for t in tables):raise Failure('requires_empty_synthetic_business_tables')
  provenance=support.Provenance(BASE);fixtures=support.Fixtures(BASE,f.sql,marker)
  root=acceptance/('payment-http-'+run_id);root.mkdir(mode=0o700)
  ledger=None;failure=None;version_evidence=None
  try:
   ledger=support.SessionLedger(root,run_id,marker,provenance.initial['script_sha256']);fixtures.ledger=ledger
   run(root)
  except Exception as ex:failure=str(ex) if isinstance(ex,(Failure,f.CheckFailure,q.QuoteFailure,o.Failure,support.SupportFailure)) else type(ex).__name__
  finally:
   for name in list(triggers):
    try:drop_trigger(name)
    except Exception:cleanup.append('fault_trigger_cleanup_failed')
   try:fixtures.capture_fixture_caches(required=False)
   except Exception:cleanup.append('fixture_cache_baseline_capture_failed')
   for token in f.tokens:
    try:fixtures.capture_session(token,1)
    except Exception:cleanup.append('owned_session_capture_failed')
   try:fixtures.cleanup_sessions()
   except Exception:cleanup.append('owned_session_cleanup_failed')
   f.stop()
   try:fixtures.cleanup_business()
   except Exception:cleanup.append('second_scope_business_cleanup_failed')
   for profile in q.profiles:
    try:
     ids=f.sql("SELECT oi.order_id FROM print_order_item oi JOIN print_quote q ON q.id=oi.quote_id WHERE q.price_profile_id='"+profile+"' AND oi.tenant_id=1;").splitlines()
     for identifier in ids:
      if str(uuid.UUID(identifier))!=identifier:raise Failure('invalid_fixture_order_id')
      for table in tables[:5]:f.sql("DELETE FROM "+table+" WHERE order_id='"+identifier+"' AND tenant_id=1;")
      f.sql("DELETE FROM print_order WHERE id='"+identifier+"' AND tenant_id=1;")
     f.sql("DELETE FROM print_quote WHERE price_profile_id='"+profile+"' AND tenant_id=1;")
     f.sql("DELETE FROM print_price_profile WHERE id='"+profile+"' AND profile_key='"+q.marker+"';")
    except Exception:cleanup.append('payment_fixture_cleanup_failed')
   for identifier,digest in f.owned.items():
    try:f.sql("DELETE FROM print_file WHERE id='"+identifier+"' AND content_sha256='"+digest+"' AND tenant_id=1;")
    except Exception:cleanup.append('file_cleanup_failed')
   if other_user is not None:
    try:
     for table in ['print_staff_store','system_user_role']:
      f.sql("DELETE FROM "+table+" WHERE user_id="+str(other_user)+" AND creator='"+marker+"' AND tenant_id=1;")
     f.sql("DELETE FROM system_users WHERE id="+str(other_user)+" AND username='"+marker+"' AND creator='"+marker+"';")
    except Exception:cleanup.append('test_user_cleanup_failed')
   try:fixtures.cleanup_identities()
   except Exception:cleanup.append('scope_identity_cleanup_failed')
   try:fixtures.cleanup_caches()
   except Exception:cleanup.append('scope_cache_cleanup_failed')
   try:
    version_evidence=provenance.report()
    if not version_evidence['source_and_release_unchanged']:cleanup.append('source_or_release_changed_during_test')
   except Exception:cleanup.append('provenance_final_verification_failed')
   descriptor=acceptance/'file-test-process.json'
   if descriptor.exists() and f.process is not None and f.process.poll() is not None and json.loads(descriptor.read_text()).get('pid')==f.process.pid:descriptor.unlink()
   remaining={t:int(f.sql('SELECT COUNT(*) FROM '+t+';')) for t in tables}
   if any(remaining.values()):cleanup.append('fixture_rows_remaining')
   if ledger is not None and not cleanup:
    try:ledger.remove_verified()
    except Exception:cleanup.append('private_ledger_removal_failed')
   ledger_status={'retained':ledger is not None and ledger.path.exists(),'records_count':len(fixtures.sessions),'last_sha256':ledger.last_sha256 if ledger is not None else None}
   if root.resolve().parent==acceptance.resolve() and not root.is_symlink() and not cleanup:shutil.rmtree(root)
   report={'passed':failure is None and not cleanup,'failure':failure,'checks':checks,'pdf_fixture_checks':q.checks,'cleanup_errors':cleanup,'remaining_rows':remaining,'temporary_files_removed':not root.exists(),'temporary_instance_stopped':f.process is None or f.process.poll() is not None,'normal_runtime_payment_enabled':False,'real_payment_or_printing':False,'started_utc':provenance.started_utc,'finished_utc':support.utc(),'version_evidence':version_evidence,'scope_cleanup':fixtures.cleaned,'same_key_same_order_parallelism':6,'browser_ui':'NOT_RUN','attempt_name':'payment-http-v5','run_id':run_id,'session_ledger':ledger_status,'old_orphan_session':{'status':'OPEN','verification':'UNVERIFIED','failed_report_sha256':'28b7e94aea0a40ce29e17093eb57fdf2863d0bd8d4399728c85bb8b0856f9c39','cleanup_scope':'Excluded: original token not recoverable from checked artifacts'},'auth_ledger_repair_round':2,'permission_fixture_repair_round':1,'prior_v3_failed_report_sha256':'1af6d3a14475b9e545e815a723bfe09b696d07bc752c76622af729a5d01cb746'}
   (BASE/'evidence/acceptance/payment-http-smoke-v5.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
  return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
