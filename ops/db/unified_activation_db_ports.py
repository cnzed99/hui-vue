"""Concrete/injectable DB-side ports for unified v3. No executable entry point.

Imports/constructors do no IO. Every effect requires an explicit held lease and
explicit ports. Final review and CLI wiring remain separate; execution is disabled.
"""
import collections,copy,hashlib,json,os,pathlib,re,secrets,selectors,shutil,stat,subprocess,time,uuid
import ordinary_activation_common as c

SOURCE_PATH='ops/db/unified_activation_db_ports.py'
NATIVE_EXECUTION_ENABLED=False
COUNT_SQL="SELECT JSON_OBJECT('connectionCount',COUNT(*)) FROM information_schema.PROCESSLIST WHERE DB='print_demo' AND ID<>CONNECTION_ID()"
DUMP_ARGV=('sudo','-n','/usr/bin/mysqldump','--single-transaction','--skip-lock-tables','--no-tablespaces','--set-gtid-purged=OFF','--hex-blob','--skip-extended-insert','--routines','--events','--triggers','--default-character-set=utf8mb4','--protocol=SOCKET','--socket='+c.SOCKET,'print_demo')
MAX_SNAPSHOT=128*1024**2
MAGIC=b'PRINTDB1\x00'
AAD=b'print-system:print_demo:mysql-snapshot:v1'

def run_schema(run_id):
    c.require(str(uuid.UUID(run_id))==run_id,'canonical_run_uuid_required')
    return 'print_unified_restore_'+uuid.UUID(run_id).hex

def backup_execution_contract(plan):
    value=plan['backupExecution']
    c.require(set(value)=={'sourceSchema','restoreSchema','keyRef','maximumSnapshotBytes','minimumFreeBytes','expectedTables','expectedInvariants'},'exact_backup_execution_contract_required')
    c.require(value['sourceSchema']=='print_demo' and value['restoreSchema']==run_schema(plan['runId']) and value['maximumSnapshotBytes']==MAX_SNAPSHOT and value['minimumFreeBytes']==10*1024**3,'backup_scope_or_limits_changed')
    c.validate_ref(value['keyRef'])
    c.require(value['keyRef']['path']==str(c.BASE/'runtime/secrets/database-backup-aes256.key') and value['keyRef'] in plan['inputs']['config'],'backup_key_ref_not_frozen')
    tables=value['expectedTables']
    c.require(isinstance(tables,list) and tables==sorted(set(tables)) and tables and all(re.fullmatch('[A-Za-z_][A-Za-z0-9_]{0,63}',name) for name in tables),'backup_table_set_not_exact')
    c.require(set(value['expectedInvariants'])=={'active_tenants','tenant_admin_roles','super_admin_roles','tenant_admin_assignment'} and all(type(n) is int and n>=0 for n in value['expectedInvariants'].values()),'backup_invariants_must_be_frozen_counts')
    c.require(plan['backupContract']==c.backup_output_contract(plan['runId'],plan['backupContract']['adapterRef']),'backup_output_contract_changed')
    c.require(plan['backupContract']['adapterRef']['path']==str(c.BASE/SOURCE_PATH),'new_run_specific_backup_producer_required')
    return value

def dump_manifest(snapshot,expected_tables):
    c.require(isinstance(snapshot,bytes) and 0<len(snapshot)<=MAX_SNAPSHOT,'snapshot_size_invalid')
    text=snapshot.decode('utf-8')
    c.require(not re.search(r'^\s*(?:USE\s|(?:CREATE|DROP)\s+DATABASE\b)',text,re.I|re.M),'dump_database_switch_forbidden')
    # Stored programs and cross-schema references need a separate restore design.
    executable='\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('--'))
    c.require(not re.search(r'\b(?:PROCEDURE|FUNCTION|TRIGGER|EVENT)\b',executable,re.I) and not re.search(r'`print_demo`\s*\.',executable),'unsupported_dump_program_or_cross_schema_reference')
    tables=re.findall(r'^CREATE TABLE `([A-Za-z0-9_]+)`',text,re.M)
    engines=re.findall(r'^\) ENGINE=([A-Za-z0-9_]+)',text,re.M)
    c.require(sorted(tables)==expected_tables and len(tables)==len(set(tables)) and len(engines)==len(tables) and all(engine.lower()=='innodb' for engine in engines),'snapshot_tables_or_engines_not_frozen')
    counts=collections.Counter(re.findall(r'^INSERT INTO `([A-Za-z0-9_]+)` VALUES ',text,re.M))
    c.require(set(counts)<=set(tables),'snapshot_insert_without_table')
    return {'snapshotSha256':c.digest(snapshot),'tableRows':{name:counts.get(name,0) for name in expected_tables}}

