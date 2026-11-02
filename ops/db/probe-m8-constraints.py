#!/usr/bin/env python3
"""M8 SQL protections against real MySQL in an isolated schema only."""
import json
from pathlib import Path
import re
import subprocess
import sys
import time

db = sys.argv[1]
if not re.fullmatch(r'print_m8_probe_[0-9]+_[0-9]+', db):
    raise SystemExit('isolated database name required')
here = Path(__file__).resolve().parent
results = []

def sql(text, *, ok=True, label=''):
    p = subprocess.run(['sudo', 'mysql', '--batch', '--skip-column-names', db],
                       input=text, text=True, capture_output=True)
    if ok != (p.returncode == 0):
        raise AssertionError(f'{label}: rc={p.returncode} {p.stderr}')
    if label:
        results.append({'case': label, 'result': 'PASS', 'mysqlExit': p.returncode,
                        'error': p.stderr.strip() if p.returncode else None})
    return p

def uid(n): return f'10000000-0000-4000-8000-{n:012d}'
principal = uid(99)
scope = (1, 1, principal)
orders = [uid(n) for n in range(1, 5)]
task_ids = [uid(21), uid(22)]

# Fixture parents only: their unrelated FK graph is intentionally omitted in a fresh schema.
# FK checks are restored before any M8 record or negative case is executed.
seed = ['SET FOREIGN_KEY_CHECKS=0;']
for n, order in enumerate(orders, 1):
    paid = n != 1
    state, version = ('NOT_CREATED', 2 if paid else 1)
    if n == 3: state, version = 'CANCELLED', 4
    if n == 4: state, version = 'CLAIMED', 4
    order_state = 'CANCELLED' if n in (1, 2, 3) else 'OPEN'
    refund = 'SUCCEEDED' if n in (2, 3) else 'NONE'
    if n == 1: version = 2
    if n == 2: version = 3
    seed.append(f"INSERT INTO print_miniapp_demo_order VALUES ('{order}','MO{n:032d}',1,1,'{principal}','MINIAPP_DEMO',1,'CNY','1.00','{order_state}','{'SUCCEEDED' if paid else 'UNPAID'}','{refund}','{state}',{version},'{n:064x}','{n+10:064x}',1,1);")
    if paid:
        seed.append(f"INSERT INTO print_miniapp_demo_payment_receipt VALUES ('{uid(n+10)}','{order}',1,1,'{principal}','SIMULATED','DEMO','SUCCEEDED',1,'CNY','1.00','{n+20:064x}','{n+30:064x}',1);")
for index, n in enumerate((3, 4)):
    state, fence, lease, until = ('CANCELLED', 0, 'NULL', 'NULL') if n == 3 else ('CLAIMED', 1, f"'{uid(30)}'", '9999999999999')
    seed.append(f"INSERT INTO print_miniapp_demo_print_task VALUES ('{task_ids[index]}','PJ{n:032X}','{orders[n-1]}','{uid(40+n)}',1,1,'{principal}','{uid(50+n)}','{'a'*64}','1','1','{state}',2,{fence},{lease},{until},2,'{n+40:064x}','{n+50:064x}',1,1);")
seed.append('SET FOREIGN_KEY_CHECKS=1; SELECT @@FOREIGN_KEY_CHECKS;')
assert sql('\n'.join(seed)).stdout.strip() == '1'

fields = ['id','order_id','tenant_id','store_id','principal_id','idempotency_sha256','input_sha256','outcome','reason',
          'order_version_before','order_version_after','task_id','task_state_before','task_state_after',
          'task_version_before','task_version_after','task_event_seq_before','task_event_seq_after',
          'task_fence_before','task_fence_after','created_at_ms','simulation']

def literal(v):
    if v is None: return 'NULL'
    return "'" + v.replace("'", "''") + "'" if isinstance(v, str) else str(v)

def entry(kind):
    n = {'unpaid':1,'no_task':2,'queued':3,'manual':4}[kind]
    v = [uid(100+n),orders[n-1],*scope,f'{n+60:064x}',f'{n+70:064x}',
         'MANUAL_REVIEW_REQUIRED' if n==4 else 'CANCELLED_UNPAID' if n==1 else 'CANCELLED_REFUNDED',
         'REQUIRES_MANUAL_REVIEW' if n==4 else 'UNPAID' if n==1 else 'NEVER_CLAIMED',
         [1,2,3,4][n-1],[2,3,4,4][n-1],None,None,None,None,None,None,None,None,None,1,1]
    row = dict(zip(fields,v))
    if n >= 3:
        row.update(task_id=task_ids[n-3],task_state_before='QUEUED' if n==3 else 'CLAIMED',
                   task_state_after='CANCELLED' if n==3 else 'CLAIMED', task_version_before=1 if n==3 else 2,
                   task_version_after=2,task_event_seq_before=1 if n==3 else 2,task_event_seq_after=2,
                   task_fence_before=0 if n==3 else 1,task_fence_after=0 if n==3 else 1)
    return row

