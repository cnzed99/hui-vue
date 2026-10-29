#!/usr/bin/env python3
"""One minimal source/model suite. No actual MySQL, HTTP, service or renderer execution."""
import copy, importlib.util, json, pathlib, sys, tempfile, threading, unittest, types
from unittest import mock
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'ops/db')); sys.path.insert(0,str(ROOT/'ops/acceptance'))
import ordinary_activation_common as c
import ordinary_modules_activation_acceptance as runtime
sys.path.insert(0,str(ROOT/'ops/runtime'))
import ordinary_activation_db as dbmod
import ordinary_activation_runtime as rtmod
import ordinary_activation_recovery as recovery
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path); module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module
ex=load('ordinary_executor',ROOT/'ops/db/migrate-ordinary-modules.py')
renderer=load('ordinary_renderer',ROOT/'ops/db/render-ordinary-modules-plan.py')
class Journal:
    def __init__(self): self.events=[]
    def append(self,phase,**_): self.events.append(phase)
    def close(self): pass
class Fake:
    def __init__(self,block=None,release=None,fail=None): self.calls=[]; self.block=block; self.release=release; self.fail=fail
    def __getattr__(self,name):
        def call(*args):
            self.calls.append((name,args))
            if name=='http_browser_acceptance' and self.block:
                self.block.set()
                if not self.release.wait(10): raise ValueError('test_timeout')
            if name in (self.fail or ()):
                raise OSError('simulated_unknown')
            return True
        return call
def plan(): return {'metadataStages':{stage:{'model':stage} for stage in c.STAGES},'permissionBefore':{},'permissionProposal':{}}
def baseline():
    ids=[1000,1001,2000,2999]
    return {'schema':'print_demo','tenants':[{'id':1,'package_id':1,'status':0,'deleted':0}], 'packages':[{'id':1,'status':0,'deleted':0,'menu_ids':ids}], 'roles':[{'id':rid,'tenant_id':1,'code':code,'status':0,'deleted':0} for rid,code in ((1,'tenant_admin'),(2,'print_reader'))], 'menus':[{'id':i,'type':1,'status':0,'deleted':0,'permission':''} for i in ids], 'grants':[{'id':i+1,'tenant_id':1,'role_id':rid,'menu_id':m,'deleted':0} for i,(rid,m) in enumerate([(1,m) for m in ids]+[(2,2000),(2,1000)])]}