class MysqlConnectionCount:
    def __init__(self,connect=None):self.connect=connect;self.lease=None
    def bind(self,lease):self.lease=lease
    def __call__(self):
        c.require(self.lease is not None and self.connect is not None,'connection_count_port_not_bound')
        self.lease.check();db=self.connect()
        try:
            c.identity(db)
            rows=db.objects(COUNT_SQL)
            c.require(len(rows)==1 and set(rows[0])=={'connectionCount'} and type(rows[0]['connectionCount']) is int and rows[0]['connectionCount']>=0,'connection_count_not_exact')
            return rows[0]['connectionCount']
        finally:db.close()

class CheckedGateEvidence:
    """Validate a runtime collector's evidence. No SQL or file-based inference."""
    def __init__(self,plan,observe=None):self.plan=copy.deepcopy(plan);self.observe=observe;self.lease=None
    def bind(self,lease):self.lease=lease
    def __call__(self):
        c.require(self.lease is not None and self.observe is not None,'gate_collector_not_bound')
        self.lease.check();value=self.observe()
        c.require(isinstance(value,dict) and set(value)=={'effectiveValue','configSha256','sourceBindingSha256'},'gate_evidence_schema')
        c.require(value['effectiveValue'] is None or type(value['effectiveValue']) is bool,'gate_value_type')
        c.require(value['configSha256']==self.plan['release']['gateConfig']['sha256'] and value['sourceBindingSha256']==self.plan['runtimeContract']['gateSourceBindingSha256'],'gate_source_binding_changed')
        return copy.deepcopy(value)

class NativeBackupFiles:
    def __init__(self,plan):self.plan=copy.deepcopy(plan);self.lease=None
    def bind(self,lease):self.lease=lease
    def guard(self):c.require(self.lease is not None,'backup_files_not_bound');self.lease.check();c.require(NATIVE_EXECUTION_ENABLED,'SOURCE_ONLY_NATIVE_BACKUP_FILES_DISABLED')
    def available(self):self.guard();return shutil.disk_usage(c.BASE).free
    def read(self,path,limit=MAX_SNAPSHOT+1024):
        self.guard();p=pathlib.Path(path)
        allowed={self.plan['backupExecution']['keyRef']['path'],self.plan['backupContract']['encryptedPath'],self.plan['backupContract']['restoreReceiptPath']}
        c.require(str(p) in allowed and all(not row.is_symlink() for row in (p,*p.parents)),'backup_read_path_not_exact')
        fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as stream:
            info=os.fstat(fd);c.require(stat.S_ISREG(info.st_mode) and info.st_nlink==1 and info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode)==0o600 and info.st_size<=limit,'backup_private_file_required');return stream.read()
    def prepare(self):
        self.guard();contract=self.plan['backupContract'];directory=pathlib.Path(contract['outputDirectory']);receipt=pathlib.Path(contract['restoreReceiptPath'])
        c.require(directory==c.BASE/'runtime/backups'/('ordinary-'+self.plan['runId']) and receipt==c.BASE/'runtime/private/ordinary-activation'/self.plan['runId']/'restore-proof.json','backup_output_paths')
        c.require(all(not row.is_symlink() for row in (*directory.parents,*receipt.parents)) and directory.parent.is_dir() and receipt.parent.is_dir(),'backup_parent_contract')
        c.require(receipt.parent.stat().st_uid==os.getuid() and stat.S_IMODE(receipt.parent.stat().st_mode)==0o700,'private_restore_receipt_parent')
        c.require(not directory.exists() and not receipt.exists(),'backup_run_output_exists')
        os.mkdir(directory,0o700);c.fsync_dir(directory.parent)
    def write(self,path,raw):
        self.guard();c.require(path in (self.plan['backupContract']['encryptedPath'],self.plan['backupContract']['restoreReceiptPath']),'backup_write_path_not_exact');c.exclusive(pathlib.Path(path),raw)

