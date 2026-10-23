#!/usr/bin/env python3
"""Real DB order acceptance; only generated PDFs and scoped test prices."""
import concurrent.futures,fcntl,hashlib,json,os,pathlib,shutil,subprocess,tempfile,time,uuid
import file_http_smoke as f
import quote_http_smoke as q
BASE=f.BASE
checks=[];order_ids=set();cleanup=[]
class Failure(Exception):pass
def check(name,value):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise Failure(name)
def create(token,quote_id,key=None,extra=None):
 key=key or str(uuid.uuid4());body={'quoteId':quote_id}
 if extra:body.update(extra)
 status,value,_=f.api('/print/orders',token,body=body,key=key)
 if isinstance(value,dict) and value.get('code')==0:order_ids.add(value['data']['id'])
 return status,value,key
def data(result):
 if result[0]!=200 or result[1].get('code')!=0:raise Failure('unexpected_success_response')
 return result[1]['data']
def new_quote(token,file_id,profile,copies=2):
 result=q.quote(token,file_id,profile,copies,'DUPLEX')
 return data(result)['id']
def run(root):
 (root/'multipart').mkdir(mode=0o700)
 (root/'logback.xml').write_text('<configuration><root level="OFF"/></configuration>')
 os.environ.update(PRINT_QUOTES_ENABLED='true',PRINT_QUOTES_TTL_SECONDS='20',PRINT_ORDERS_ENABLED='true')
 f.start(root,max_pages=20)
 admin=f.login('admin');reader=f.login('reader')
 file_id=q.ready_file(admin,3,'order-three')
 profile=q.profile()
 quote_id=new_quote(admin,file_id,profile)
 key=str(uuid.uuid4())
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
  created=list(pool.map(lambda _:create(admin,quote_id,key),range(4)))
 check('concurrent_creation_one_order',all(r[0]==200 and r[1].get('code')==0 for r in created) and len({r[1]['data']['id'] for r in created})==1)
 first=data(created[0]);order_id=first['id']
 check('real_order_uses_server_snapshot',first['totalAmount']=='0.80' and first['items'][0]['quoteId']==quote_id and first['items'][0]['fileId']==file_id and first['items'][0]['quoteSnapshot']['totalAmount']=='0.80')
 check('creation_is_not_payment_or_printing',first['source']=='ADMIN_DEMO' and first['simulation'] is True and first['orderState']=='OPEN' and first['paymentState']=='UNPAID' and first['printState']=='NOT_CREATED' and first['refundState']=='NONE' and first['deliveryState']=='NOT_APPLICABLE')
 check('one_quote_consumption_row',f.sql("SELECT COUNT(*) FROM print_order_item WHERE quote_id='"+quote_id+"';")=='1')
 check('one_creation_event',f.sql("SELECT COUNT(*) FROM print_order_event WHERE order_id='"+order_id+"';")=='1')
 status,value,_=create(admin,quote_id)
 check('same_quote_new_key_cannot_duplicate',status==409 and value.get('code')==1510030004)
 second_quote=new_quote(admin,file_id,profile)
 status,value,_=create(admin,second_quote,key)
 check('same_key_other_quote_conflict',status==409 and value.get('code')==1510030008)
 status,value,_=create(admin,second_quote,extra={'totalAmount':'0.00','storeId':999})
 check('client_amount_or_scope_rejected',status==400 and value.get('code')==1510030001)
 status,value,_=create(reader,second_quote)
 check('reader_create_denied',value.get('code')==403)
 for suffix in ['', '/events']:
  status,value,_=f.api('/print/orders/'+order_id+suffix,reader)
  check('other_owner_hidden'+suffix,status==404 and value.get('code')==1510030009)
 _,value,_=f.api('/print/orders',reader)
 check('reader_list_contains_no_admin_orders',value.get('code')==0 and value['data']['list']==[] and value['data']['total']==0)
 _,value,_=f.api('/print/orders/'+order_id+'/events',admin)
 event=value['data']['list'][0]
 check('creation_event_is_staff_and_simulated',value['data']['total']==1 and event['type']=='ORDER_CREATED' and event['actorType']=='STAFF' and event['simulation'] is True and event['orderId']==order_id)
 # A second valid order enables stable pagination checks.
 second=data(create(admin,second_quote))
 _,page1,_=f.api('/print/orders?pageNo=1&pageSize=1',admin)
 _,page2,_=f.api('/print/orders?pageNo=2&pageSize=1',admin)
 check('stable_owner_pagination',page1['data']['total']==2 and page2['data']['total']==2 and page1['data']['list'][0]['id']==second['id'] and page2['data']['list'][0]['id']==order_id)
 for params in ['pageNo=0','pageNo=1.5','pageSize=101','pageSize=-1','storeId=999','ownerUserId=999','sort=created_at_ms','pageNo=1&pageNo=2']:
  status,value,_=f.api('/print/orders?'+params,admin)
  check('invalid_pagination_or_scope_'+params,status==400 and value.get('code')==1510030001)
 status,value,_=f.api('/print/orders/'+order_id,admin,port=48080)
 check('normal_demo_orders_stay_disabled',status==503 and value.get('code')==1510030000)
 check('order_update_blocked',q.forbidden_update("UPDATE print_order SET total_amount='0' WHERE id='"+order_id+"';"))
 check('item_update_blocked',q.forbidden_update("UPDATE print_order_item SET amount='0' WHERE order_id='"+order_id+"';"))
 result=subprocess.run(['sudo','-n','mysql','print_demo'],input="UPDATE print_order_event SET event_type='FAKE' WHERE order_id='"+order_id+"';",text=True,capture_output=True)
 check('event_update_blocked',result.returncode!=0 and '1644 (45000)' in result.stderr and 'append only' in result.stderr)
 unknown=str(uuid.uuid4());status,value,_=create(admin,unknown)
 check('unknown_quote_hidden',status==404 and value.get('code')==1510030002)
 # Reject an otherwise-valid quote after its referenced file is deleted.
 delete_file=q.ready_file(admin,1,'order-delete');delete_quote=new_quote(admin,delete_file,profile,1)
 f.api('/print/files/'+delete_file,admin,method='DELETE')
 status,value,_=create(admin,delete_quote)
 check('deleted_quote_file_rejected',status==409 and value.get('code')==1510030006)
 # Compare quote's immutable file digest against changed file metadata.
 mismatch_file=q.ready_file(admin,1,'order-mismatch');mismatch_quote=new_quote(admin,mismatch_file,profile,1)
 digest=f.owned[mismatch_file]
 try:
  f.sql("UPDATE print_file SET content_sha256='"+'0'*64+"' WHERE id='"+mismatch_file+"';")
  status,value,_=create(admin,mismatch_quote)
  check('file_digest_mismatch_rejected',status==409 and value.get('code')==1510030007)
 finally:f.sql("UPDATE print_file SET content_sha256='"+digest+"' WHERE id='"+mismatch_file+"';")
 expiring=new_quote(admin,file_id,profile,3)
 # Restart real process. Existing orders must not depend on quote's continued validity.
 f.stop();f.start(root,max_pages=20)
 _,reloaded,_=f.api('/print/orders/'+order_id,admin)
 check('order_survives_process_restart',reloaded.get('code')==0 and reloaded['data']==first)
 remaining=int(f.sql("SELECT expires_at_ms FROM print_quote WHERE id='"+expiring+"';"))-int(time.time()*1000)
 if remaining>=0:time.sleep(remaining/1000+0.05)
 replay=create(admin,quote_id,key)
 check('created_order_replay_survives_quote_expiry',data(replay)==first)
 status,value,_=create(admin,expiring)
 check('unconsumed_expired_quote_rejected',status==409 and value.get('code')==1510030003)
 check('only_two_orders_created',f.sql("SELECT COUNT(*) FROM print_order WHERE id IN ('"+order_id+"','"+second['id']+"');")=='2')
