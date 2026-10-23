#!/usr/bin/env python3
"""Guarded, additive and idempotent migration for the isolated demo database."""
import datetime,fcntl,hashlib,json,pathlib,shutil,subprocess,sys
BASE=pathlib.Path('/home/ubuntu/print-system')
class MigrationError(Exception):pass

def sql(statement):
 p=subprocess.run(['sudo','-n','mysql','--default-character-set=utf8mb4','-N','-B','print_demo'],input=statement,text=True,capture_output=True)
 if p.returncode:raise MigrationError('mysql_failed_no_row_data_output')
 return p.stdout.rstrip('\n')

def rows(statement):
 out=sql(statement)
 return [line.split('\t') for line in out.splitlines()] if out else []

def guard():
 if sql("SELECT COUNT(*) FROM system_tenant WHERE id=1 AND package_id=1 AND deleted=b'0';")!='1':raise MigrationError('expected_demo_tenant_missing')
 for username in ('printadmin','printreader'):
  if sql("SELECT COUNT(*) FROM system_users WHERE tenant_id=1 AND username='"+username+"' AND deleted=b'0';")!='1':raise MigrationError('expected_demo_staff_missing_or_ambiguous')
 for code in ('tenant_admin','print_reader'):
  if sql("SELECT COUNT(*) FROM system_role WHERE tenant_id=1 AND code='"+code+"' AND deleted=b'0';")!='1':raise MigrationError('expected_demo_role_missing_or_ambiguous')
 package=sql('SELECT menu_ids FROM system_tenant_package WHERE id=1;')
 if not isinstance(json.loads(package),list):raise MigrationError('invalid_package_menu_array')
 expected={2000:('',1,0,'/print'),2001:('print:file:query',3,2000,''),2002:('print:file:upload',3,2000,''),2003:('print:file:delete',3,2000,''),2004:('print:quote:create',3,2000,''),2005:('print:quote:query',3,2000,'')}
 for identifier,(permission,kind,parent,path) in expected.items():
  found=rows('SELECT id,permission,type,parent_id,path FROM system_menu WHERE id='+str(identifier)+';')
  if found and found[0]!=[str(identifier),permission,str(kind),str(parent),path]:raise MigrationError('reserved_menu_id_conflict')
  if permission and sql("SELECT COUNT(*) FROM system_menu WHERE permission='"+permission+"' AND id<>"+str(identifier)+';')!='0':raise MigrationError('permission_already_exists_with_different_id')
 existing=rows("SHOW TABLES LIKE 'print_store';")
 if existing:
  if sql("SELECT COUNT(*) FROM print_store WHERE id=1 AND (tenant_id<>1 OR store_code<>'DEMO001');")!='0':raise MigrationError('reserved_store_id_conflict')
  if sql("SELECT COUNT(*) FROM print_store WHERE tenant_id=1 AND store_code='DEMO001' AND id<>1;")!='0':raise MigrationError('reserved_store_code_conflict')

def metrics():
 return {
  'stores':int(sql('SELECT COUNT(*) FROM print_store;')),
  'staff_store_mappings':int(sql('SELECT COUNT(*) FROM print_staff_store;')),
  'print_menu_records':int(sql('SELECT COUNT(*) FROM system_menu WHERE id BETWEEN 2000 AND 2005;')),
  'admin_print_permission_grants':int(sql("SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE r.tenant_id=1 AND r.code='tenant_admin' AND rm.tenant_id=1 AND rm.menu_id BETWEEN 2000 AND 2005 AND rm.deleted=0;")),
  'reader_print_permission_grants':int(sql("SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE r.tenant_id=1 AND r.code='print_reader' AND rm.tenant_id=1 AND rm.menu_id BETWEEN 2000 AND 2005 AND rm.deleted=0;")),
  'reader_mutation_grants':int(sql("SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE r.tenant_id=1 AND r.code='print_reader' AND rm.tenant_id=1 AND rm.menu_id IN (2002,2003,2004) AND rm.deleted=0;")),
 }

def main():
 with (BASE/'ops/db/.print-foundation-v1.lock').open('w') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  if shutil.disk_usage(BASE).free<10*1024**3:raise MigrationError('less_than_10_GiB_free')
  guard()
  result=subprocess.run(['python3',str(BASE/'ops/backup/backup_verify.py')],capture_output=True,text=True)
  if result.returncode:raise MigrationError('pre_migration_backup_failed')
  before_backup=json.loads((BASE/'evidence/backup/latest.json').read_text())['encrypted_file']
  script=(BASE/'ops/db/print-foundation-v1.sql').read_text()
  sql(script)
  first=metrics()
  guard();sql(script);second=metrics()
  if first!=second:raise MigrationError('migration_second_run_changed_counts')
  if first!={'stores':1,'staff_store_mappings':2,'print_menu_records':6,'admin_print_permission_grants':6,'reader_print_permission_grants':3,'reader_mutation_grants':0}:raise MigrationError('unexpected_seed_counts')
  report={'timestamp_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'migration':'print-foundation-v1','sql_sha256':hashlib.sha256(script.encode()).hexdigest(),'pre_migration_backup':before_backup,'metrics':first,'second_execution_same_counts':True,'dropped_existing_tables':False,'upstream_records_imported':False,'backend_compiled':False,'backend_restarted':False,'http_scope_checks_run':False}
  (BASE/'evidence/database/print-foundation-v1.json').write_text(json.dumps(report,indent=2)+'\n')
  print(json.dumps(report))
if __name__=='__main__':
 try:main()
 except Exception as exc:
  print('migration_failed='+type(exc).__name__+(':'+str(exc) if isinstance(exc,MigrationError) else ''));sys.exit(1)
