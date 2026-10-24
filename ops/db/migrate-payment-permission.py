#!/usr/bin/env python3
"""Add only the isolated demo administrator's simulated-payment permission."""
import argparse,datetime,fcntl,json,pathlib,shutil,subprocess
BASE=pathlib.Path(__file__).resolve().parents[2]
def sql(value):
 r=subprocess.run(['sudo','-n','mysql','-N','-B','print_demo'],input=value,text=True,capture_output=True)
 if r.returncode:raise RuntimeError('permission_sql_failed')
 return r.stdout.strip()
def guard():
 row=sql("SELECT CONCAT(permission,'|',type,'|',parent_id,'|',status,'|',CAST(deleted AS UNSIGNED)) FROM system_menu WHERE id=2008;")
 if row and row!='print:payment:simulate|3|2000|0|0':raise RuntimeError('reserved_menu_conflict')
 if sql("SELECT COUNT(*) FROM system_menu WHERE permission='print:payment:simulate' AND id<>2008;")!='0':raise RuntimeError('permission_conflict')
 for role in ('tenant_admin','print_reader'):
  if sql("SELECT COUNT(*) FROM system_role WHERE tenant_id=1 AND code='"+role+"' AND status=0 AND deleted=0;")!='1':raise RuntimeError('expected_demo_role_missing')
 if sql("SELECT COUNT(*) FROM system_tenant WHERE id=1 AND package_id=1 AND deleted=0;")!='1':raise RuntimeError('demo_tenant_missing')
def grants():
 return sql("SELECT GROUP_CONCAT(r.code ORDER BY r.code) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE rm.tenant_id=1 AND rm.menu_id=2008 AND rm.deleted=0 AND r.tenant_id=1;")
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args()
 if not args.apply:print('NOT_APPLIED');return
 if shutil.disk_usage(BASE).free<10*1024**3:raise RuntimeError('low_disk')
 with (BASE/'runtime/locks/fixture-mutation.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);guard()
  if subprocess.run(['python3','ops/backup/backup_verify.py'],cwd=BASE,capture_output=True).returncode:raise RuntimeError('backup_failed')
  script="""START TRANSACTION;
INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible)
SELECT 2008,'Simulate print payment','print:payment:simulate',3,8,2000,'',0,b'0'
WHERE NOT EXISTS(SELECT 1 FROM system_menu WHERE id=2008);
INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,2008,1,'payment-v1','payment-v1' FROM system_role r
WHERE r.tenant_id=1 AND r.code='tenant_admin' AND r.deleted=0
AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.tenant_id=1 AND rm.role_id=r.id AND rm.menu_id=2008);
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',2008)
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'2008');
COMMIT;"""
  sql(script);first=grants();guard();sql(script);second=grants()
  if first!='tenant_admin' or second!=first:raise RuntimeError('unexpected_payment_grants')
  report={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'permission':'print:payment:simulate','admin_grant':True,'reader_grant':False,'idempotent_second_application':True,'pre_migration_backup_verified':True,'runtime_gate':'NOT_RUN'}
  (BASE/'evidence/database/print-payment-permission.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':
 try:main()
 except Exception as exc:print(json.dumps({'failed':True,'exception_class':type(exc).__name__}));raise SystemExit(1)