class Tests(unittest.TestCase):
    def lockfile(self,directory):
        p=pathlib.Path(directory)/'fixture-mutation.lock'; p.touch(); return p
    def test_01_A_holds_lock_B_zero_actions_and_eleven_durable_stages(self):
        with tempfile.TemporaryDirectory() as directory:
            lock=self.lockfile(directory); entered=threading.Event(); release=threading.Event(); a=Fake(entered,release); j=Journal(); errors=[]
            def work():
                try: ex.run_model(plan(),a,lambda:j,lock)
                except BaseException as error: errors.append(type(error).__name__)
            thread=threading.Thread(target=work); thread.start(); self.assertTrue(entered.wait(5))
            b=Fake(); journals=[]
            try:
                with self.assertRaises(BlockingIOError): ex.run_model(plan(),b,lambda:journals.append(1),lock)
                self.assertEqual(b.calls,[]); self.assertEqual(journals,[])
            finally: release.set(); thread.join(10)
            self.assertFalse(thread.is_alive()); self.assertEqual(errors,[])
            ddl=[args[0] for name,args in a.calls if name=='execute_ddl']; self.assertEqual(ddl,c.ddl_statements()); self.assertEqual(len(ddl),11)
            self.assertEqual([e for e in j.events if '_DDL_' in e],[stage+suffix for stage in c.STAGES[1:] for suffix in ('_DDL_INTENT','_DDL_OBSERVED')])
            self.assertLess(j.events.index('TOKEN_CLEAN'),j.events.index('READY_TO_REOPEN'))
    def test_02_full_union_twenty_five_grants_and_physical_conflict(self):
        before=baseline(); frozen=copy.deepcopy(before); result=c.plan_permissions(before)
        self.assertEqual(before,frozen); self.assertEqual(result['packageAfter'],sorted(set(before['packages'][0]['menu_ids'])|set(range(2010,2026))))
        self.assertEqual(result['newGrants'],[list(pair) for pair in c.GRANTS]); self.assertEqual(len(result['newGrants']),25)
        self.assertTrue({2010,2011,2012,2013,2999}<=set(result['roleAfter']['1']));self.assertEqual(len(c.PERMISSIONS),16);self.assertEqual(sum(r==1 for r,m in c.GRANTS),15);self.assertEqual(sum(r==2 for r,m in c.GRANTS),10); self.assertNotIn(2017,result['roleAfter']['1']); self.assertNotIn(2017,result['roleAfter']['2'])
        before['menus'].append({'id':2014,'permission':'','deleted':1})
        with self.assertRaises(ValueError): c.plan_permissions(before)
    def test_03_unknown_commit_and_sessions_never_reopen_or_retry(self):
        for failures in ({'commit_acknowledged'},{'login_exact_ledger_sessions','cleanup_access_refresh_and_browser_zero'}):
            with self.subTest(failures=sorted(failures)),tempfile.TemporaryDirectory() as directory:
                fake=Fake(fail=failures); journal=Journal()
                with self.assertRaises(OSError): ex.run_model(plan(),fake,lambda:journal,self.lockfile(directory))
                names=[name for name,_ in fake.calls]
                self.assertNotIn('open_ingress_and_external_verify',names); self.assertNotIn('TOKEN_CLEAN',journal.events); self.assertEqual(names.count('commit_acknowledged'),1)
                self.assertIn('ensure_ingress_closed',names); self.assertIn('STOPPED_REQUIRES_REVIEWED_RECOVERY',journal.events)
    def test_04_renderer_rejects_production_and_metadata_reader_is_shared(self):
        class Sink:
            def write(self,*_): pass
            def flush(self): pass
        isolated=renderer.Isolated('print_ordinary_render_'+'a'*32,Sink(),None)
        with self.assertRaises(ValueError): isolated.command('CREATE TABLE anything (id INT)','print_demo','structure')
        with self.assertRaises(ValueError): isolated.command('SELECT * FROM print_demo.system_menu',isolated.schema,'metadata')
        class Reader:
            def __init__(self): self.sql=[]
            def objects(self,sql): self.sql.append(sql); return []
        reader=Reader(); graph=c.metadata(reader)
        self.assertEqual(set(graph),c.METADATA_KEYS); self.assertEqual(len(reader.sql),7); self.assertIs(renderer.metadata,c.metadata); self.assertIs(ex.metadata,c.metadata)
    def test_05_pending_approval_and_empty_material_are_not_runtime_pass(self):
        with self.assertRaises(ValueError): ex.verify_plan({'status':'DRAFT_NOT_EXECUTABLE'},{},'a'*64,'bad','')
        self.assertEqual(runtime.slot_removal_http()['SLOT_REMOVAL_HTTP'],'NOT_RUN'); self.assertFalse(runtime.slot_removal_http()['blocksEmptyDraftActivation']); self.assertFalse(runtime.Runtime.implemented)
    def test_06_cleanup_side_effect_then_unknown_is_never_replayed(self):
        class CleanupSideEffectThenUnknown(Fake):
            def __init__(self): super().__init__(); self.cleanup_side_effects=0
            def cleanup_access_refresh_and_browser_zero(self,*args):
                self.calls.append(('cleanup_access_refresh_and_browser_zero',args))
                self.cleanup_side_effects+=1
                if self.cleanup_side_effects==1:
                    raise OSError('side_effect_completed_response_unknown')
                return True  # A replay would appear successful and must never be attempted.
        with tempfile.TemporaryDirectory() as directory:
            fake=CleanupSideEffectThenUnknown(); journal=Journal()
            with self.assertRaises(OSError): ex.run_model(plan(),fake,lambda:journal,self.lockfile(directory))
            self.assertEqual(fake.cleanup_side_effects,1)
            self.assertEqual(sum(name=='cleanup_access_refresh_and_browser_zero' for name,_ in fake.calls),1)
            self.assertIn('SESSION_CLEANUP_UNKNOWN',journal.events)
            self.assertNotIn('TOKEN_CLEAN',journal.events); self.assertNotIn('READY_TO_REOPEN',journal.events)
            self.assertFalse(any(name=='open_ingress_and_external_verify' for name,_ in fake.calls))
    def test_07_permission_proposal_json_roundtrip_is_identical(self):
        proposal=c.plan_permissions(baseline())
        roundtrip=json.loads(json.dumps(proposal))
        self.assertEqual(set(proposal['roleAfter']),{'1','2'})
        self.assertEqual(proposal,roundtrip)
        self.assertEqual(c.plan_permissions(roundtrip['before']),roundtrip)

