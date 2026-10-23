#!/usr/bin/env python3
"""Explicit, idempotent CORE-01 schema migration. No service start or upload enabling."""
import argparse,hashlib,json,pathlib,subprocess,datetime,shutil
base=pathlib.Path('/home/ubuntu/print-system')
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--apply',action='store_true');args=ap.parse_args()
schema=base/'ops/db/print-files-v1.sql'
if not args.apply:
    print('NOT_APPLIED; pass --apply after reviewing schema');raise SystemExit(0)
def sql(text):
    p=subprocess.run(['sudo','-n','mysql','--batch','--skip-column-names','print_demo'],input=text,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if p.returncode:raise RuntimeError('mysql_migration_failed')
    return p.stdout.strip()
if shutil.disk_usage(base).free < 10 * 1024**3: raise RuntimeError('low_disk_stop')
backup=subprocess.run(['python3',str(base/'ops/backup/backup_verify.py')],capture_output=True,text=True)
if backup.returncode: raise RuntimeError('pre_migration_backup_failed')
sql(schema.read_text())
columns=sql("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='print_file' ORDER BY ORDINAL_POSITION;").splitlines()
expected=['id','tenant_id','store_id','owner_user_id','idempotency_sha256','content_sha256','display_name','size_bytes','page_count','status','failure_code','storage_key','created_at_ms','updated_at_ms','expires_at_ms','processing_started_at_ms','processing_attempts','blob_deleted_at_ms']
if columns!=expected:raise RuntimeError('existing_schema_column_contract_mismatch')
unique=sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='print_file' AND INDEX_NAME='uk_print_file_idempotency' AND NON_UNIQUE=0;")
if unique!='tenant_id,store_id,owner_user_id,idempotency_sha256':raise RuntimeError('idempotency_index_contract_mismatch')
evidence={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'schema_sha256':hashlib.sha256(schema.read_bytes()).hexdigest(),'columns_verified':len(columns),'idempotency_unique_verified':True,'table':'print_file','schema_only':True,'runtime_enabled':False}
out=base/'evidence/database/print-files-v1.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(evidence,indent=2))
print(json.dumps(evidence))
