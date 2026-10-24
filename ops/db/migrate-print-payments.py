#!/usr/bin/env python3
"""Explicit guarded A-to-B demo payment migration. No business seeds or service enabling."""
import argparse,datetime,fcntl,hashlib,importlib.util,json,pathlib,re,shutil,subprocess,sys
sys.dont_write_bytecode=True
BASE=pathlib.Path('/home/ubuntu/print-system')
SCHEMA=BASE/'ops/db/print-payments-v1.sql'
LEGACY=BASE/'ops/db/migrate-print-orders.py'
spec=importlib.util.spec_from_file_location('orders_migration_contract',LEGACY)
legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)
ORDER_COLUMNS=legacy.TABLES['print_order']
IMMUTABLE=[c for c in ORDER_COLUMNS if c not in ('payment_state','print_state','version')]
INITIAL="order_state='OPEN' AND payment_state='UNPAID' AND refund_state='NONE' AND delivery_mode='NONE' AND delivery_state='NOT_APPLICABLE' AND print_state='NOT_CREATED' AND version=1"
PAID="order_state='OPEN' AND payment_state='SUCCEEDED' AND refund_state='NONE' AND delivery_mode='NONE' AND delivery_state='NOT_APPLICABLE' AND print_state='QUEUED' AND version=2"
ORDER_CHECK='('+INITIAL+') OR ('+PAID+')'
EVENT_OLD="event_type='ORDER_CREATED' AND aggregate_version=1 AND simulation=b'1'"
EVENT_CHECK="simulation=b'1' AND ((event_type='ORDER_CREATED' AND aggregate_version=1) OR (event_type='PAYMENT_SIMULATED' AND aggregate_version=2))"
CHECKS={
 'chk_print_order_payment_v2':('print_order',ORDER_CHECK),
 'chk_print_order_event_payment_v2':('print_order_event',EVENT_CHECK)}
OLD_CHECKS={'chk_print_order_initial':('print_order',INITIAL),'chk_print_order_event_initial':('print_order_event',EVENT_OLD)}
TRANSITION_GUARD="""BEGIN
 IF NOT ({immutable}) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order immutable fields cannot change'; END IF;
 IF NOT (OLD.payment_state='UNPAID' AND OLD.print_state='NOT_CREATED' AND OLD.version=1 AND NEW.payment_state='SUCCEEDED' AND NEW.print_state='QUEUED' AND NEW.version=2) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unsupported order state transition'; END IF;
 IF (SELECT COUNT(*) FROM print_payment_receipt r WHERE r.order_id=NEW.id AND r.tenant_id=NEW.tenant_id AND r.store_id=NEW.store_id AND r.owner_user_id=NEW.owner_user_id
  AND r.provider='SIMULATED' AND r.state='SUCCEEDED' AND r.simulation=1 AND r.currency=NEW.currency AND r.amount=NEW.total_amount AND r.actor_type='STAFF' AND r.actor_id=NEW.owner_user_id)<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order transition requires matching simulated receipt'; END IF;
 IF (SELECT COUNT(*) FROM print_simulated_journal j JOIN print_payment_receipt r ON j.receipt_id=r.id
  WHERE r.order_id=NEW.id AND j.order_id=NEW.id AND j.tenant_id=NEW.tenant_id AND j.store_id=NEW.store_id AND j.owner_user_id=NEW.owner_user_id
  AND r.tenant_id=NEW.tenant_id AND r.store_id=NEW.store_id AND r.owner_user_id=NEW.owner_user_id
  AND j.simulation=1 AND j.amount=NEW.total_amount AND j.currency=NEW.currency)<>2 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order transition requires paired simulated journal'; END IF;
 IF (SELECT COUNT(*) FROM print_job j JOIN print_payment_receipt r ON j.payment_receipt_id=r.id JOIN print_order_item i ON j.order_item_id=i.id
  WHERE j.order_id=NEW.id AND r.order_id=NEW.id AND i.order_id=NEW.id
  AND j.tenant_id=NEW.tenant_id AND j.store_id=NEW.store_id AND j.owner_user_id=NEW.owner_user_id
  AND r.tenant_id=NEW.tenant_id AND r.store_id=NEW.store_id AND r.owner_user_id=NEW.owner_user_id
  AND i.tenant_id=NEW.tenant_id AND i.store_id=NEW.store_id AND i.owner_user_id=NEW.owner_user_id
  AND j.file_id=i.file_id AND j.file_content_sha256=i.file_content_sha256 AND j.generation=0 AND j.simulation=1 AND j.dispatch_state='QUEUED' AND j.print_result='NOT_REPORTED')<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order transition requires one queued print job'; END IF;
 IF (SELECT COUNT(*) FROM print_order_event e WHERE e.order_id=NEW.id AND e.tenant_id=NEW.tenant_id AND e.store_id=NEW.store_id AND e.owner_user_id=NEW.owner_user_id
  AND e.event_type='PAYMENT_SIMULATED' AND e.aggregate_version=2 AND e.simulation=1 AND e.actor_type='STAFF' AND e.actor_id=NEW.owner_user_id)<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order transition requires simulated payment event'; END IF;
END""".format(immutable=' AND '.join('(OLD.'+c+' <=> NEW.'+c+')' for c in IMMUTABLE))
TRIGGERS={'trg_print_order_update_guard_v2':('UPDATE','print_order',TRANSITION_GUARD)}
for short,table in [('payment_receipt','print_payment_receipt'),('simulated_journal','print_simulated_journal'),('job','print_job')]:
 TRIGGERS['trg_'+short+'_immutable']=('UPDATE',table,"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Demo payment records are immutable'")
 TRIGGERS['trg_'+short+'_delete_guard']=('DELETE',table,"BEGIN IF USER() <> 'root@localhost' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Demo records require local root maintenance'; END IF; END")