class AesSnapshotCipher:
    def encrypt(self,key,snapshot):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        c.require(isinstance(key,bytes) and len(key)==32,'backup_key_length')
        nonce=secrets.token_bytes(12);return MAGIC+nonce+AESGCM(key).encrypt(nonce,snapshot,AAD)
    def decrypt(self,key,encrypted):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        c.require(encrypted.startswith(MAGIC) and len(encrypted)>len(MAGIC)+28,'backup_ciphertext_frame')
        return AESGCM(key).decrypt(encrypted[len(MAGIC):len(MAGIC)+12],encrypted[len(MAGIC)+12:],AAD)

class FixedBackupMysql:
    """Finite native socket commands. Callbacks make every effect test-injectable."""
    def __init__(self,plan,run=None,capture=None):
        self.plan=copy.deepcopy(plan);self.run=run;self.capture=capture;self.lease=None;self.snapshot_sha=None
    def bind(self,lease):self.lease=lease
    def guard(self):c.require(self.lease is not None,'backup_mysql_not_bound');self.lease.check()
    def invoke(self,database,sql):
        self.guard();schema=run_schema(self.plan['runId']);c.require(database in ('information_schema','print_demo',schema),'backup_database_outside_scope')
        identity_sql="SELECT JSON_OBJECT('schema',DATABASE(),'serverUuid',@@server_uuid,'version',@@version,'currentUser',CURRENT_USER(),'sqlMode',@@sql_mode,'socket',@@socket)"
        read={identity_sql,"SELECT JSON_ARRAY(TABLE_NAME,ENGINE) FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+database+"' ORDER BY TABLE_NAME"}
        read.update(query.replace("'print_demo'","'"+database+"'") for query in c.metadata_queries().values())
        catalog={"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"'",'CREATE DATABASE '+c.identifier(schema)+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci','DROP DATABASE '+c.identifier(schema)}
        restored={'SELECT COUNT(*) FROM '+c.identifier(name) for name in backup_execution_contract(self.plan)['expectedTables']}
        restored.update(('SELECT COUNT(*) FROM system_tenant WHERE deleted=0',"SELECT COUNT(*) FROM system_role WHERE deleted=0 AND code='tenant_admin'","SELECT COUNT(*) FROM system_role WHERE deleted=0 AND code='super_admin'","SELECT COUNT(*) FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id JOIN system_users u ON u.id=ur.user_id WHERE ur.deleted=0 AND r.deleted=0 AND u.deleted=0 AND r.code='tenant_admin' AND ur.tenant_id=u.tenant_id AND r.tenant_id=u.tenant_id"))
        c.require((database=='information_schema' and sql in catalog) or (database in ('print_demo',schema) and sql in read) or (database==schema and sql in restored),'backup_sql_not_fixed_allowlist')
        argv=('sudo','-n','/usr/bin/mysql','--batch','--raw','--skip-column-names','--skip-reconnect','--connect-timeout=5','--default-character-set=utf8mb4','--protocol=SOCKET','--socket='+c.SOCKET,'--database='+database)
        if self.run is not None:raw=self.run(argv,sql.encode())
        else:
            c.require(NATIVE_EXECUTION_ENABLED,'SOURCE_ONLY_NATIVE_BACKUP_MYSQL_DISABLED')
            result=subprocess.run(argv,input=sql.encode(),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=120);c.require(result.returncode==0,'backup_mysql_failed_or_unknown');raw=result.stdout
        c.require(isinstance(raw,bytes) and len(raw)<=4*1024**2,'backup_mysql_response_bound');return raw.decode('utf-8').splitlines()
    def identity(self,schema):
        c.require(schema in ('print_demo',run_schema(self.plan['runId'])),'backup_identity_schema')
        sql="SELECT JSON_OBJECT('schema',DATABASE(),'serverUuid',@@server_uuid,'version',@@version,'currentUser',CURRENT_USER(),'sqlMode',@@sql_mode,'socket',@@socket)"
        rows=self.invoke(schema,sql);c.require(len(rows)==1,'backup_identity_response');return c.strict_json(rows[0])
    def metadata(self,schema):
        self.guard();c.require(schema in ('print_demo',run_schema(self.plan['runId'])),'backup_metadata_schema')
        graph={}
        for key,sql in c.metadata_queries().items():
            rows=[c.strict_json(row) for row in self.invoke(schema,sql.replace("'print_demo'","'"+schema+"'"))]
            if key=='foreignKeys':
                for row in rows:c.require(row[4]==schema,'restore_foreign_schema_escape');row[4]='print_demo'
            graph[key]=rows
        return graph
    def table_names(self,schema):
        c.require(schema in ('print_demo',run_schema(self.plan['runId'])),'backup_tables_schema')
        rows=self.invoke(schema,"SELECT JSON_ARRAY(TABLE_NAME,ENGINE) FROM information_schema.TABLES WHERE TABLE_SCHEMA='"+schema+"' ORDER BY TABLE_NAME")
        tables=[c.strict_json(row) for row in rows];c.require(all(len(row)==2 and row[1]=='InnoDB' for row in tables),'unsupported_backup_engine');return [row[0] for row in tables]
    def dump(self):
        self.guard()
        if self.capture is not None:raw=self.capture(DUMP_ARGV,MAX_SNAPSHOT)
        else:
            c.require(NATIVE_EXECUTION_ENABLED,'SOURCE_ONLY_NATIVE_BACKUP_DUMP_DISABLED')
            process=subprocess.Popen(DUMP_ARGV,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0);chunks=[];size=0;deadline=time.monotonic()+120
            selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
            try:
                while True:
                    remaining=deadline-time.monotonic();c.require(remaining>0 and selector.select(remaining),'backup_dump_timeout')
                    chunk=os.read(process.stdout.fileno(),1024**2)
                    if not chunk:break
                    size+=len(chunk);c.require(size<=MAX_SNAPSHOT,'snapshot_size_invalid');chunks.append(chunk)
                c.require(process.wait(timeout=max(1,deadline-time.monotonic()))==0,'backup_dump_failed');raw=b''.join(chunks)
            finally:
                selector.close();process.stdout.close()
                if process.poll() is None:process.kill();process.wait(timeout=5)
        c.require(isinstance(raw,bytes) and 0<len(raw)<=MAX_SNAPSHOT,'snapshot_size_invalid');self.snapshot_sha=c.digest(raw);return raw
    def present(self):
        schema=run_schema(self.plan['runId']);rows=self.invoke('information_schema',"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"'");c.require(rows in (['0'],['1']),'restore_schema_presence_unknown');return rows==['1']
    def create(self):
        schema=run_schema(self.plan['runId']);c.require(not self.present(),'restore_schema_collision');self.invoke('information_schema','CREATE DATABASE '+c.identifier(schema)+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
    def restore(self,snapshot):
        self.guard();c.require(self.snapshot_sha==c.digest(snapshot),'restore_not_same_captured_snapshot');dump_manifest(snapshot,backup_execution_contract(self.plan)['expectedTables'])
        schema=run_schema(self.plan['runId']);argv=('sudo','-n','/usr/bin/mysql','--default-character-set=utf8mb4','--protocol=SOCKET','--socket='+c.SOCKET,'--database='+schema)
        if self.run is not None:raw=self.run(argv,snapshot)
        else:
            c.require(NATIVE_EXECUTION_ENABLED,'SOURCE_ONLY_NATIVE_BACKUP_RESTORE_DISABLED')
            result=subprocess.run(argv,input=snapshot,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=120);c.require(result.returncode==0,'backup_restore_failed_or_unknown');raw=result.stdout
        c.require(raw==b'','restore_unexpected_output')
    def counts(self):
        schema=run_schema(self.plan['runId']);result={}
        for name in backup_execution_contract(self.plan)['expectedTables']:
            rows=self.invoke(schema,'SELECT COUNT(*) FROM '+c.identifier(name));c.require(len(rows)==1 and re.fullmatch('[0-9]+',rows[0]),'restore_count_unknown');result[name]=int(rows[0])
        return result
    def invariants(self):
        schema=run_schema(self.plan['runId'])
        queries={
         'active_tenants':'SELECT COUNT(*) FROM system_tenant WHERE deleted=0',
         'tenant_admin_roles':"SELECT COUNT(*) FROM system_role WHERE deleted=0 AND code='tenant_admin'",
         'super_admin_roles':"SELECT COUNT(*) FROM system_role WHERE deleted=0 AND code='super_admin'",
         'tenant_admin_assignment':"SELECT COUNT(*) FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id JOIN system_users u ON u.id=ur.user_id WHERE ur.deleted=0 AND r.deleted=0 AND u.deleted=0 AND r.code='tenant_admin' AND ur.tenant_id=u.tenant_id AND r.tenant_id=u.tenant_id"}
        result={}
        for name,sql in queries.items():
            rows=self.invoke(schema,sql);c.require(len(rows)==1 and re.fullmatch('[0-9]+',rows[0]),'restore_invariant_unknown');result[name]=int(rows[0])
        return result
    def drop(self):self.invoke('information_schema','DROP DATABASE '+c.identifier(run_schema(self.plan['runId'])))

class SnapshotBackupProducer:
    def __init__(self,plan,mysql=None,files=None,cipher=None,writers_closed=None):
        self.plan=copy.deepcopy(plan);self.mysql=mysql;self.files=files;self.cipher=cipher;self.writers_closed=writers_closed;self.lease=None;self.journal=None;self.started=False
    def bind(self,lease,journal=None):
        self.lease=lease;self.journal=journal
        for port in (self.mysql,self.files):
            if port is not None and hasattr(port,'bind'):port.bind(lease)
    def event(self,stage,status,**safe):
        c.require(self.journal is not None,'backup_durable_journal_required');self.lease.check();self.journal.append(stage+'_'+status,**safe)
    def __call__(self,contract):
        c.require(self.lease is not None and not self.started and all(port is not None for port in (self.mysql,self.files,self.cipher,self.writers_closed)),'backup_producer_not_bound_or_replay');self.lease.check()
        c.require(contract==self.plan['backupContract'],'backup_contract_changed');execution=backup_execution_contract(self.plan)
        c.require(self.writers_closed() is True and self.files.available()>=execution['minimumFreeBytes'],'backup_writers_or_disk_not_proven')
        self.started=True;self.event('BACKUP_PRODUCER','INTENT')
        c.require(self.mysql.identity('print_demo')==self.plan['dbIdentity'] and self.mysql.metadata('print_demo')==self.plan['metadataStages']['G0'],'backup_source_identity_or_graph_drift')
        c.require(self.mysql.table_names('print_demo')==execution['expectedTables'],'backup_source_table_set_drift')
        key=self.files.read(execution['keyRef']['path'],32);c.require(len(key)==32 and c.digest(key)==execution['keyRef']['sha256'],'backup_key_drift')
        snapshot=self.mysql.dump();manifest=dump_manifest(snapshot,execution['expectedTables'])
        encrypted=self.cipher.encrypt(key,snapshot);self.files.prepare();self.files.write(contract['encryptedPath'],encrypted)
        saved=self.files.read(contract['encryptedPath']);c.require(saved==encrypted and self.cipher.decrypt(key,saved)==snapshot,'backup_cipher_roundtrip_failed')
        corrupt=bytearray(saved);corrupt[-1]^=1
        try:self.cipher.decrypt(key,bytes(corrupt))
        except Exception:pass
        else:raise ValueError('backup_authenticated_tamper_rejection_failed')
        created=False;verified=False;removed=False
        try:
            self.event('RESTORE_SCHEMA_CREATE','INTENT');self.mysql.create();created=True;self.event('RESTORE_SCHEMA_CREATE','OBSERVED')
            self.event('RESTORE_SNAPSHOT','INTENT');self.mysql.restore(snapshot)
            schema=execution['restoreSchema'];ident=self.mysql.identity(schema)
            c.require(ident.get('schema')==schema and {k:v for k,v in ident.items() if k!='schema'}=={k:v for k,v in self.plan['dbIdentity'].items() if k!='schema'},'restore_identity_drift')
            c.require(self.mysql.table_names(schema)==execution['expectedTables'] and self.mysql.metadata(schema)==self.plan['metadataStages']['G0'],'restore_graph_or_tables_mismatch')
            c.require(self.mysql.counts()==manifest['tableRows'] and self.mysql.invariants()==execution['expectedInvariants'],'restore_rows_or_invariants_mismatch')
            verified=True;self.event('RESTORE_SNAPSHOT','OBSERVED',snapshotSha256=manifest['snapshotSha256'])
        finally:
            if created:
                self.event('RESTORE_SCHEMA_DROP','INTENT')
                try:self.mysql.drop();removed=not self.mysql.present();c.require(removed,'restore_schema_not_removed');self.event('RESTORE_SCHEMA_DROP','OBSERVED')
                except BaseException:self.event('RESTORE_SCHEMA_DROP','UNKNOWN');raise
        c.require(verified and removed,'backup_restore_not_proven')
        proof={'runId':self.plan['runId'],'encryptedArtifact':{'path':contract['encryptedPath'],'sha256':c.digest(saved)},'restoreVerified':True,'temporarySchemaRemoved':True,'snapshotSha256':manifest['snapshotSha256'],'tableRowsSha256':c.digest(c.canon(manifest['tableRows'])),'metadataSha256':c.digest(c.canon(self.plan['metadataStages']['G0'])),'invariantsSha256':c.digest(c.canon(execution['expectedInvariants']))}
        self.files.write(contract['restoreReceiptPath'],c.canon(proof)+b'\n');c.require(c.strict_json(self.files.read(contract['restoreReceiptPath']))==proof,'restore_receipt_roundtrip_failed')
        self.event('BACKUP_PRODUCER','OBSERVED',encryptedArtifact=proof['encryptedArtifact'],proofSha256=c.digest(c.canon(proof)));return proof

def final_source_candidate(draft):
    """Validate prospective live wiring; returned plan never authorizes execution."""
    c.require(draft.get('executionAuthorization') is False,'source_plan_must_not_authorize_execution')
    value=c.freeze_adapter_candidate(draft);backup_execution_contract(value)
    c.require(value['backupContract']['adapterRef'] in value['inputs']['backup'],'backup_producer_ref_not_bound')
    source_paths={ref['path'] for refs in value['inputs'].values() for ref in refs}
    c.require(str(c.BASE/SOURCE_PATH) in source_paths,'live_db_port_source_not_frozen')
    proof=value['metadataProvenance']
    c.require(proof['kind']=='AUTHORITATIVE_ISOLATED_RENDER' and proof['temporarySchemaRemoved'] is True and proof['sourceSchemaConnected'] is False and proof['baselineExactMatch'] is True and proof['stageSha256']=={name:c.digest(c.canon(graph)) for name,graph in value['metadataStages'].items()},'final_plan_render_provenance_incomplete')
    c.require(value['release']['frontendRoot']==str(c.BASE/'src/frontend/dist'),'final_plan_frontend_root_not_fixed')
    gate=value['runtimeContract']['gateSourceBindingSha256'];c.require(re.fullmatch('[0-9a-f]{64}',gate) is not None,'final_gate_binding_required')
    runtime=value['runtimeContract'];c.require({'backendLaunch','listenerAllowlist','ingress'}<=set(runtime),'final_runtime_contract_incomplete')
    launch=runtime['backendLaunch'];c.require({'fragmentPath','startScript','unitCatSha256','dropInPaths','files','before','after'}<=set(launch),'final_backend_launch_contract_incomplete')
    c.require(launch['fragmentPath']=='/etc/systemd/system/print-backend.service' and launch['startScript']==str(c.BASE/'ops/runtime/start-backend.sh') and re.fullmatch('[0-9a-f]{64}',launch['unitCatSha256']) is not None,'final_backend_launch_paths_or_hash')
    c.require(all(isinstance(path,str) and pathlib.PurePosixPath(path).parent==pathlib.PurePosixPath('/etc/systemd/system/print-backend.service.d') for path in launch['dropInPaths']),'final_backend_dropin_paths')
    expected={launch['fragmentPath'],launch['startScript'],*launch['dropInPaths']}
    c.require(len(launch['files'])==len(expected) and {ref['path'] for ref in launch['files']}==expected and all(set(ref)=={'path','sha256'} and re.fullmatch('[0-9a-f]{64}',ref['sha256']) is not None for ref in launch['files']),'final_backend_files_not_complete')
    for phase in ('before','after'):
        row=launch[phase];path=pathlib.PurePosixPath(row['jarPath'])
        c.require(path.is_relative_to(pathlib.PurePosixPath(str(c.BASE/'runtime/releases'))) and path.name=='backend.jar' and '..' not in path.parts and isinstance(row['argv'],list) and row['argv'].count('-jar')==1 and row['argv'][row['argv'].index('-jar')+1]==row['jarPath'] and re.fullmatch('[0-9a-f]{64}',row['jarSha256']) is not None,'final_backend_process_binding')
    c.require(launch['after']['jarSha256']==value['release']['jar']['sha256'],'final_backend_release_hash_mismatch')
    ingress=runtime['ingress'];c.require(ingress['host']=='127.0.0.1' and ingress['port']==18080 and ingress['root']=='/var/www/print-system' and ingress['sitePath']=='/etc/nginx/sites-available/print-system' and ingress['proxyPass']=='http://127.0.0.1:48080' and ingress['expectedProof']['status']=='VERIFIED_INGRESS','final_ingress_not_fixed')
    value['executionAuthorization']=False;value['liveWiringStatus']='SOURCE_VALIDATED_NOT_CONNECTED_TO_CLI'
    return value

def renderer_source_action(prepare,prepare_ref,renderer_ref,run_id):
    """Freeze a future isolated action, without enabling or executing the renderer."""
    c.validate_ref(prepare_ref);c.validate_ref(renderer_ref)
    c.require(prepare.get('status')=='DRAFT_NOT_EXECUTABLE' and prepare.get('designSha256')==c.DESIGN_SHA and set(prepare['metadata'])==c.METADATA_KEYS,'renderer_prepare_not_exact')
    c.require(renderer_ref['path']==str(c.BASE/'ops/db/render-ordinary-modules-plan.py') and prepare['frozenFiles']['ops/db/render-ordinary-modules-plan.py']==renderer_ref['sha256'],'renderer_source_ref_not_bound')
    c.require(str(uuid.UUID(run_id))==run_id,'canonical_run_uuid_required')
    return {'kind':'UNIFIED_RENDER_SOURCE_ACTION_V3','executionAuthorization':False,'runId':run_id,'prepareRef':copy.deepcopy(prepare_ref),'rendererRef':copy.deepcopy(renderer_ref),'temporarySchema':'print_ordinary_render_'+uuid.UUID(run_id).hex,'sourceSchemaConnected':False,'metadataStages':list(c.STAGES),'ddlFiles':copy.deepcopy(c.DDL_FILES),'status':'SOURCE_ONLY_ISOLATED_ACTION_REVIEW_REQUIRED'}

if __name__=='__main__':print('SOURCE_ONLY / EXECUTION_DISABLED / DB_PORTS_NOT_RUN')
