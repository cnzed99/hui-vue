#!/usr/bin/env python3
"""Explicit additive order-A migration. Default does not connect to DB or change anything."""
import argparse, datetime, fcntl, hashlib, json, pathlib, shutil, subprocess, sys
BASE=pathlib.Path('/home/ubuntu/print-system')
TABLES={
 'print_order':['id','order_no','tenant_id','store_id','owner_user_id','created_by_actor_type','created_by_actor_id','source','simulation',
                'currency','total_amount','order_state','payment_state','refund_state','delivery_mode','delivery_state','print_state',
                'version','idempotency_sha256','input_sha256','created_at_ms'],
 'print_order_item':['id','order_id','tenant_id','store_id','owner_user_id','quote_id','file_id','file_content_sha256',
                     'quote_snapshot_json','amount','sequence_no','created_at_ms'],
 'print_order_event':['id','order_id','tenant_id','store_id','owner_user_id','event_type','actor_type','actor_id',
                      'simulation','aggregate_version','created_at_ms']}
TRIGGERS={
 'trg_print_order_immutable':('UPDATE','print_order',"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order A records are immutable'"),
 'trg_print_order_item_immutable':('UPDATE','print_order_item',"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order item snapshots are immutable'"),
 'trg_print_order_event_immutable':('UPDATE','print_order_event',"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order events are append only'"),
 'trg_print_order_event_delete_guard':('DELETE','print_order_event',
    "BEGIN IF USER() <> 'root@localhost' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order events require local root maintenance'; END IF; END")}
INDEXES={
 ('print_order','uk_print_order_idempotency'):'tenant_id,store_id,owner_user_id,idempotency_sha256',
 ('print_order','uk_print_order_scope'):'tenant_id,store_id,owner_user_id,id',
 ('print_order','uk_print_order_no'):'order_no',
 ('print_order_item','uk_print_order_item_quote'):'quote_id',
 ('print_order_item','uk_print_order_item_sequence'):'order_id,sequence_no',
 ('print_order_event','uk_print_order_event_version'):'order_id,aggregate_version'}
def sql(source):
    completed=subprocess.run(['sudo','-n','mysql','--batch','--skip-column-names','print_demo'],input=source,text=True,capture_output=True)
    if completed.returncode:raise RuntimeError('order_migration_database_operation_failed')
    return completed.stdout.rstrip('\n')
def columns(table):
    return sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='print_demo' "
               f"AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION;").splitlines()
def trigger_state(name):
    return sql("SELECT CONCAT(ACTION_TIMING,'|',EVENT_MANIPULATION,'|',EVENT_OBJECT_TABLE,'|',ACTION_STATEMENT) "
               f"FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='print_demo' AND TRIGGER_NAME='{name}';")
def norm(value):return ' '.join(value.replace('`','').split()).lower()
def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--apply',action='store_true');args=ap.parse_args()
    if not args.apply:print('NOT_APPLIED; review schema, append-only guards and backup before --apply');return
    if shutil.disk_usage(BASE).free<10*1024**3:raise RuntimeError('low_disk_stop')
    locks=BASE/'runtime/locks';locks.mkdir(parents=True,exist_ok=True)
    with (locks/'fixture-mutation.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        for table,expected in TABLES.items():
            current=columns(table)
            if current and current!=expected:raise RuntimeError('existing_order_schema_mismatch')
        for name,(event,table,statement) in TRIGGERS.items():
            existing=trigger_state(name)
            if existing and norm(existing)!=norm('BEFORE|'+event+'|'+table+'|'+statement):raise RuntimeError('existing_order_trigger_mismatch')
        backup=subprocess.run(['python3',str(BASE/'ops/backup/backup_verify.py')],capture_output=True,text=True)
        if backup.returncode:raise RuntimeError('pre_order_migration_backup_failed')
        schema=BASE/'ops/db/print-orders-v1.sql';sql(schema.read_text())
        for name,(event,table,statement) in TRIGGERS.items():
            if not trigger_state(name):
                sql(f'DELIMITER $$\nCREATE TRIGGER {name} BEFORE {event} ON {table} FOR EACH ROW {statement}$$\nDELIMITER ;\n')
        for table,expected in TABLES.items():
            if columns(table)!=expected:raise RuntimeError('order_schema_column_verification_failed')
        for (table,index),expected in INDEXES.items():
            actual=sql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS "
                       f"WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='{table}' AND INDEX_NAME='{index}' AND NON_UNIQUE=0;")
            if actual!=expected:raise RuntimeError('order_unique_index_verification_failed')
        for name,(event,table,statement) in TRIGGERS.items():
            if norm(trigger_state(name))!=norm('BEFORE|'+event+'|'+table+'|'+statement):raise RuntimeError('order_trigger_verification_failed')
        evidence={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'schema_sha256':hashlib.sha256(schema.read_bytes()).hexdigest(),
                  'tables':list(TABLES),'columns_verified':sum(map(len,TABLES.values())),'unique_indexes_verified':len(INDEXES),
                  'immutable_and_append_only_triggers_verified':len(TRIGGERS),'event_delete_maintenance_identity':'root@localhost',
                  'business_rows_seeded':False,'runtime_enabled':False,'pre_migration_backup_restore_verified':True,
                  'remaining_free_bytes':shutil.disk_usage(BASE).free}
        output=BASE/'evidence/database/print-orders-v1.json';output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps(evidence,indent=2)+'\n');print(json.dumps(evidence))
if __name__=='__main__':
    try:main()
    except Exception as exc:
        print(json.dumps({'status':'FAILED','exception_class':type(exc).__name__}),file=sys.stderr);sys.exit(1)