INDEXES={
 ('print_payment_receipt','PRIMARY'):'id',
 ('print_simulated_journal','PRIMARY'):'id',
 ('print_job','PRIMARY'):'id',
 ('print_payment_receipt','uk_print_payment_order'):'order_id',
 ('print_payment_receipt','uk_print_payment_idempotency'):'tenant_id,store_id,owner_user_id,idempotency_sha256',
 ('print_payment_receipt','uk_print_payment_scope'):'tenant_id,store_id,owner_user_id,id',
 ('print_simulated_journal','uk_print_journal_side'):'receipt_id,entry_side',
 ('print_job','uk_print_job_intent'):'order_item_id,generation'}
# Explicit ordered foreign-key contracts; default InnoDB NO ACTION and RESTRICT
# are equivalent, but CASCADE/SET NULL must never pass as the same contract.
FOREIGN_KEYS={
 'fk_print_payment_order':('print_payment_receipt','tenant_id,store_id,owner_user_id,order_id','print_order','tenant_id,store_id,owner_user_id,id'),
 'fk_print_journal_receipt':('print_simulated_journal','tenant_id,store_id,owner_user_id,receipt_id','print_payment_receipt','tenant_id,store_id,owner_user_id,id'),
 'fk_print_journal_order':('print_simulated_journal','tenant_id,store_id,owner_user_id,order_id','print_order','tenant_id,store_id,owner_user_id,id'),
 'fk_print_job_order':('print_job','tenant_id,store_id,owner_user_id,order_id','print_order','tenant_id,store_id,owner_user_id,id'),
 'fk_print_job_item':('print_job','order_item_id','print_order_item','id'),
 'fk_print_job_receipt':('print_job','tenant_id,store_id,owner_user_id,payment_receipt_id','print_payment_receipt','tenant_id,store_id,owner_user_id,id')}

def sql(source):
 p=subprocess.run(['sudo','-n','mysql','--batch','--raw','--skip-column-names','print_demo'],input=source,text=True,capture_output=True)
 if p.returncode:raise RuntimeError('payment_migration_database_operation_failed')
 return p.stdout.rstrip('\n')
def norm(value):return legacy.norm(value)