def insert(row):
    return 'INSERT INTO print_miniapp_demo_cancellation ('+','.join(fields)+') VALUES ('+','.join(literal(row[f]) for f in fields)+');'

def refund(row, outcome='CANCELLED_REFUNDED'):
    n=orders.index(row['order_id'])+1
    return f"INSERT INTO print_miniapp_demo_refund_receipt (id,cancellation_id,cancellation_outcome,payment_receipt_id,order_id,tenant_id,store_id,principal_id,provider,mode,state,simulation,currency,amount,created_at_ms) VALUES ('{uid(200+n)}','{row['id']}','{outcome}','{uid(n+10)}','{row['order_id']}',1,1,'{principal}','SIMULATED','DEMO','SUCCEEDED',1,'CNY','1.00',1);"

for kind in ('unpaid','no_task','queued','manual'):
    sql('START TRANSACTION;'+insert(entry(kind))+'ROLLBACK;', label='valid_'+kind)
for kind in ('queued','manual'):
    for f in fields[11:20]:
        row=entry(kind); row[f]=None
        sql('START TRANSACTION;'+insert(row)+'ROLLBACK;', ok=False, label=kind+'_null_'+f)
for field, value in [('task_event_seq_before',3),('task_event_seq_after',3),('order_version_before',5),('order_version_after',5)]:
    row=entry('manual'); row[field]=value
    sql('START TRANSACTION;'+insert(row)+'ROLLBACK;', ok=False, label='manual_wrong_'+field)
for kind in ('no_task','queued'):
    row=entry(kind)
    sql('START TRANSACTION;'+insert(row)+refund(row)+'ROLLBACK;', label='valid_refund_'+kind)
for kind in ('manual','unpaid'):
    row=entry(kind)
    # The unpaid fixture receives a synthetic original receipt solely to isolate outcome protection.
    extra=''
    if kind=='unpaid':
        extra=f"INSERT INTO print_miniapp_demo_payment_receipt VALUES ('{uid(11)}','{orders[0]}',1,1,'{principal}','SIMULATED','DEMO','SUCCEEDED',1,'CNY','1.00','{'f'*64}','{'e'*64}',1);"
    sql('START TRANSACTION;'+extra+insert(row)+refund(row)+'ROLLBACK;', ok=False,label='reject_refund_'+kind)
row=entry('manual')
sql('START TRANSACTION;'+insert(row)+refund(row,'MANUAL_REVIEW_REQUIRED')+'ROLLBACK;',ok=False,label='reject_refund_forged_outcome')

# Running backend/preview services must stop the controlled executor before it reaches SQL.
controlled=subprocess.run(['bash',str(here/'rollback-m8-no-writes-controlled.sh'),'--execute',db],text=True,capture_output=True)
assert controlled.returncode==76 and 'M8_NOT_DRAINED service=' in controlled.stderr, controlled.stdout+controlled.stderr
results.append({'case':'active_service_blocks_controlled_rollback_before_ddl','result':'PASS','exit':controlled.returncode})

# A held application-like transaction must fail the shared drain gate before any destructive SQL.
hold=subprocess.Popen(['sudo','mysql','--batch','--skip-column-names',db],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
hold.stdin.write(f"START TRANSACTION; SELECT id FROM print_miniapp_demo_order WHERE id='{orders[3]}' FOR UPDATE; SELECT SLEEP(30); ROLLBACK;\n")
hold.stdin.close()
time.sleep(1)
gate=subprocess.run(['bash',str(here/'assert-m8-db-drained.sh'),db],text=True,capture_output=True)
assert gate.returncode==76, gate.stdout+gate.stderr
tables=sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'print_miniapp_demo_%refund%';").stdout.strip()
assert tables=='2'
held_ids=sql('SELECT ID FROM information_schema.processlist WHERE DB=DATABASE() AND ID<>CONNECTION_ID();').stdout.split()
assert len(held_ids)==1 and held_ids[0].isdigit(), held_ids
sql('KILL CONNECTION '+held_ids[0]+';')
hold.wait(timeout=5)
results.append({'case':'inflight_transaction_blocks_drain_before_ddl','result':'PASS','exit':gate.returncode})

# Commit one legal immutable manual record and prove zero-write rollback refuses before DROP.
sql(insert(entry('manual')),label='persist_manual_record_for_rollback_gate')
rollback=(here/'print-miniapp-demo-cancellation-refund-rollback-no-writes.sql').read_text()
sql('SET @m8_backend_drained=1;\n'+rollback,ok=False,label='rollback_rejects_existing_m8_write')
assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('print_miniapp_demo_cancellation','print_miniapp_demo_refund_receipt','print_miniapp_demo_refund_journal');").stdout.strip()=='3'
print(json.dumps({'status':'PASS_SQL_CONSTRAINTS_ONLY','cases':results,'count':len(results)},indent=2))
