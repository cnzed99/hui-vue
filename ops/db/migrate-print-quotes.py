#!/usr/bin/env python3
"""Reviewable additive quote schema; only --apply performs backup and migration."""
import argparse, datetime, fcntl, hashlib, json, pathlib, shutil, subprocess, sys
BASE=pathlib.Path('/home/ubuntu/print-system')
TABLES={
 'print_price_profile':['id','tenant_id','store_id','profile_key','version','mode','currency',
                        'simplex_price_per_sheet','duplex_price_per_sheet','tier_json','created_at_ms'],
 'print_quote':['id','tenant_id','store_id','owner_user_id','file_id','price_profile_id',
                'idempotency_sha256','input_sha256','snapshot_json','created_at_ms','expires_at_ms']}
TRIGGERS={
 'trg_print_price_profile_immutable':('print_price_profile',"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Price profile versions are immutable'"),
 'trg_print_quote_immutable':('print_quote',"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Quote snapshots are immutable'")}
INDEXES={
 ('print_price_profile','uk_print_price_version'):'tenant_id,store_id,profile_key,version',
 ('print_price_profile','uk_print_price_scope'):'tenant_id,store_id,id',
 ('print_quote','uk_print_quote_idempotency'):'tenant_id,store_id,owner_user_id,idempotency_sha256'}
def sql(source):
    completed=subprocess.run(['sudo','-n','mysql','--batch','--skip-column-names','print_demo'],
        input=source,text=True,capture_output=True)
    if completed.returncode: raise RuntimeError('quote_migration_database_operation_failed')
    return completed.stdout.rstrip('\n')
def columns(table):
    return sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='print_demo' "
               f"AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION;").splitlines()
def trigger_state(name):
    return sql("SELECT CONCAT(ACTION_TIMING,'|',EVENT_MANIPULATION,'|',EVENT_OBJECT_TABLE,'|',ACTION_STATEMENT) "
               f"FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='print_demo' AND TRIGGER_NAME='{name}';")
def normalized(value):
    return ' '.join(value.replace('`','').split()).lower()
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    if not args.apply:
        print('NOT_APPLIED; review SQL and immutable trigger definitions before --apply');return
    if shutil.disk_usage(BASE).free<10*1024**3: raise RuntimeError('low_disk_stop')
    locks=BASE/'runtime/locks';locks.mkdir(parents=True,exist_ok=True)
    with (locks/'fixture-mutation.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        for table,expected in TABLES.items():
            existing=columns(table)
            if existing and existing!=expected: raise RuntimeError('existing_quote_schema_mismatch')
        for name,(table,statement) in TRIGGERS.items():
            existing=trigger_state(name)
            if existing and normalized(existing)!=normalized('BEFORE|UPDATE|'+table+'|'+statement):
                raise RuntimeError('existing_immutable_trigger_mismatch')
        backup=subprocess.run(['python3',str(BASE/'ops/backup/backup_verify.py')],capture_output=True,text=True)
        if backup.returncode: raise RuntimeError('pre_quote_migration_backup_failed')
        schema=BASE/'ops/db/print-quotes-v1.sql'
        sql(schema.read_text())
        for name,(table,statement) in TRIGGERS.items():
            if not trigger_state(name): sql(f'CREATE TRIGGER {name} BEFORE UPDATE ON {table} FOR EACH ROW {statement};')
        for table,expected in TABLES.items():
            if columns(table)!=expected: raise RuntimeError('quote_schema_column_verification_failed')
        for (table,index),expected in INDEXES.items():
            actual=sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS "
                       f"WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='{table}' AND INDEX_NAME='{index}' AND NON_UNIQUE=0;")
            if actual!=expected: raise RuntimeError('quote_unique_index_verification_failed')
        for name,(table,statement) in TRIGGERS.items():
            if normalized(trigger_state(name))!=normalized('BEFORE|UPDATE|'+table+'|'+statement):
                raise RuntimeError('quote_immutable_trigger_verification_failed')
        result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'schema_sha256':hashlib.sha256(schema.read_bytes()).hexdigest(),
                'tables':list(TABLES),'columns_verified':sum(map(len,TABLES.values())),
                'unique_indexes_verified':len(INDEXES),'immutable_update_triggers_verified':len(TRIGGERS),
                'business_prices_seeded':False,'runtime_enabled':False,'pre_migration_backup_restore_verified':True,
                'remaining_free_bytes':shutil.disk_usage(BASE).free}
        destination=BASE/'evidence/database/print-quotes-v1.json';destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result))
if __name__=='__main__':
    try: main()
    except Exception as exc:
        print(json.dumps({'status':'FAILED','exception_class':type(exc).__name__}),file=sys.stderr);sys.exit(1)
