#!/usr/bin/env python3
"""Verify application DB identity cannot delete an order event; rollback even on unexpected allowance."""
import datetime,fcntl,json,os,pathlib,shlex,subprocess,uuid
BASE=pathlib.Path(__file__).resolve().parents[2]
def root_sql(sql):
 r=subprocess.run(['sudo','-n','mysql','-N','-B','print_demo'],input=sql,text=True,capture_output=True)
 if r.returncode:raise RuntimeError('fixture_database_failed')
 return r.stdout.strip()
def main():
 with (BASE/'runtime/locks/fixture-mutation.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  order=str(uuid.uuid4());event=str(uuid.uuid4());failure=None;passed=False;clean=False
  try:
   uid=root_sql("SELECT id FROM system_users WHERE username='printadmin' AND tenant_id=1 AND deleted=0;")
   if not uid.isdecimal():raise RuntimeError('fixture_user_not_unique')
   root_sql("START TRANSACTION; INSERT INTO print_order(id,order_no,tenant_id,store_id,owner_user_id,created_by_actor_type,created_by_actor_id,source,simulation,currency,total_amount,order_state,payment_state,refund_state,delivery_mode,delivery_state,print_state,version,idempotency_sha256,input_sha256,created_at_ms) VALUES('"+order+"','GD"+order.replace('-','')+"',1,1,"+uid+",'STAFF',"+uid+",'ADMIN_DEMO',1,'CNY','0','OPEN','UNPAID','NONE','NONE','NOT_APPLICABLE','NOT_CREATED',1,'"+uuid.uuid4().hex*2+"','"+uuid.uuid4().hex*2+"',1); INSERT INTO print_order_event(id,order_id,tenant_id,store_id,owner_user_id,event_type,actor_type,actor_id,simulation,aggregate_version,created_at_ms) VALUES('"+event+"','"+order+"',1,1,"+uid+",'ORDER_CREATED','STAFF',"+uid+",1,1,1); COMMIT;")
   env=dict(os.environ)
   for line in (BASE/'runtime/secrets/runtime.env').read_text().splitlines():
    if line.startswith('PRINT_DB_PASSWORD='):env['MYSQL_PWD']=shlex.split(line.split('=',1)[1])[0]
   if 'MYSQL_PWD' not in env:raise RuntimeError('database_secret_missing')
   result=subprocess.run(['mysql','-h','127.0.0.1','-u','print_app','-N','-B','print_demo'],input="START TRANSACTION; DELETE FROM print_order_event WHERE id='"+event+"'; ROLLBACK;",env=env,text=True,capture_output=True)
   env.pop('MYSQL_PWD',None)
   passed=result.returncode!=0 and '1644 (45000)' in result.stderr and 'local root maintenance' in result.stderr
   if root_sql("SELECT COUNT(*) FROM print_order_event WHERE id='"+event+"';")!='1':raise RuntimeError('event_not_preserved')
  except Exception as ex:failure=type(ex).__name__
  finally:
   root_sql("DELETE FROM print_order_event WHERE id='"+event+"' AND order_id='"+order+"'; DELETE FROM print_order WHERE id='"+order+"';")
   clean=root_sql("SELECT COUNT(*) FROM print_order WHERE id='"+order+"';")=='0' and root_sql("SELECT COUNT(*) FROM print_order_event WHERE id='"+event+"';")=='0'
  report={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':passed and clean and failure is None,'application_delete_rejected_by_trigger':passed,'root_maintenance_cleanup_verified':clean,'failure':failure}
  (BASE/'evidence/acceptance/order-event-guard-smoke.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
  return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