def main():
 os.umask(0o077)
 acceptance=BASE/'runtime/acceptance';acceptance.mkdir(exist_ok=True,mode=0o700)
 with (BASE/'runtime/locks/fixture-mutation.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  if f.sql('SELECT COUNT(*) FROM print_file;')!='0' or f.sql('SELECT COUNT(*) FROM print_order;')!='0':raise Failure('requires_empty_demo_file_order_tables')
  root=pathlib.Path(tempfile.mkdtemp(prefix='order-http-',dir=acceptance));failure=None
  try:run(root)
  except Exception as ex:failure=str(ex) if isinstance(ex,(Failure,f.CheckFailure,q.QuoteFailure)) else type(ex).__name__
  finally:
   for token in f.tokens:
    try:
     _,value,_=f.api('/system/auth/logout',token,body={},port=48080)
     if value.get('code')!=0:cleanup.append('logout_failed')
    except Exception:cleanup.append('logout_failed')
   f.stop()
   # Root maintenance removes only orders referencing this batch's private price profiles.
   for profile in q.profiles:
    try:
     condition="SELECT oi.order_id FROM print_order_item oi JOIN print_quote q ON q.id=oi.quote_id WHERE q.price_profile_id='"+profile+"' AND oi.tenant_id=1"
     ids=f.sql(condition).splitlines()
     for identifier in ids:
      if str(uuid.UUID(identifier))!=identifier:raise Failure('invalid_fixture_order_id')
      f.sql("DELETE FROM print_order_event WHERE order_id='"+identifier+"' AND tenant_id=1;")
      f.sql("DELETE FROM print_order_item WHERE order_id='"+identifier+"' AND tenant_id=1;")
      f.sql("DELETE FROM print_order WHERE id='"+identifier+"' AND tenant_id=1;")
     f.sql("DELETE FROM print_quote WHERE price_profile_id='"+profile+"' AND tenant_id=1;")
     f.sql("DELETE FROM print_price_profile WHERE id='"+profile+"' AND profile_key='"+q.marker+"';")
    except Exception:cleanup.append('order_fixture_cleanup_failed')
   for identifier,digest in f.owned.items():
    try:f.sql("DELETE FROM print_file WHERE id='"+identifier+"' AND content_sha256='"+digest+"' AND tenant_id=1;")
    except Exception:cleanup.append('file_fixture_cleanup_failed')
   descriptor=BASE/'runtime/acceptance/file-test-process.json'
   if descriptor.exists() and f.process is not None and f.process.poll() is not None:
    if json.loads(descriptor.read_text()).get('pid')==f.process.pid:descriptor.unlink()
   remaining={table:int(f.sql('SELECT COUNT(*) FROM '+table+';')) for table in ['print_order','print_order_item','print_order_event','print_file']}
   if any(remaining.values()):cleanup.append('fixture_rows_remaining')
   if root.resolve().parent==acceptance.resolve() and not root.is_symlink() and not cleanup:shutil.rmtree(root)
   report={'passed':failure is None and not cleanup,'failure':failure,'checks':checks,'pdf_fixture_checks':q.checks,'cleanup_errors':cleanup,'remaining_rows':remaining,'temporary_files_removed':not root.exists(),'temporary_instance_stopped':f.process is None or f.process.poll() is not None,'test_quote_ttl_seconds':20,'normal_demo_orders_enabled':False,'real_payment_or_printing':False}
   (BASE/'evidence/acceptance/order-http-smoke.json').write_text(json.dumps(report,indent=2)+'\n')
   print(json.dumps(report))
  return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
