#!/usr/bin/env python3
"""Add only the two order permissions for known demo roles, with backup and guards."""
import argparse,fcntl,json,pathlib,shutil,subprocess,datetime
BASE=pathlib.Path(__file__).resolve().parents[2]
def sql(value):
 r=subprocess.run(['sudo','-n','mysql','-N','-B','print_demo'],input=value,text=True,capture_output=True)
 if r.returncode:raise RuntimeError('permission_sql_failed')
 return r.stdout.strip()
def guard():
 for ident,permission in [(2006,'print:order:create'),(2007,'print:order:query')]:
  row=sql(f"SELECT CONCAT(permission,'|',type,'|',parent_id,'|',status,'|',CAST(deleted AS UNSIGNED)) FROM system_menu WHERE id={ident};")
  if row and row!=permission+'|3|2000|0|0':raise RuntimeError('menu_id_conflict')
  if sql(f"SELECT COUNT(*) FROM system_menu WHERE permission='{permission}' AND id<>{ident};")!='0':raise RuntimeError('permission_conflict')
 for code in ['tenant_admin','print_reader']:
  if sql(f"SELECT COUNT(*) FROM system_role WHERE tenant_id=1 AND code='{code}' AND deleted=0 AND status=0;")!='1':raise RuntimeError('role_missing')
 if sql("SELECT COUNT(*) FROM system_tenant WHERE id=1 AND package_id=1 AND deleted=0;")!='1':raise RuntimeError('tenant_missing')
 if sql("SELECT COUNT(*) FROM system_tenant_package WHERE id=1 AND deleted=0;")!='1':raise RuntimeError('package_missing')
def metrics():
 return sql("SELECT GROUP_CONCAT(CONCAT(r.code,':',rm.menu_id) ORDER BY r.code,rm.menu_id) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE rm.tenant_id=1 AND rm.deleted=0 AND rm.menu_id IN (2006,2007) AND r.code IN ('tenant_admin','print_reader');")
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args()
 if not args.apply:print('NOT_APPLIED');return
 if shutil.disk_usage(BASE).free<10*1024**3:raise RuntimeError('low_disk')
 with (BASE/'runtime/locks/fixture-mutation.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);guard()
  backup=subprocess.run(['python3','ops/backup/backup_verify.py'],cwd=BASE,capture_output=True)
  if backup.returncode:raise RuntimeError('backup_failed')
  statements=['START TRANSACTION;']
  for ident,permission,label in [(2006,'print:order:create','Create print order'),(2007,'print:order:query','Query print orders')]:
   statements.append(f"INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible) SELECT {ident},'{label}','{permission}',3,{ident-2000},2000,'',0,b'0' WHERE NOT EXISTS(SELECT 1 FROM system_menu WHERE id={ident});")
   statements.append(f"UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',{ident}) WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'{ident}');")
  for code,ids in [('tenant_admin','2006,2007'),('print_reader','2007')]:
   statements.append(f"INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,updater) SELECT r.id,m.id,1,'orders-v1','orders-v1' FROM system_role r JOIN system_menu m ON m.id IN ({ids}) WHERE r.tenant_id=1 AND r.code='{code}' AND r.deleted=0 AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.tenant_id=1 AND rm.role_id=r.id AND rm.menu_id=m.id);")
  statements.append('COMMIT;');source='\n'.join(statements)
  sql(source);first=metrics();guard();sql(source);second=metrics()
  if first!=second or first!='print_reader:2007,tenant_admin:2006,tenant_admin:2007':raise RuntimeError('grant_verification_failed')
  if sql("SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE rm.tenant_id=1 AND r.code='print_reader' AND rm.menu_id=2006 AND rm.deleted=0;")!='0':raise RuntimeError('reader_write_grant')
  report={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'permissions':['print:order:create','print:order:query'],'admin_grants':2,'reader_grants':1,'reader_create_denied_by_seed':True,'idempotent_second_application':True,'pre_migration_backup_verified':True,'runtime_permission_acceptance':'NOT_RUN'}
  (BASE/'evidence/database/print-order-permissions.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':
 try:main()
 except Exception as exc:print(json.dumps({'failed':True,'exception_class':type(exc).__name__}));raise SystemExit(1)