# Four integration scenarios below share these explicit in-memory ports only.
class MemoryJournal(Journal):
    def __init__(self):super().__init__();self.rows=[]
    def append(self,phase,**safe):super().append(phase);self.rows.append(dict(phase=phase,**safe))
class Lease:
    def __init__(self):self.held=False
    def __enter__(self):self.held=True;return self
    def check(self):
        if not self.held:raise ValueError('lock_not_held')
    def __exit__(self,*_):self.held=False
class FakeSession:
    def __init__(self,state):self.state=state;self.working=None;self.meta_index=0
    def objects(self,sql):
        if sql.startswith("SELECT JSON_OBJECT('schema',DATABASE()"):return [copy.deepcopy(self.state.plan['dbIdentity'])]
        if sql.startswith('SELECT JSON_OBJECT('):return self.query(sql,())
        key=list(c.METADATA_KEYS_ORDER)[self.meta_index%7];self.meta_index+=1
        return copy.deepcopy(self.state.plan['metadataStages'][c.STAGES[self.state.stage]][key])
    def query(self,sql,params):
        self.state.sql.append((sql,params));table=sql.split(' FROM ')[1].split()[0].strip('`');key=next(k for k,t in dbmod.TABLES.items() if t==table)
        rows=copy.deepcopy((self.working if self.working is not None else self.state.data)[key])
        if self.state.drift and sql.endswith('FOR UPDATE') and key=='tenants':rows[0]['status']=1
        return rows
    def execute(self,sql,params):
        self.state.sql.append((sql,params))
        if sql.startswith(('CREATE TABLE ','ALTER TABLE ')):self.state.stage+=1;return 0
        if sql=='SET TRANSACTION ISOLATION LEVEL SERIALIZABLE':return 0
        if sql=='START TRANSACTION':self.working=copy.deepcopy(self.state.data);return 0
        if sql=='ROLLBACK':self.working=None;return 0
        if sql.startswith('INSERT INTO system_menu '):self.working['menus'].append(dict(zip(dbmod.MENU_COLUMNS,params)));self.working['menus'].sort(key=lambda r:r['id']);return 1
        if sql.startswith('INSERT INTO system_role_menu '):self.working['grants'].append(dict(zip(dbmod.GRANT_COLUMNS,params)));self.working['grants'].sort(key=lambda r:r['id']);return 1
        if sql.startswith('UPDATE system_tenant_package '):self.working['packages'][0]['menu_ids']=json.loads(params[0]);return 1
        raise AssertionError('SQL outside fixed fake allowlist')
    def commit(self):self.state.sql.append(('COMMIT',()));self.state.data=self.working;self.working=None;return 'ACK'
    def close(self):self.state.closes+=1