def checknorm(value):
 """Parse a deliberately small CHECK grammar, retaining boolean precedence.

 Parentheses are removed only through parsing. Flattening identical AND/OR
 nodes permits metadata's redundant grouping without moving a condition across
 a different boolean operator. Unknown syntax is never accepted.
 """
 tokens=legacy.sql_tokens(value);position=0
 def peek(text):return position<len(tokens) and tokens[position]==('word' if text.isalpha() else 'symbol',text)
 def take(text):
  nonlocal position
  if peek(text):position+=1;return True
  return False
 def operand():
  nonlocal position
  if take('('):
   node=expression('or')
   if not take(')'):raise RuntimeError('invalid_check_parentheses')
   return node
  if position==len(tokens):raise RuntimeError('invalid_check_operand')
  token=tokens[position];position+=1
  if token[0] not in ('word','string','number') or (token[0]=='word' and token[1] in ('and','or','not','is','null')):
   raise RuntimeError('unsupported_check_operand')
  return token
 def predicate():
  if take('not'):return ('not',predicate())
  left=operand()
  if take('is'):
   negated=take('not')
   if not take('null'):raise RuntimeError('unsupported_check_is_predicate')
   return ('is_not_null' if negated else 'is_null',left)
  if position<len(tokens) and tokens[position] in [('symbol',op) for op in ('=','<>','!=','<','>','<=','>=','<=>')]:
   operator=tokens[position][1];take(operator)
   return ('compare',operator,left,operand())
  return left
 def expression(level):
  child=(lambda:expression('and')) if level=='or' else predicate
  nodes=[child()]
  while take(level):nodes.append(child())
  if len(nodes)==1:return nodes[0]
  flattened=[]
  for node in nodes:
   flattened.extend(node[1:] if node[0]==level else [node])
  return (level,*flattened)
 result=expression('or')
 if position!=len(tokens):raise RuntimeError('unsupported_check_expression')
 return result

def trigger_state(name):
 return sql("SELECT CONCAT(ACTION_TIMING,'|',EVENT_MANIPULATION,'|',EVENT_OBJECT_TABLE,'|',ACTION_STATEMENT) FROM information_schema.TRIGGERS "
            f"WHERE TRIGGER_SCHEMA='print_demo' AND TRIGGER_NAME='{name}';")
def check_state(name):
 # Fetch identity/enforcement with the expression. A similarly named CHECK on
 # another table, or a NOT ENFORCED CHECK, must never stand in for protection.
 return sql("SELECT CONCAT(c.CONSTRAINT_SCHEMA,'|',t.TABLE_SCHEMA,'|',t.TABLE_NAME,'|',t.CONSTRAINT_TYPE,'|',t.ENFORCED,'|',c.CHECK_CLAUSE) "
            "FROM information_schema.CHECK_CONSTRAINTS c JOIN information_schema.TABLE_CONSTRAINTS t "
            "ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME "
            f"WHERE c.CONSTRAINT_SCHEMA='print_demo' AND c.CONSTRAINT_NAME='{name}';")

def column_names(table):
 return sql(f"SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION;").splitlines()
def schema_columns():
 result={}
 for table,body in re.findall(r'CREATE TABLE IF NOT EXISTS (\w+) \((.*?)PRIMARY KEY',SCHEMA.read_text(),re.S):
  expected=[]
  for name,kind,size,rest,null in re.findall(r'\b([a-z]\w*)\s+(CHAR|VARCHAR|BIGINT|INT|BIT)(?:\((\d+)\))?([^,]*?)\s+(NOT NULL|NULL)(?:,|\s*$)',body,re.M):
   dtype=kind.lower()+('('+size+')' if size else '(1)' if kind=='BIT' else '')
   expected.append((name,dtype,'NO' if null=='NOT NULL' else 'YES','ascii' if kind in ('CHAR','VARCHAR') else '-', 'ascii_bin' if kind in ('CHAR','VARCHAR') else '-'))
  if not expected:raise RuntimeError('schema_contract_parse_failed')
  result[table]=expected
 return result
def inspect_columns(table):
 rows=sql("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(CHARACTER_SET_NAME,'-'),COALESCE(COLLATION_NAME,'-') FROM information_schema.COLUMNS "
          f"WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION;")
 return [tuple(line.split('\t')) for line in rows.splitlines()]
def require_trigger(name,event,table,statement,optional=False):
 existing=trigger_state(name)
 if not existing and optional:return
 if norm(existing)!=norm('BEFORE|'+event+'|'+table+'|'+statement):raise RuntimeError('payment_trigger_contract_mismatch')
def require_check(name,table,statement,optional=False):
 existing=check_state(name)
 if not existing and optional:return
 fields=existing.split('|',5)
 if len(fields)!=6 or fields[:5]!=['print_demo','print_demo',table,'CHECK','YES']:
  raise RuntimeError('payment_check_identity_or_enforcement_mismatch')
 if checknorm(fields[5])!=checknorm(statement):raise RuntimeError('payment_check_contract_mismatch')
