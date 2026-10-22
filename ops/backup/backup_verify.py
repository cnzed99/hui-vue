#!/usr/bin/env python3
"""Consistent encrypted snapshot + isolated restore verification; never prints row data."""
import collections,datetime,hashlib,json,os,pathlib,re,secrets,shutil,subprocess,sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidTag
BASE=pathlib.Path('/home/ubuntu/print-system').resolve()
BACKUPS=BASE/'runtime/backups'
KEY=BASE/'runtime/secrets/database-backup-aes256.key'
MAGIC=b'PRINTDB1\x00'; AAD=b'print-system:print_demo:mysql-snapshot:v1'
class BackupError(Exception): pass

def command(args,data=None):
 p=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if p.returncode: raise BackupError('subprocess_failed_'+str(p.returncode))
 return p.stdout

def mysql(sql,database=None):
 args=['sudo','-n','mysql','--default-character-set=utf8mb4','-N','-B']
 if database: args.append(database)
 return command(args,sql.encode()).decode().strip()

def report_write(path,report):
 path.write_text(json.dumps(report,indent=2)+'\n')

def main():
 os.umask(0o077)
 start=datetime.datetime.now(datetime.timezone.utc)
 stamp=start.strftime('%Y%m%dT%H%M%SZ')+'-'+secrets.token_hex(4)
 temp_db='print_restore_verify_'+start.strftime('%Y%m%d_%H%M%S')+'_'+secrets.token_hex(4)
 assert re.fullmatch(r'print_restore_verify_[0-9]{8}_[0-9]{6}_[a-f0-9]{8}',temp_db)
 free_before=shutil.disk_usage(BASE).free
 if free_before<10*1024**3: raise BackupError('less_than_10_GiB_free')
 if KEY.exists():
  if KEY.stat().st_mode&0o077 or KEY.stat().st_uid!=os.getuid(): raise BackupError('unsafe_key_permissions')
  key=KEY.read_bytes()
 else:
  key=secrets.token_bytes(32)
  with KEY.open('xb') as f:f.write(key)
  KEY.chmod(0o600)
 if len(key)!=32: raise BackupError('invalid_backup_key_length')
 snapshot=command(['sudo','-n','mysqldump','--single-transaction','--skip-lock-tables','--no-tablespaces','--set-gtid-purged=OFF','--hex-blob','--skip-extended-insert','--routines','--events','--default-character-set=utf8mb4','print_demo'])
 if len(snapshot)>128*1024**2: raise BackupError('snapshot_exceeds_current_in_memory_limit')
 dump=snapshot.decode('utf-8')
 if re.search(r'^\s*(?:USE\s|(?:CREATE|DROP)\s+DATABASE\b)',dump,re.M|re.I): raise BackupError('dump_has_database_switch')
 tables=re.findall(r'^CREATE TABLE `([a-zA-Z0-9_]+)`',dump,re.M)
 if not tables or len(tables)!=len(set(tables)): raise BackupError('unexpected_snapshot_schema')
 engines=re.findall(r'^\) ENGINE=([A-Za-z0-9_]+)',dump,re.M)
 if len(engines)!=len(tables) or any(e.lower()!='innodb' for e in engines): raise BackupError('nontransactional_table_snapshot_not_supported')
 inserts=collections.Counter(re.findall(r'^INSERT INTO `([a-zA-Z0-9_]+)` VALUES ',dump,re.M))
 if not set(inserts)<=set(tables): raise BackupError('unmatched_snapshot_inserts')
 expected={t:inserts.get(t,0) for t in tables}
 nonce=secrets.token_bytes(12)
 encrypted=MAGIC+nonce+AESGCM(key).encrypt(nonce,snapshot,AAD)
 name='print-demo-'+stamp+'.sql.aesgcm'
 target=BACKUPS/name
 with target.open('xb') as f: f.write(encrypted)
 target.chmod(0o600)
 saved=target.read_bytes()
 if not saved.startswith(MAGIC): raise BackupError('encrypted_header_mismatch')
 restored_plain=AESGCM(key).decrypt(saved[len(MAGIC):len(MAGIC)+12],saved[len(MAGIC)+12:],AAD)
 if hashlib.sha256(restored_plain).digest()!=hashlib.sha256(snapshot).digest(): raise BackupError('snapshot_roundtrip_mismatch')
 corrupt=bytearray(saved);corrupt[-1]^=1
 try:
  AESGCM(key).decrypt(bytes(corrupt[len(MAGIC):len(MAGIC)+12]),bytes(corrupt[len(MAGIC)+12:]),AAD)
  raise BackupError('tamper_detection_failed')
 except InvalidTag: pass
 created=False;counts={};invariants={};cleanup=False
 try:
  if mysql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='"+temp_db+"';")!='0': raise BackupError('temporary_database_collision')
  mysql('CREATE DATABASE `'+temp_db+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');created=True
  command(['sudo','-n','mysql','--default-character-set=utf8mb4',temp_db],restored_plain)
  actual_tables=mysql('SHOW TABLES;',temp_db).splitlines()
  if set(actual_tables)!=set(tables): raise BackupError('restored_table_set_mismatch')
  for table in tables:
   counts[table]=int(mysql('SELECT COUNT(*) FROM `'+table+'`;',temp_db))
  if counts!=expected: raise BackupError('restored_row_count_mismatch')
  invariants={
   'active_tenants':int(mysql('SELECT COUNT(*) FROM system_tenant WHERE deleted=0;',temp_db)),
   'tenant_admin_roles':int(mysql("SELECT COUNT(*) FROM system_role WHERE deleted=0 AND code='tenant_admin';",temp_db)),
   'super_admin_roles':int(mysql("SELECT COUNT(*) FROM system_role WHERE deleted=0 AND code='super_admin';",temp_db)),
   'tenant_admin_assignment':int(mysql("SELECT COUNT(*) FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id JOIN system_users u ON u.id=ur.user_id WHERE ur.deleted=0 AND r.deleted=0 AND u.deleted=0 AND r.code='tenant_admin' AND ur.tenant_id=u.tenant_id AND r.tenant_id=u.tenant_id;",temp_db)),
  }
  if invariants!={'active_tenants':1,'tenant_admin_roles':1,'super_admin_roles':0,'tenant_admin_assignment':1}: raise BackupError('restored_tenant_role_invariants_failed')
 finally:
  if created:
   mysql('DROP DATABASE `'+temp_db+'`;')
   cleanup=mysql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='"+temp_db+"';")=='0'
 if not cleanup: raise BackupError('temporary_database_cleanup_failed')
 # Retention is limited to files registered by this script. Unknown files are never deleted.
 manifest_path=BACKUPS/'owned-manifest.json'
 manifest=json.loads(manifest_path.read_text()) if manifest_path.exists() else {'version':1,'backups':[]}
 if manifest.get('version')!=1 or not isinstance(manifest.get('backups'),list): raise BackupError('invalid_ownership_manifest')
 manifest['backups'].append({'file':name,'created_utc':start.isoformat(),'sha256':hashlib.sha256(encrypted).hexdigest(),'bytes':len(encrypted),'restore_verified':True})
 removed=[]
 while len(manifest['backups'])>2:
  old=manifest['backups'][0]
  if not re.fullmatch(r'print-demo-\d{8}T\d{6}Z-[a-f0-9]{8}\.sql\.aesgcm',old['file']): raise BackupError('unsafe_retention_entry')
  candidate=BACKUPS/old['file']
  if candidate.is_symlink(): raise BackupError('symlink_retention_entry')
  if candidate.exists():
   if hashlib.sha256(candidate.read_bytes()).hexdigest()!=old['sha256']: raise BackupError('retention_hash_mismatch')
   candidate.unlink();removed.append(old['file'])
  manifest['backups'].pop(0)
 report_write(manifest_path,manifest)
 report={'timestamp_utc':start.isoformat(),'source_database':'print_demo','snapshot_method':'mysqldump --single-transaction --skip-lock-tables','baseline_method':'row counts from same snapshot single-row INSERT statements; no comparison to later live rows','encryption':'AES-256-GCM','authenticated_tamper_rejection':True,'encrypted_file':name,'encrypted_bytes':len(encrypted),'encrypted_sha256':hashlib.sha256(encrypted).hexdigest(),'snapshot_bytes':len(snapshot),'table_count':len(tables),'total_rows':sum(counts.values()),'table_rows':counts,'tenant_role_invariants':invariants,'temporary_database_removed':cleanup,'plaintext_sql_file_created':False,'source_database_stopped':False,'retention_max_owned_backups':2,'retention_removed':removed,'backup_location':'same server only; not offsite recovery','disk_free_before_bytes':free_before,'disk_free_after_bytes':shutil.disk_usage(BASE).free}
 report_write(BASE/'evidence/backup'/('verification-'+stamp+'.json'),report)
 report_write(BASE/'evidence/backup/latest.json',report)
 print(json.dumps({k:v for k,v in report.items() if k not in ('table_rows','retention_removed')}))
if __name__=='__main__':
 try:main()
 except Exception as exc:
  # Never print stderr, SQL, passwords/hashes or token-containing rows.
  print('backup_operation_failed='+type(exc).__name__+(':'+str(exc) if isinstance(exc,BackupError) else ''))
  sys.exit(1)