class Ports:
    def __init__(self,plan,drift=False,fail_install=False,lost_login=False,fail_cleanup=False):
        self.plan=plan;self.data=copy.deepcopy(plan['permissionBefore']);self.sql=[];self.stage=0;self.closes=0;self.connects=0;self.drift=drift;self.fail_install=fail_install;self.lost_login=lost_login;self.fail_cleanup=fail_cleanup;self.commands=[];self.install_calls=[];self.live=copy.deepcopy(plan['runtimeBefore']);self.login_calls=[];self.associations=[];self.cleanup_calls=0;self.GETs=[]
    def connect(self):self.connects+=1;return FakeSession(self)
    def command(self,argv):
        self.commands.append(argv)
        if argv==('systemctl','stop','nginx.service'):self.live.update(nginxActive=False,externalListeners=[],pendingRequests=0)
        elif argv==('systemctl','stop','print-backend.service'):self.live.update(backendActive=False,backendPid=0,port48080=[],dbConnections=0)
        elif argv==('systemctl','start','print-backend.service'):
            launch=self.plan['runtimeContract']['backendLaunch'];binding=c.digest(c.canon(launch))
            self.live.update(backendActive=True,backendPid=42,backendStartTicks='4200',port48080=['127.0.0.1'],jarSha256=self.plan['release']['jar']['sha256'],frontendManifestSha256=self.plan['release']['frontendManifest']['sha256'],gateConfigSha256=self.plan['release']['gateConfig']['sha256'],effectiveGate=True,gateSourceBindingSha256='a'*64,backendLaunchBindingSha256=binding,backendProcess={'pid':42,'startTicks':'4200','jarPath':launch['after']['jarPath'],'jarSha256':self.plan['release']['jar']['sha256'],'argvSha256':c.digest(c.canon(launch['after']['argv'])),'launchBindingSha256':binding})
        elif argv==('systemctl','start','nginx.service'):self.live.update(nginxActive=True)
        else:raise AssertionError('unexpected command')
        return 'ACK'
    def snapshot(self):return copy.deepcopy(self.live)
    def verify_ingress(self):return copy.deepcopy(self.plan['runtimeContract']['ingress']['expectedProof'])
    def select_backend_release(self,*_):return 'ACK'
    def install(self,ref,target):
        self.install_calls.append((ref,target))
        if self.fail_install and len(self.install_calls)==2:raise OSError('partial_frontend_install')
        return 'ACK'
    def verify(self,*args):
        if len(args)==1:return {'runId':self.plan['runId'],'encryptedArtifact':{'path':self.plan['backupContract']['encryptedPath'],'sha256':'1'*64},'restoreVerified':True,'temporarySchemaRemoved':True}
        return 'ACK'
    def oauth_client(self):return {'clientId':'default','status':0,'accessTtl':1800,'refreshTtl':86400}
    def intent(self,run,alias,user):return alias+'-exact-intent'
    def token(self,alias,kind):return 'PRIVATE_TOKEN_MUST_NOT_LEAK_'+alias+'_'+kind
    def login(self,host,port,path,ref,intent):
        self.login_calls.append((host,port,path,intent));alias=intent.split('-')[0]
        if self.lost_login:raise runtime.LoginResponseLost('lost')
        return {'status':200,'body':{'code':0,'data':{'accessToken':self.token(alias,'access'),'refreshToken':self.token(alias,'refresh'),'userId':self.plan['users'][alias]['userId']}}}
    def associate_once(self,run,alias,intent):
        self.associations.append(alias);return {'runId':run,'intentId':intent,'userId':self.plan['users'][alias]['userId'],'tenantId':1,'clientId':'default','accessId':101 if alias=='admin' else 102,'refreshId':201 if alias=='admin' else 202,'accessFingerprint':runtime.AcceptanceAdapter.fingerprint(self.token(alias,'access')),'refreshFingerprint':runtime.AcceptanceAdapter.fingerprint(self.token(alias,'refresh'))}
    def request(self,host,port,method,path,body,session):self.GETs.append((host,port,method,path,body));return {'status':200,'body':{'code':0,'data':{}}}
    def permission_rows(self):return copy.deepcopy(self.data)
    def role_menu_ids(self,host,port,rid,session):return self.plan['permissionProposal']['roleAfter'][str(rid)]
    def filtered_menu_ids(self,rid,ids):return ids
    def assign_same_role_menus(self,*_):return 'ACK'
    def reviewed_cases(self,*_):return self.plan['acceptanceContract']['expectedProof']
    def scoped_readback(self,*_):return self.plan['acceptanceContract']['expectedSqlReadback']
    def revoke_exact_once(self,run,sessions):
        self.cleanup_calls+=1
        if self.fail_cleanup:raise OSError('cleanup_effect_then_response_lost')
        return 'ACK'
    def session_residuals(self,*_):return {'access':0,'refresh':0,'redisAccess':0,'redisRefresh':0}
    def clear_auth(self,*_):return 'ACK'