def schema_checks():
 result={}
 for table,body in re.findall(r'CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\n\) ENGINE=',SCHEMA.read_text(),re.S):
  result[table]=dict(re.findall(r'CONSTRAINT (\w+) CHECK\((.*)\)[,\n]',body+'\n'))
 if set(result)!={'print_payment_receipt','print_simulated_journal','print_job'} or sum(map(len,result.values()))!=10:
  raise RuntimeError('schema_check_contract_parse_failed')
 return result

def require_table_constraints(table,checks):
 engine=sql(f"SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='{table}';")
 if engine!='InnoDB':raise RuntimeError('payment_table_engine_mismatch')
 for (index_table,index),columns in INDEXES.items():
  if index_table!=table:continue
  # Include prefixes/type/visibility so a similarly named partial index cannot pass.
  actual=sql("SELECT CONCAT(COALESCE(COLUMN_NAME,'<expression>'),'|',COALESCE(CAST(SUB_PART AS CHAR),'FULL'),'|',INDEX_TYPE,'|',IS_VISIBLE) "
             "FROM information_schema.STATISTICS "
             f"WHERE TABLE_SCHEMA='print_demo' AND TABLE_NAME='{table}' AND INDEX_NAME='{index}' AND NON_UNIQUE=0 ORDER BY SEQ_IN_INDEX;")
  if actual.splitlines()!=[column+'|FULL|BTREE|YES' for column in columns.split(',')]:
   raise RuntimeError('payment_unique_index_verification_failed')
 actual_checks=sql("SELECT CONCAT(CONSTRAINT_NAME,'|',ENFORCED) FROM information_schema.TABLE_CONSTRAINTS "
                  f"WHERE CONSTRAINT_SCHEMA='print_demo' AND TABLE_NAME='{table}' AND CONSTRAINT_TYPE='CHECK' ORDER BY CONSTRAINT_NAME;")
 if actual_checks.splitlines()!=[name+'|YES' for name in sorted(checks)]:
  raise RuntimeError('payment_table_check_set_mismatch')
 for name,statement in checks.items():require_check(name,table,statement)
 rows=sql("SELECT CONCAT(r.CONSTRAINT_NAME,'|',k.ORDINAL_POSITION,'|',k.COLUMN_NAME,'|',k.REFERENCED_TABLE_SCHEMA,'|',"
          "k.REFERENCED_TABLE_NAME,'|',k.REFERENCED_COLUMN_NAME,'|',r.UPDATE_RULE,'|',r.DELETE_RULE) "
          "FROM information_schema.REFERENTIAL_CONSTRAINTS r JOIN information_schema.KEY_COLUMN_USAGE k "
          "ON k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME AND k.TABLE_NAME=r.TABLE_NAME "
          f"WHERE r.CONSTRAINT_SCHEMA='print_demo' AND r.TABLE_NAME='{table}' ORDER BY r.CONSTRAINT_NAME,k.ORDINAL_POSITION;")
 actual=[]
 for row in rows.splitlines():
  parts=row.split('|')
  if len(parts)!=8:raise RuntimeError('payment_foreign_key_contract_mismatch')
  parts[-2:]=['RESTRICT' if action=='NO ACTION' else action for action in parts[-2:]]
  actual.append(tuple(parts))
 expected=[]
 for name,(source_table,columns,target_table,target_columns) in sorted(FOREIGN_KEYS.items()):
  if source_table!=table:continue
  for ordinal,(column,target_column) in enumerate(zip(columns.split(','),target_columns.split(',')),1):
   expected.append((name,str(ordinal),column,'print_demo',target_table,target_column,'RESTRICT','RESTRICT'))
 if actual!=expected:raise RuntimeError('payment_foreign_key_contract_mismatch')

