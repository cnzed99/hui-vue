#!/usr/bin/env python3
"""Only an explicitly provisioned private MySQL socket. No production connection or credentials."""
import argparse,hashlib,json,pathlib,re,subprocess,time,uuid
parser=argparse.ArgumentParser();parser.add_argument('--socket',required=True);parser.add_argument('--datadir',required=True);parser.add_argument('--output',required=True);a=parser.parse_args()
base=pathlib.Path(__file__).resolve().parents[2];output=pathlib.Path(a.output).resolve();output.mkdir(parents=True,exist_ok=True)
sock=pathlib.Path(a.socket).resolve();datadir=pathlib.Path(a.datadir).resolve()
if not str(sock).startswith('/tmp/print-photo-collation-') or sock.parent!=datadir.parent:raise SystemExit('Expected owned isolated socket/datadir')
client=['mysql','--no-defaults','--protocol=socket','--socket='+str(sock),'-uroot','--batch','--raw','--skip-column-names']
runs=[]
def run(name,sql,db=None,ok=True,contains=None):
 p=subprocess.run(client+([db] if db else []),input=sql,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 (output/(name+'.log')).write_text(p.stdout+p.stderr)
 runs.append({'name':name,'exit':p.returncode,'sha256':hashlib.sha256((p.stdout+p.stderr).encode()).hexdigest()})
 if (p.returncode==0)!=ok or contains and contains not in p.stdout+p.stderr:raise AssertionError(name+': '+p.stderr[-1000:])
 return p.stdout.strip()
if pathlib.Path(run('isolation','SELECT @@datadir;')).resolve()!=datadir:raise SystemExit('Datadir identity mismatch')
old=subprocess.check_output(['git','show','e0816dafad22d7619decf9b17c3807860e278d48:ops/db/print-miniapp-demo-photo-6in.sql'],cwd=base)
if hashlib.sha256(old).hexdigest()!='28889a04225006e6a5186f1e63a1e96996b3e521577b1d01ab355d69448df8dd':raise SystemExit('Historical source mismatch')
fixture=(base/'ops/acceptance/photo-6in-collation-fixture.sql').read_text();fixed=(base/'ops/db/print-miniapp-demo-photo-6in-trigger-collation-forward.sql').read_text()
assert len(re.findall(r'FOR UPDATE',fixed))==3
# Forward file may contain SET/SHOW, but its only DDL is one named trigger DROP+CREATE.
assert re.findall(r'(?im)^(DROP|CREATE)\s+([^\n]+)',fixed)==[('DROP','TRIGGER trg_print_demo_quote_document_only;'),('CREATE','TRIGGER trg_print_demo_quote_document_only BEFORE INSERT ON print_miniapp_demo_quote FOR EACH ROW')]
owner='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';foreign='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';doc='11111111-1111-1111-1111-111111111111';photo='22222222-2222-2222-2222-222222222222';identity='33333333-3333-3333-3333-333333333333';document_profile='8cf69353-bc69-5039-ad47-b59e3c791c76'
def seed(db):
 run(db+'-seed',f"INSERT INTO print_miniapp_demo_principal VALUES('{owner}',1,1),('{foreign}',1,1);INSERT INTO print_miniapp_demo_file VALUES('{doc}',1,1,'{owner}','DOCUMENT',NULL,1),('{photo}',1,1,'{owner}','PHOTO','PHOTO_6IN',1),('{identity}',1,1,'{owner}','ID_PHOTO','ONE_INCH',1);",db)
def quote(file,profile,principal=owner):return f"INSERT INTO print_miniapp_demo_quote VALUES('{uuid.uuid4()}',1,1,'{principal}','{file}','{profile}');"
def matrix(db):
 active=run(db+'-active','SELECT active_profile_id FROM print_photo_price_binding;',db)
 cases=[('document-valid',doc,document_profile,owner,0,True),('document-other-profile',doc,active,owner,0,False),('document-wrong-owner',doc,document_profile,foreign,0,False),('gate0-photo',photo,active,owner,0,False),('gate0-id',identity,active,owner,0,False),('gate1-photo',photo,active,owner,1,True),('gate1-id',identity,active,owner,1,True),('photo-document-profile',photo,document_profile,owner,1,False),('id-document-profile',identity,document_profile,owner,1,False),('photo-owner',photo,active,foreign,1,False)]
 for label,file,profile,actor,gate,allow in cases:
  run(db+'-'+label,f"SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;START TRANSACTION;UPDATE print_photo_price_binding SET quote_enabled={gate};"+quote(file,profile,actor)+'ROLLBACK;',db,allow,None if allow else '(45000)')
 for label,key,version,mode,currency in [('bad-version','MINIAPP_DEMO_PHOTO_6IN','2','DEMO','CNY'),('bad-key','OTHER','1','DEMO','CNY'),('bad-mode','MINIAPP_DEMO_PHOTO_6IN','1','LIVE','CNY'),('bad-currency','MINIAPP_DEMO_PHOTO_6IN','1','DEMO','USD')]:
  pid=str(uuid.uuid4());run(db+'-seed-'+label,f"INSERT INTO print_price_profile VALUES('{pid}',1,1,'{key}','{version}','{mode}','{currency}','1.00','1.00',NULL,1000);",db)
  for file,name in [(photo,'photo'),(identity,'id')]:run(db+'-'+label+'-'+name,f"SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;START TRANSACTION;UPDATE print_photo_price_binding SET active_profile_id='{pid}',revision=1,quote_enabled=1;"+quote(file,pid)+'ROLLBACK;',db,False,'(45000)')
 check=run(db+'-final-gate','SELECT quote_enabled FROM print_photo_price_binding;',db);assert check=='0'
for db in ['photo_forward','photo_fresh']:
 run(db+'-create',f'CREATE DATABASE {db} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
 run(db+'-fixture','SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n'+fixture,db)
 migration=old.decode() if db=='photo_forward' else (base/'ops/db/print-miniapp-demo-photo-6in.sql').read_text()
 run(db+'-migration','SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n'+migration,db);seed(db)
 if db=='photo_forward':
  meta=run('old-trigger-metadata',"SELECT DATABASE_COLLATION,COLLATION_CONNECTION,CHARACTER_SET_CLIENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_print_demo_quote_document_only';",db)
  assert meta=='utf8mb4_unicode_ci\tutf8mb4_0900_ai_ci\tutf8mb4',meta
  active=run('old-active','SELECT active_profile_id FROM print_photo_price_binding;',db)
  run('historical-1267-reproduction',"SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;START TRANSACTION;UPDATE print_photo_price_binding SET quote_enabled=1;"+quote(photo,active)+'ROLLBACK;',db,False,'ERROR 1267')
  run('forward-repair',fixed,db)
 meta=run(db+'-fixed-metadata',"SELECT DATABASE_COLLATION,COLLATION_CONNECTION,CHARACTER_SET_CLIENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_print_demo_quote_document_only';",db)
 assert meta=='utf8mb4_unicode_ci\tutf8mb4_bin\tutf8mb4',meta
 body=run(db+'-fixed-body',"SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_print_demo_quote_document_only';",db)
 expected=fixed[fixed.index('\nBEGIN\n')+1:fixed.index('\nEND//')+len('\nEND')].strip()
 assert body==expected,'Exact body readback mismatch'
 matrix(db)
record={'result':'PASS','kind':'ISOLATED_MYSQL_COLLATION_FORWARD_AND_FRESH_INSTALL','oldMigrationSha256':hashlib.sha256(old).hexdigest(),'repairSha256':hashlib.sha256(fixed.encode()).hexdigest(),'databaseCollation':'utf8mb4_unicode_ci','oldCreationConnection':'utf8mb4_0900_ai_ci','fixedCreationConnection':'utf8mb4_bin','matrices':2,'matrixCasesEach':18,'runs':runs,'notRun':['Production DB/trigger/service access','Production repair','Gate enable','Deployment','Java/JAR build']}
(output/'manifest.json').write_text(json.dumps(record,indent=2)+'\n');print('PHOTO_COLLATION_FORWARD_PASS historical1267 forward18 fresh18 production_not_touched')