def adapter_fixture():
    run='02000000-0000-4000-8000-000000000001';before=baseline();before['packages'][0]['update_time']='2026-10-28 00:00:00';proposal=c.plan_permissions(before)
    blobs={}
    def ref(path,raw=None):
        if raw is None:raw=(ROOT/path).read_bytes()
        full=str(ROOT/path);blobs[full]=raw;return {'path':full,'sha256':c.digest(raw)}
    inputs={'design':[ref(p) for p in (c.DESIGN_PATH,c.PERMISSION_MAP_PATH,c.CONFIRMATION_PATH)],'ddl':[ref(p) for p in c.DDL_FILES],'adapters':[ref(p) for p in ('ops/db/ordinary_activation_common.py','ops/db/ordinary_activation_db.py','ops/db/migrate-ordinary-modules.py','ops/db/ordinary_activation_recovery.py','ops/db/render-ordinary-modules-plan.py','ops/runtime/ordinary_activation_runtime.py','ops/acceptance/ordinary_modules_activation_acceptance.py')],'artifacts':[ref('runtime/fake-inputs/new.jar',b'fake-jar'),ref('runtime/fake-inputs/frontend.json',b'fake-front')],'config':[ref('runtime/fake-inputs/gate.conf',b'fake-true')],'backup':[ref('ops/backup/backup_verify.py')]}
    graphs={stage:{key:[] for key in c.METADATA_KEYS} for stage in c.STAGES}
    for i,stage in enumerate(c.STAGES):
        graphs[stage]['tables']=[[t,'InnoDB','utf8mb4_unicode_ci'] for t in ('system_tenant','system_tenant_package','system_role','system_menu','system_role_menu')+c.NEW_TABLES[:max(0,i-1)]]
        for label,table in dbmod.TABLES.items():
            for ordinal,name in enumerate(before[label][0],1):
                kind='bit(1)' if name=='deleted' else ('text' if name=='menu_ids' else 'varchar(255)')
                graphs[stage]['columns'].append([table,name,ordinal,kind,'YES',None,'',None,None,''])
    menus=[]
    for ident,permission in c.PERMISSIONS.items():
        row={k:None for k in dbmod.MENU_COLUMNS};row.update(id=ident,name=permission,permission=permission,type=3,sort=ident-2000,parent_id=2000,path='',status=0,visible=0,keep_alive=0,always_show=0,deleted=0);menus.append(row)
    grants=[]
    for i,(rid,mid) in enumerate(c.GRANTS):
        row={k:None for k in dbmod.GRANT_COLUMNS};row.update(id=500+i,role_id=rid,menu_id=mid,tenant_id=1,deleted=0);grants.append(row)
    draft={'runId':run,'schema':'print_demo','environment':'CLOUD_DEMO','scope':{'tenantId':1,'packageId':1,'roleIds':[1,2]},'humanConfirmations':{k:'HUMAN_CONFIRMED' for k in c.HUMAN_FIELDS},'designSha256':c.DESIGN_SHA,'advertisingGate':{'property':'print.advertising.enabled','effectiveValue':True},'inputs':inputs,'metadataStages':graphs,'permissionBefore':before,'permissionProposal':proposal,'permissionRows':{'menus':menus,'grants':grants},'sessionContract':c.session_output_contract(run),'dbIdentity':{'schema':'print_demo','serverUuid':'FAKE_SERVER','version':'FAKE_VERSION','currentUser':'root@localhost','sqlMode':'FAKE','socket':'/run/mysqld/mysqld.sock'},'ddlStatements':c.ddl_statements(),'runtimeBefore':{'nginxActive':True,'externalListeners':[443],'pendingRequests':0,'backendActive':True,'backendPid':41,'port48080':['127.0.0.1'],'dbConnections':2,'jarSha256':'old','frontendManifestSha256':'old','gateConfigSha256':'old','effectiveGate':False},'release':{'jar':inputs['artifacts'][0],'frontendManifest':inputs['artifacts'][1],'gateConfig':inputs['config'][0]},'backupContract':{'encryptedSha256':'1'*64,'expectedProof':{'temporarySchemaRemoved':True,'restoreVerified':True,'encryptedSha256':'1'*64}},'users':{alias:{'userId':uid,'tenantId':1,'roleId':rid,'credentialRef':{'path':str(ROOT/'runtime/secrets'/('smoke-'+alias+'.json')),'sha256':'2'*64}} for alias,uid,rid in (('admin',11,1),('reader',12,2))},'acceptanceContract':{'existingLegalAssets':False,'expectedProof':{'scopedCases':'FAKE_PASS'},'expectedSqlReadback':{'scopedRows':'FAKE_PASS'}},'browserContract':{'controlledExistingSessionPort':'FAKE'},'runtimeContract':{'gateSourceBindingSha256':'a'*64}}
    for alias in ('admin','reader'):
        credential=ref('runtime/secrets/smoke-'+alias+'.json',b'FAKE_NOT_A_CREDENTIAL');inputs['config'].append(credential);draft['users'][alias]['credentialRef']=credential
    draft['backupContract']=c.backup_output_contract(run,inputs['backup'][0])
    draft['acceptanceContract']['getSchemas']={path:{'type':'object','required':[],'properties':{},'additional':False} for path in runtime.GET_PATHS}
    draft['runtimeBefore']['backendStartTicks']='4100'
    jar_path=str(ROOT/'runtime/releases'/('ordinary-'+run)/'backend.jar')
    draft['runtimeContract']['backendLaunch']={'before':{'jarPath':'FAKE_OLD_JAR','argv':['java','-jar','FAKE_OLD_JAR']},'after':{'jarPath':jar_path,'argv':['java','-jar',jar_path]}}
    draft['runtimeContract']['ingress']={'expectedProof':{'status':'VERIFIED_INGRESS','scope':'FAKE_FIXED_INGRESS'}}
    candidate=c.freeze_adapter_candidate(draft)
    review={'actualModel':'gpt-6-astra','actualEffort':'high','conclusion':'PASS_SOURCE_ONLY','bindings':c.adapter_review_bindings(candidate)}
    review_raw=c.canon(review);envelope=c.bind_adapter_review(candidate,review_raw,{'path':str(ROOT/'docs/reviews/fake-only.json'),'sha256':c.digest(review_raw)})
    blobs[envelope['reviewRef']['path']]=review_raw
    return envelope,blobs