def require_payment_tables(expected_columns,new_checks):
 for table,expected in expected_columns.items():
  if inspect_columns(table)!=expected:raise RuntimeError('payment_column_verification_failed')
  require_table_constraints(table,new_checks[table])

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--apply',action='store_true');args=ap.parse_args()
 if not args.apply:print('NOT_APPLIED; requires reviewed A-to-B migration and disabled payment writes');return
 if shutil.disk_usage(BASE).free<10*1024**3:raise RuntimeError('low_disk_stop')
 lockdir=BASE/'runtime/locks';lockdir.mkdir(parents=True,exist_ok=True)
 with (lockdir/'fixture-mutation.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  for table,expected in legacy.TABLES.items():
   if column_names(table)!=expected:raise RuntimeError('legacy_order_columns_mismatch')
  for name,(event,table,statement) in legacy.TRIGGERS.items():
   require_trigger(name,event,table,statement,optional=name=='trg_print_order_immutable')
  if not trigger_state('trg_print_order_immutable') and not trigger_state('trg_print_order_update_guard_v2'):
   raise RuntimeError('order_update_protection_missing')
  for name,(event,table,statement) in TRIGGERS.items():require_trigger(name,event,table,statement,optional=True)
  for name,(table,statement) in OLD_CHECKS.items():require_check(name,table,statement,optional=True)
  for name,(table,statement) in CHECKS.items():require_check(name,table,statement,optional=True)
  if not check_state('chk_print_order_initial') and not check_state('chk_print_order_payment_v2'):raise RuntimeError('order_state_check_missing')
  if not check_state('chk_print_order_event_initial') and not check_state('chk_print_order_event_payment_v2'):raise RuntimeError('event_check_missing')
  expected_columns=schema_columns()
  if set(expected_columns)!={'print_payment_receipt','print_simulated_journal','print_job'}:raise RuntimeError('schema_table_contract_mismatch')
  new_checks=schema_checks()
  for table,expected in expected_columns.items():
   current=inspect_columns(table)
   if current:
    if current!=expected:raise RuntimeError('existing_payment_table_contract_mismatch')
    # IF NOT EXISTS cannot repair a drifted table: reject it before backup/DDL.
    require_table_constraints(table,new_checks[table])
  if sql('SELECT COUNT(*) FROM print_order WHERE NOT ('+ORDER_CHECK+');')!='0':raise RuntimeError('unsupported_existing_order_state')
  backup=subprocess.run(['python3',str(BASE/'ops/backup/backup_verify.py')],capture_output=True,text=True)
  if backup.returncode:raise RuntimeError('pre_payment_migration_backup_failed')
  sql(SCHEMA.read_text())
  for name,(event,table,statement) in TRIGGERS.items():
   if not trigger_state(name):sql(f'DELIMITER $$\nCREATE TRIGGER {name} BEFORE {event} ON {table} FOR EACH ROW {statement}$$\nDELIMITER ;\n')
   require_trigger(name,event,table,statement)
  for name,(table,statement) in CHECKS.items():
   if not check_state(name):sql(f'ALTER TABLE {table} ADD CONSTRAINT {name} CHECK ({statement});')
   require_check(name,table,statement)
  # DDL auto-commits: verify every new table constraint before removing A protection.
  require_payment_tables(expected_columns,new_checks)
  # Recheck both old and replacement order/event protections at the removal
  # boundary: new-table validation alone cannot prove these CHECKs enforce.
  for name,(table,statement) in CHECKS.items():require_check(name,table,statement)
  for name,(table,statement) in OLD_CHECKS.items():require_check(name,table,statement,optional=True)
  # Replacement protections exist and are verified before any old restriction is removed.
  for name,(table,statement) in OLD_CHECKS.items():
   if check_state(name):sql(f'ALTER TABLE {table} DROP CHECK {name};')
  if trigger_state('trg_print_order_immutable'):sql('DROP TRIGGER trg_print_order_immutable;')
  for name,(event,table,statement) in TRIGGERS.items():require_trigger(name,event,table,statement)
  for name,(table,statement) in CHECKS.items():require_check(name,table,statement)
  require_payment_tables(expected_columns,new_checks)
  result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'schema_sha256':hashlib.sha256(SCHEMA.read_bytes()).hexdigest(),
          'tables':list(expected_columns),'column_types_verified':sum(map(len,expected_columns.values())),'unique_indexes_verified':len(INDEXES),
          'foreign_keys_verified':len(FOREIGN_KEYS),'foreign_key_definitions_verified':list(FOREIGN_KEYS),'new_table_checks_verified':sum(map(len,new_checks.values())),'replacement_checks_verified':2,'new_triggers_verified':len(TRIGGERS),
          'order_immutable_fields':IMMUTABLE,'business_rows_seeded':False,'runtime_enabled':False,'pre_migration_backup_restore_verified':True,
          'remaining_free_bytes':shutil.disk_usage(BASE).free}
  out=BASE/'evidence/database/print-payments-v1.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':
 try:main()
 except Exception as exc:
  print(json.dumps({'status':'FAILED','exception_class':type(exc).__name__}),file=sys.stderr);sys.exit(1)