c.METADATA_KEYS_ORDER=('tables','columns','indexes','constraints','foreignKeys','checks','triggers')
class AdapterTests(unittest.TestCase):
    def setup_chain(self,**kwargs):
        envelope,blobs=adapter_fixture();plan=envelope['candidate'];ports=Ports(plan,**kwargs);journal=MemoryJournal();db=dbmod.DatabaseAdapter(plan,ports.connect);rt=rtmod.RuntimeAdapter(plan,ports,ports);ac=runtime.AcceptanceAdapter(plan,ports,ports,ports,ports)
        return envelope,blobs,ports,journal,db,rt,ac
    def execute(self,chain):
        envelope,blobs,ports,journal,db,rt,ac=chain
        return ex.run_source_adapters(envelope,db,rt,ac,lambda:journal,lock_factory=Lease,input_reader=lambda ref:blobs[ref['path']],review_reader=lambda ref:blobs[ref['path']])
    def test_08_full_fake_chain_parameterized_SQL_receipts_and_default_no_live(self):
        chain=self.setup_chain();envelope,blobs,ports,journal,db,rt,ac=chain
        pending=copy.deepcopy(envelope['candidate']);pending['humanConfirmations']['permissionMatrix']='PENDING_HUMAN'
        with self.assertRaises(ValueError):c.freeze_adapter_candidate(pending)
        with mock.patch.object(c.os,'open',side_effect=AssertionError('constructor IO forbidden')):
            dbmod.DatabaseAdapter(envelope['candidate']);rtmod.RuntimeAdapter(envelope['candidate']);runtime.AcceptanceAdapter(envelope['candidate'])
            self.assertIsNone(c.MysqlSession().process)
            with mock.patch.object(sys,'argv',['executor','--apply']),self.assertRaisesRegex(ValueError,'SOURCE_ONLY_RUNTIME_HTTP_ADAPTERS_NOT_IMPLEMENTED'):ex.main()
        result=self.execute(chain);self.assertEqual(result['status'],'SOURCE_PORT_CHAIN_COMPLETE_NOT_LIVE_ACCEPTANCE')
        self.assertEqual(ports.connects,2);self.assertEqual([sql for sql,args in ports.sql if sql.startswith(('CREATE TABLE','ALTER TABLE'))],c.ddl_statements())
        inserts=[(sql,args) for sql,args in ports.sql if sql.startswith('INSERT INTO')];self.assertEqual(len(inserts),41);self.assertTrue(all('%s' in sql and len(args)==sql.count('%s') for sql,args in inserts))
        self.assertEqual([sql for sql,args in ports.sql if sql.startswith('SELECT JSON_OBJECT') and sql.endswith('FOR UPDATE')].__len__(),10)
        self.assertEqual(sum(sql=='COMMIT' for sql,args in ports.sql),1);self.assertFalse(any(sql=='ROLLBACK' for sql,args in ports.sql))
        receipts=result['receipts'];commit=[r['status'] for r in receipts if r['stage']=='PERMISSION_COMMIT'];self.assertEqual(commit,['INTENT','OBSERVED'])
        phases=journal.events;self.assertLess(phases.index('CLOSE_INGRESS_OBSERVED'),phases.index('STOP_BACKEND_INTENT'));self.assertLess(phases.index('PERMISSION_COMMIT_OBSERVED'),phases.index('INSTALL_JAR_INTENT'));self.assertLess(phases.index('SESSION_CLEANUP_OBSERVED'),phases.index('REOPEN_INGRESS_INTENT'))
        self.assertEqual(len(ports.GETs),38);self.assertTrue(all(row[:3]==('127.0.0.1',48080,'GET') for row in ports.GETs));self.assertNotIn('PRIVATE_TOKEN_MUST_NOT_LEAK',json.dumps(receipts)+json.dumps(journal.rows));self.assertEqual(ac.tokens,{})
    def test_09_permission_drift_ROLLBACK_zero_COMMIT(self):
        chain=self.setup_chain(drift=True)
        with self.assertRaises(ValueError):self.execute(chain)
        ports=chain[2];self.assertEqual(sum(sql=='ROLLBACK' for sql,args in ports.sql),1);self.assertFalse(any(sql=='COMMIT' or sql.startswith('INSERT') for sql,args in ports.sql));self.assertEqual(ports.install_calls,[])
    def test_10_partial_release_failure_closed_no_restart_or_DB_rollback(self):
        chain=self.setup_chain(fail_install=True)
        with self.assertRaises(OSError):self.execute(chain)
        ports=chain[2];self.assertEqual(len(ports.install_calls),2);self.assertEqual(sum(sql=='COMMIT' for sql,args in ports.sql),1);self.assertFalse(any(sql=='ROLLBACK' for sql,args in ports.sql));self.assertFalse(any(argv[1]=='start' for argv in ports.commands));self.assertFalse(ports.live['nginxActive'])
        envelope=chain[0];receipts=chain[5].receipts.rows
        report=recovery.recovery_report(envelope,[],receipts);self.assertIn('INSTALL_FRONTEND',report['unknownPoints']);self.assertFalse(report['actionsExecuted'])
    def test_11_lost_login_exact_association_cleanup_unknown_once_no_token_leak(self):
        chain=self.setup_chain(lost_login=True,fail_cleanup=True)
        with self.assertRaises(runtime.LoginResponseLost):self.execute(chain)
        ports=chain[2];ac=chain[6];self.assertEqual(len(ports.login_calls),1);self.assertEqual(ports.associations,['admin']);self.assertEqual(ports.cleanup_calls,1);self.assertTrue(ac.cleanup_started);self.assertEqual(ac.tokens,{})
        evidence=json.dumps(chain[3].rows)+json.dumps(ac.receipts.rows);self.assertNotIn('PRIVATE_TOKEN_MUST_NOT_LEAK',evidence);self.assertIn('SESSION_CLEANUP_UNKNOWN',evidence);self.assertFalse(ports.live['nginxActive']);self.assertNotIn(('systemctl','start','nginx.service'),ports.commands)

    def test_12_mixed_adapter_plan_or_review_tamper_rejected_before_all_ports(self):
        for index in range(3):
            with self.subTest(mixedAdapter=index):
                chain=self.setup_chain();envelope,blobs,ports,journal,db,rt,ac=chain
                (db,rt,ac)[index].plan['release']['jar']['sha256']='f'*64
                calls={'input':0,'review':0,'journal':0,'lock':0}
                def read_input(ref):calls['input']+=1;return blobs[ref['path']]
                def read_review(ref):calls['review']+=1;return blobs[ref['path']]
                def journal_factory():calls['journal']+=1;return journal
                def lock_factory():calls['lock']+=1;return Lease()
                with self.assertRaisesRegex(ValueError,'adapter_full_plan_mismatch'):
                    ex.run_source_adapters(envelope,db,rt,ac,journal_factory,lock_factory=lock_factory,input_reader=read_input,review_reader=read_review)
                self.assertEqual(calls,{'input':0,'review':0,'journal':0,'lock':0})
                self.assertEqual((ports.connects,ports.commands,ports.install_calls,ports.login_calls,journal.events),(0,[],[],[],[]))
        for tamper in ('raw_bytes','execution_verdict','wrong_model','cached_adapter_hash','path_outside_reviews'):
            with self.subTest(reviewTamper=tamper):
                chain=self.setup_chain();envelope,blobs,ports,journal,db,rt,ac=chain;envelope=copy.deepcopy(envelope);ref=envelope['reviewRef'];raw=blobs[ref['path']]
                if tamper=='raw_bytes':raw+=b' '
                elif tamper in ('execution_verdict','wrong_model'):
                    review=json.loads(raw)
                    if tamper=='execution_verdict':review['conclusion']='PASS_MIGRATION_EXECUTION'
                    else:review['actualModel']='unreviewed-model'
                    raw=c.canon(review);ref['sha256']=c.digest(raw);envelope['reviewSha256']=ref['sha256'];envelope['review']=review
                elif tamper=='cached_adapter_hash':envelope['review']['bindings']['adapterSha256'][next(iter(envelope['review']['bindings']['adapterSha256']))]='f'*64
                elif tamper=='path_outside_reviews':ref['path']=str(ROOT/'runtime/not-review.json')
                calls={'input':0,'review':0,'journal':0,'lock':0}
                def read_input(ref):calls['input']+=1;return blobs[ref['path']]
                def read_review(ref):calls['review']+=1;return raw
                def journal_factory():calls['journal']+=1;return journal
                def lock_factory():calls['lock']+=1;return Lease()
                with self.assertRaises(ValueError):
                    ex.run_source_adapters(envelope,db,rt,ac,journal_factory,lock_factory=lock_factory,input_reader=read_input,review_reader=read_review)
                self.assertEqual((calls['input'],calls['journal'],calls['lock']),(0,0,0));self.assertEqual(calls['review'],0 if tamper=='path_outside_reviews' else 1)
                self.assertEqual((ports.connects,ports.sql,ports.commands,ports.install_calls,ports.login_calls,journal.events),(0,[],[],[],[],[]))
    def test_13_journal_only_commit_intent_remains_unknown_and_bad_bindings_reject(self):
        envelope,blobs=adapter_fixture();run=envelope['candidate']['runId'];plan_sha=envelope['candidateSha256']
        intent={'runId':run,'planSha256':plan_sha,'sequence':1,'phase':'PERMISSION_COMMIT_INTENT'}
        report=recovery.recovery_report(envelope,[intent],[])
        self.assertEqual(report['unknownPoints'],['PERMISSION_COMMIT']);self.assertEqual(report['lastIntent'],'PERMISSION_COMMIT');self.assertFalse(report['actionsExecuted'])
        observed_receipt={'runId':run,'planSha256':plan_sha,'reviewSha256':envelope['reviewSha256'],'inputsSha256':c.digest(c.canon(envelope['candidate']['inputs'])),'sequence':1,'stage':'PERMISSION_COMMIT','status':'OBSERVED'}
        self.assertIn('PERMISSION_COMMIT',recovery.recovery_report(envelope,[intent],[observed_receipt])['unknownPoints'])
        observed_journal=dict(intent,sequence=2,phase='PERMISSION_COMMIT_OBSERVED')
        self.assertEqual(recovery.recovery_report(envelope,[intent,observed_journal],[])['unknownPoints'],[])
        malformed=[{k:v for k,v in intent.items() if k!='runId'},dict(intent,runId='wrong'),dict(intent,planSha256='f'*64),dict(intent,status='OBSERVED'),dict(intent,sequence=2)]
        for row in malformed:
            with self.subTest(invalidJournal=row),self.assertRaises(ValueError):recovery.recovery_report(envelope,[row],[])
        for raw in (c.canon(intent),b'{truncated\n'):
            with self.subTest(truncatedJournal=raw),self.assertRaises(ValueError):recovery.recovery_report(envelope,raw,[])
        self.assertEqual(recovery.recovery_report(envelope,c.canon(intent)+b'\n',[])['unknownPoints'],['PERMISSION_COMMIT'])


class UnifiedSqlTests(unittest.TestCase):
    def test_14_fixed_template_encoding_and_lazy_connector(self):
        value="quote'\\n; DROP TABLE system_menu; -- 中文"
        sql='UPDATE system_tenant_package SET menu_ids=%s,update_time=%s WHERE id=%s AND deleted=%s'
        result=c.MysqlSession.fixed_sql(sql,(value,None,1,False))
        self.assertIn("CONVERT(X'"+value.encode().hex()+"' USING utf8mb4)",result)
        self.assertNotIn('DROP TABLE',result);self.assertTrue(result.endswith('id=1 AND deleted=0'))
        for query,args in ((sql,(1,)),('SELECT JSON_OBJECT(1,SLEEP(1))',()),('UPDATE system_menu SET status=%s',(1,)),(sql,(object(),None,1,0))):
            with self.subTest(query=query),self.assertRaises(ValueError):c.MysqlSession.fixed_sql(query,args)
        with mock.patch.object(c.subprocess,'Popen',side_effect=AssertionError('unexpected database connection')):
            db=c.MysqlSession();reader=c.ReadOnlyMysql();self.assertIsNone(db.process);self.assertIsNone(reader.process)
            with self.assertRaises(ValueError):reader.execute('COMMIT')
            with self.assertRaises(ValueError):reader.commit()
            db.close();reader.close()
        with mock.patch.object(sys,'argv',['renderer','--render-draft']),self.assertRaisesRegex(ValueError,'SOURCE_ONLY_V3_ISOLATED_ACTION_REVIEW_REQUIRED'):renderer.main()

if __name__=='__main__': unittest.main(verbosity=2)
