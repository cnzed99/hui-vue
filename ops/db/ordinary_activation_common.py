#!/usr/bin/env python3
"""Independent ordinary activation primitives. Import is side effect free.
Metadata reader and mysql framing are copied stable primitives, never delegated to old executors.
"""
import copy, datetime, fcntl, hashlib, json, os, pathlib, re, selectors, stat, subprocess, time, uuid
BASE=pathlib.Path(__file__).resolve().parents[2]
SOCKET='/run/mysqld/mysqld.sock'
DESIGN_PATH='docs/design/phase1-unified-activation-v3.md'
DESIGN_SHA='02ffe0a147d74f3c4303bb70f465a9a67640a69da34d055d7b84966f81c34c3e'
PERMISSION_MAP_PATH='docs/design/phase1-unified-permission-map-v3.json'
PERMISSION_MAP_SHA='a394975a68bc31194f7db727e2aa11de45bb6f8d5ef376c9199b57c49d3b7eac'
CONFIRMATION_PATH='docs/decisions/ordinary-permission-matrix-confirmed-2026-10-29.json'
CONFIRMATION_SHA='58cbfe13f041bc64c97aff7ffcb48498b87ff9787ce0ebc199e5bde1f28fea41'
NEW_TABLES=('print_store_profile','print_library_settings','print_product_catalog_entry','print_library_catalog_entry','print_terminal_directory','print_printer_directory','print_library_category','print_paper_total_report','print_paper_daily_report','print_advertising_draft')
TABLES=('print_store','system_tenant','system_tenant_package','system_role','system_menu','system_role_menu')+NEW_TABLES
STAGES=tuple('G'+str(i) for i in range(12))
DDL_FILES={
 'ops/db/print-store-profile-v1.sql':'fb9bc37814852586b7022ac341fa961fdf5fc3159cacbf04237015940275b676',
 'ops/db/print-library-settings-v1.sql':'635266327c3c77b73277951686b5fc8a60cacd446dc6491f64ad164c7ab8d43f',
 'ops/db/print-readonly-directory-v1.draft.sql':'62848752c30014c1fee80a0c315168cb1baa4ae44cf6ca9c6acca3ff88e8c9dc',
 'ops/db/print-printer-directory-v1.draft.sql':'525a5527b5219376ad9bf4798c13667215943aed0adaf3870407395abbdf8dbb',
 'ops/db/print-library-category-v1.draft.sql':'99ce0b07863e0d360ef9d2c6d46033914fd8b7ee3f460b2195439f6d3359cab5',
 'ops/db/print-statistics-reports-v1.draft.sql':'c0c657e469d9711f3995bf4fa38be5eff758aba235de517a131beda5912386cc',
 'ops/db/print-advertising-v1.draft.sql':'75717d1bec23a6186a7c03c7f8e8d4a98840dbd56ee822edd77cf888b68ffe8b'}
PERMISSIONS=dict(zip(range(2010,2026),('print:store:query','print:store:update','print:library-settings:query','print:library-settings:update','print:library-category:query','print:library-category:create','print:library-category:update','print:library-category:delete','print:statistics:query','print:advertising:query','print:advertising:update','print:product:query','print:platform-product:query','print:library-entry:query','print:terminal-directory:query','print:printer-directory:query')))
GRANTS=tuple((r,m) for r,menus in ((1,tuple(m for m in PERMISSIONS if m!=2017)),(2,tuple(m for m,p in PERMISSIONS.items() if p.endswith(':query')))) for m in menus)
METADATA_KEYS_ORDER=('tables','columns','indexes','constraints','foreignKeys','checks','triggers')
METADATA_KEYS={'tables','columns','indexes','constraints','foreignKeys','checks','triggers'}
def require(ok,reason):
    if not ok: raise ValueError(reason)
def digest(raw): return hashlib.sha256(raw).hexdigest()
def canon(value): return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def utc_now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def exact_file(path,private=False):
    path=pathlib.Path(path)
    require(path.is_absolute() and '..' not in path.parts,'absolute_path_required')
    require(all(not p.is_symlink() for p in path.parents),'symlink_parent')
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    with os.fdopen(fd,'rb') as stream:
        info=os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_size<=32*1024**2,'finite_regular_file_required')
        if private: require(info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode)==0o600,'private_owner_mode_required')
        return stream.read()
def strict_json(raw):
    def pairs(rows):
        value={}
        for key,item in rows:
            require(key not in value,'duplicate_json_key'); value[key]=item
        return value
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda _:require(False,'nonfinite_json'))
def fsync_dir(path):
    fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try: os.fsync(fd)
    finally: os.close(fd)
def exclusive(path,raw):
    path=pathlib.Path(path)
    require(all(not p.is_symlink() for p in path.parents),'symlink_output_parent')
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as stream:
        stream.write(raw); stream.flush(); os.fsync(stream.fileno())
    fsync_dir(path.parent)
def identifier(value):
    require(isinstance(value,str) and re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]{0,63}',value),'unsafe_identifier')
    return '`'+value+'`'
def ddl_statements():
    statements=[]
    for relative,expected in DDL_FILES.items():
        raw=exact_file(BASE/relative); require(digest(raw)==expected,'ddl_source_changed')
        text='\n'.join(line for line in raw.decode().splitlines() if not line.lstrip().startswith('--'))
        statements.extend(part.strip() for part in text.split(';') if part.strip())
    require(len(statements)==11 and statements[0].startswith('ALTER TABLE print_store\n') and all(sql.startswith('CREATE TABLE '+table+' (') for sql,table in zip(statements[1:],NEW_TABLES)),'unexpected_ddl_statements')
    return statements
class DatabaseError(Exception):
    """Only a numeric mysql server diagnostic; never retain raw stderr or SQL values."""
    def __init__(self, mysql_code):
        self.mysql_code = mysql_code
        super().__init__('mysql_server_error')

class MysqlSession:
    """One mysql process = one transaction; no reconnect, shell or error/raw output."""
    def __init__(self):
        # Lazy fixed connector: import and construction perform no IO.
        self.process=None;self.selector=None;self.buffer=b'';self.error_buffer=b'';self.failed=False;self.closed=False

    def _start(self):
        require(not self.closed and not self.failed,'mysql_session_not_usable')
        if self.process is not None:return
        self.process=subprocess.Popen(['sudo','-n','/usr/bin/mysql','--batch','--raw','--skip-column-names','--unbuffered','--skip-reconnect','--connect-timeout=5','--default-character-set=utf8mb4','--protocol=SOCKET','--socket='+SOCKET,'print_demo'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0)
        os.set_blocking(self.process.stderr.fileno(),False)
        self.selector=selectors.DefaultSelector()
        self.selector.register(self.process.stdout,selectors.EVENT_READ,'stdout')
        self.selector.register(self.process.stderr,selectors.EVENT_READ,'stderr')

    @staticmethod
    def literal(value):
        if value is None:return 'NULL'
        if type(value) is bool:return '1' if value else '0'
        if type(value) is int:
            require(-(2**63)<=value<2**63,'sql_integer_out_of_range');return str(value)
        require(isinstance(value,str) and len(value.encode('utf-8'))<=4*1024**2,'sql_value_type_or_size_not_allowed')
        return "CONVERT(X'"+value.encode('utf-8').hex()+"' USING utf8mb4)"

    @classmethod
    def fixed_sql(cls,sql,params=()):
        # mysql CLI has no native prepared parameters: fixed templates plus strict typed serialization.
        require(isinstance(sql,str) and type(params) is tuple and sql.count('%s')==len(params),'sql_parameter_count_or_type')
        if params:
            columns={'system_menu':('id','name','permission','type','sort','parent_id','path','icon','component','component_name','status','visible','keep_alive','always_show','creator','create_time','updater','update_time','deleted'),'system_role_menu':('id','role_id','menu_id','creator','create_time','updater','update_time','deleted','tenant_id')}
            templates={'INSERT INTO '+table+' ('+','.join(names)+') VALUES ('+','.join(['%s']*len(names))+')' for table,names in columns.items()}
            templates.add('UPDATE system_tenant_package SET menu_ids=%s,update_time=%s WHERE id=%s AND deleted=%s')
            require(sql in templates,'sql_template_not_allowed')
            pieces=sql.split('%s');out=pieces[0]
            for value,tail in zip(params,pieces[1:]):out+=cls.literal(value)+tail
            return out
        require('%s' not in sql,'unbound_sql_parameter')
        identity_sql="SELECT JSON_OBJECT('schema',DATABASE(),'serverUuid',@@server_uuid,'version',@@version,'currentUser',CURRENT_USER(),'sqlMode',@@sql_mode,'socket',@@socket)"
        scalar=r"(?:`[a-z_][a-z0-9_]*`|CAST\(`[a-z_][a-z0-9_]*` AS (?:UNSIGNED|JSON)\))"
        pair=r"'[a-z_][a-z0-9_]*',"+scalar
        projection=r'SELECT JSON_OBJECT\('+pair+r'(?:,'+pair+r')*\) FROM `system_(?:tenant|tenant_package|role|menu|role_menu)` ORDER BY id(?: FOR UPDATE)?'
        require(sql in ('SET TRANSACTION ISOLATION LEVEL SERIALIZABLE','START TRANSACTION','ROLLBACK','COMMIT') or sql in ddl_statements() or sql in metadata_queries().values() or sql==identity_sql or sql=="SELECT JSON_OBJECT('connectionCount',COUNT(*)) FROM information_schema.PROCESSLIST WHERE DB='print_demo' AND ID<>CONNECTION_ID()" or re.fullmatch(projection,sql) is not None,'sql_template_not_allowed')
        return sql

    def check_error(self):
        while True:
            try:
                chunk = os.read(self.process.stderr.fileno(),65536)
            except BlockingIOError:
                break
            if not chunk:
                break
            self.error_buffer += chunk
            require(len(self.error_buffer) < 65536, 'mysql_diagnostic_limit')
        match = re.search(rb'(?:^|\n)ERROR ([0-9]+) \([A-Z0-9]+\)',self.error_buffer)
        if match:
            code = int(match.group(1))
            self.error_buffer = b''
            raise DatabaseError(code)

    def query(self,sql,params=()):
        return self._exchange(self.fixed_sql(sql,params))

    def _exchange(self,sql):
        try:return self._exchange_impl(sql)
        except BaseException:
            self.failed=True
            raise

    def _exchange_impl(self,sql):
        self._start()
        marker = ('UNIFIED_'+uuid.uuid4().hex).encode()
        self.process.stdin.write((sql.rstrip().rstrip(';')+";\nSELECT '"+marker.decode()+"';\n").encode())
        self.process.stdin.flush()
        deadline = time.monotonic()+60
        lines = []
        while True:
            self.check_error()
            while b'\n' in self.buffer:
                line, self.buffer = self.buffer.split(b'\n', 1)
                if line == marker:
                    return [row.decode('utf-8') for row in lines]
                lines.append(line)
                require(sum(map(len, lines)) < 4*1024**2, 'mysql_response_limit')
            remaining = deadline-time.monotonic()
            require(remaining > 0, 'mysql_timeout')
            events = self.selector.select(remaining)
            require(bool(events), 'mysql_timeout')
            self.check_error()
            for key,mask in events:
                if key.data == 'stdout':
                    chunk = os.read(self.process.stdout.fileno(), 65536)
                    if not chunk:
                        self.check_error()
                        raise OSError('mysql_connection_closed_without_server_diagnostic')
                    self.buffer += chunk

    def execute_with_row_count(self, sql):
        """Read DML diagnostics in the same exchange, before the framing SELECT resets them."""
        marker = 'P02_COUNT_'+uuid.uuid4().hex+':'
        rows = self._exchange(sql.rstrip().rstrip(';')+";\nSELECT CONCAT('"+marker+"',ROW_COUNT())")
        require(len(rows) == 1 and rows[0].startswith(marker), 'dml_row_count_frame_missing')
        value = rows[0][len(marker):]
        require(re.fullmatch(r'[0-9]+',value) is not None, 'invalid_dml_row_count')
        return int(value)

    def execute(self,sql,params=()):
        statement=self.fixed_sql(sql,params)
        if sql.startswith(('INSERT INTO ','UPDATE ')):return self.execute_with_row_count(statement)
        self._exchange(statement);return 0

    def commit(self):
        self._exchange('COMMIT');return 'ACK'

    def objects(self,sql,params=()):
        return [strict_json(row) for row in self.query(sql,params)]

    def close(self):
        if self.closed:return
        self.closed=True
        if self.process is None:return
        try:
            if self.process.poll() is None:
                self.process.stdin.write(b'ROLLBACK;\nQUIT;\n')
                self.process.stdin.flush()
                self.process.wait(timeout=5)
        except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=5)
        finally:
            self.selector.close()
            self.process.stdin.close()
            self.process.stdout.close()
            self.process.stderr.close()

def metadata_queries():
    """Finite fixed catalog templates; no database access."""
    names = ','.join("'"+name+"'" for name in TABLES)
    predicates = "TABLE_SCHEMA='print_demo' AND TABLE_NAME IN ("+names+")"
    queries = {
        'tables': "SELECT JSON_ARRAY(TABLE_NAME,ENGINE,TABLE_COLLATION) FROM information_schema.TABLES WHERE "+predicates+" ORDER BY TABLE_NAME",
        'columns': "SELECT JSON_ARRAY(TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME,GENERATION_EXPRESSION) FROM information_schema.COLUMNS WHERE "+predicates+" ORDER BY TABLE_NAME,ORDINAL_POSITION",
        'indexes': "SELECT JSON_ARRAY(TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,NON_UNIQUE,COLUMN_NAME,SUB_PART,INDEX_TYPE,IS_VISIBLE,EXPRESSION) FROM information_schema.STATISTICS WHERE "+predicates+" ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX",
        'constraints': "SELECT JSON_ARRAY(TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,ENFORCED) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='print_demo' AND TABLE_NAME IN ("+names+") ORDER BY TABLE_NAME,CONSTRAINT_NAME",
        'foreignKeys': "SELECT JSON_ARRAY(k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION,k.COLUMN_NAME,k.REFERENCED_TABLE_SCHEMA,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.UPDATE_RULE,r.DELETE_RULE) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE k.CONSTRAINT_SCHEMA='print_demo' AND k.TABLE_NAME IN ("+names+") ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION",
        'checks': "SELECT JSON_ARRAY(t.TABLE_NAME,c.CONSTRAINT_NAME,c.CHECK_CLAUSE,t.ENFORCED) FROM information_schema.CHECK_CONSTRAINTS c JOIN information_schema.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE c.CONSTRAINT_SCHEMA='print_demo' AND t.TABLE_NAME IN ("+names+") ORDER BY t.TABLE_NAME,c.CONSTRAINT_NAME",
        'triggers': "SELECT JSON_ARRAY(EVENT_OBJECT_TABLE,TRIGGER_NAME,ACTION_TIMING,EVENT_MANIPULATION,ACTION_STATEMENT,SQL_MODE,DEFINER) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='print_demo' AND EVENT_OBJECT_TABLE IN ("+names+") ORDER BY EVENT_OBJECT_TABLE,TRIGGER_NAME"
    }
    return queries
def metadata(db):
    return {key:db.objects(sql) for key,sql in metadata_queries().items()}

class ReadOnlyMysql(MysqlSession):
    def query(self,sql,params=()):
        require(not params and sql.startswith('SELECT ') and ';' not in sql and not re.search(r'\b(FOR UPDATE|INTO|SLEEP|GET_LOCK|RELEASE_LOCK|OUTFILE|LOAD_FILE)\b',sql,re.I),'readonly_query_required')
        return super().query(sql,params)
    def execute_with_row_count(self,*_):raise ValueError('readonly_execute_forbidden')
    def execute(self,*_):raise ValueError('readonly_execute_forbidden')
    def commit(self):raise ValueError('readonly_commit_forbidden')
    def close(self):
        # No rollback or recovery statements in a read-only collector.
        if self.closed:return
        self.closed=True
        if self.process is None:return
        try:
            if self.process.poll() is None:
                self.process.stdin.write(b'QUIT;\n'); self.process.stdin.flush(); self.process.wait(timeout=5)
        except (BrokenPipeError,OSError,subprocess.TimeoutExpired):
            self.process.kill(); self.process.wait(timeout=5)
        finally:
            self.selector.close(); self.process.stdin.close(); self.process.stdout.close(); self.process.stderr.close()
def identity(db):
    rows=db.objects("SELECT JSON_OBJECT('schema',DATABASE(),'serverUuid',@@server_uuid,'version',@@version,'currentUser',CURRENT_USER(),'sqlMode',@@sql_mode,'socket',@@socket)")
    require(len(rows)==1 and rows[0]['schema']=='print_demo' and rows[0]['currentUser']=='root@localhost','database_identity_mismatch')
    return rows[0]
def permissions(db,graph,locked=False):
    result={'schema':'print_demo'}
    for label,table in (('tenants','system_tenant'),('packages','system_tenant_package'),('roles','system_role'),('menus','system_menu'),('grants','system_role_menu')):
        columns=[row for row in graph['columns'] if row[0]==table]
        require(columns,'permission_table_metadata_missing')
        fields=[]
        for row in columns:
            name,kind=row[1],row[3]
            expression=identifier(name)
            if kind.startswith('bit('): expression='CAST('+expression+' AS UNSIGNED)'
            elif name=='menu_ids': expression='CAST('+expression+' AS JSON)'
            fields.extend(("'"+name+"'",expression))
        result[label]=db.objects('SELECT JSON_OBJECT('+','.join(fields)+') FROM '+identifier(table)+' ORDER BY id'+(' FOR UPDATE' if locked else ''))
    return result
def plan_permissions(snapshot):
    require(snapshot['schema']=='print_demo','wrong_schema')
    tenants=snapshot['tenants']; packages=snapshot['packages']; roles=snapshot['roles']
    require(len([t for t in tenants if t['id']==1])==1,'tenant_physical_conflict')
    tenant=next(t for t in tenants if t['id']==1)
    require(tenant['package_id']==1 and tenant['status']==0 and not tenant['deleted'],'tenant_inactive')
    require(not any(t['id']!=1 and t['package_id']==1 and not t['deleted'] for t in tenants),'shared_package')
    matching=[p for p in packages if p['id']==1]; require(len(matching)==1,'package_physical_conflict')
    package=matching[0]; require(package['status']==0 and not package['deleted'],'package_inactive')
    menu_ids=package['menu_ids']; require(type(menu_ids)==list and all(type(i)==int and i>0 for i in menu_ids) and len(set(menu_ids))==len(menu_ids),'invalid_package_set')
    for rid,code in ((1,'tenant_admin'),(2,'print_reader')):
        selected=[r for r in roles if r['id']==rid or (r['tenant_id']==1 and r['code']==code)]
        require(len(selected)==1 and selected[0]['id']==rid and selected[0]['tenant_id']==1 and selected[0]['code']==code and selected[0]['status']==0 and not selected[0]['deleted'],'role_conflict')
    parents=[m for m in snapshot['menus'] if m['id']==2000]
    require(len(parents)==1 and parents[0]['type']==1 and parents[0]['status']==0 and not parents[0]['deleted'],'parent_conflict')
    require(not any(m['id'] in PERMISSIONS or m['permission'] in PERMISSIONS.values() for m in snapshot['menus']),'menu_or_permission_conflict_including_soft_deleted')
    require(not any(g['menu_id'] in PERMISSIONS for g in snapshot['grants']),'grant_conflict_including_soft_deleted')
    pairs=[(g['tenant_id'],g['role_id'],g['menu_id']) for g in snapshot['grants']]
    require(len(set(pairs))==len(pairs),'duplicate_physical_grant')
    before={rid:sorted(g['menu_id'] for g in snapshot['grants'] if g['tenant_id']==1 and g['role_id']==rid and not g['deleted']) for rid in (1,2)}
    require(all(set(ids)<=set(menu_ids) for ids in before.values()),'role_outside_package')
    return {'status':'PENDING_HUMAN','packageAfter':sorted(set(menu_ids)|set(PERMISSIONS)), 'roleAfter':{str(rid):sorted(set(ids)|{m for r,m in GRANTS if r==rid}) for rid,ids in before.items()},'newGrants':[list(p) for p in GRANTS],'before':copy.deepcopy(snapshot),'categoryDeleteGranted':False}

# Pure source-only candidate/receipt schema. No filesystem/network access in these APIs.
HUMAN_FIELDS=('permissionMatrix','advertisingGateTrue','maintenanceWindow','acceptanceRecordRetention','localWriterExclusion')
INPUT_GROUPS=('design','ddl','adapters','artifacts','config','backup')
ADAPTER_SCHEMA='UNIFIED_ADAPTER_SOURCE_V3'
REQUIRED_ACCEPTANCE_ADAPTERS={'ops/acceptance/unified_v3_session_ports.py': 'e2800011ba6d5d07c72b04665115995ceb2722d4060f769ac6d911b594efe62e', 'ops/acceptance/unified_v3_acceptance_ports.py': '847e622d6c3f1df2b585f6a8c6d2c05a3503fd3982fa90dca7b19502ce5d6d8a'}
def validate_ref(ref):
    require(isinstance(ref,dict) and set(ref)=={'path','sha256'},'exact_file_ref_required')
    path=pathlib.PurePosixPath(ref['path'])
    require(path.is_absolute() and '..' not in path.parts and path.as_posix()==ref['path'] and ref['path'].startswith(str(BASE)+'/'),'narrow_project_ref_required')
    require(re.fullmatch('[0-9a-f]{64}',ref['sha256']) is not None,'sha256_ref_required')
    return ref
def session_output_contract(run_id):
    return {'tenantId':1,'clientId':'default','accessTtl':1800,'refreshTtl':86400,'allowRefresh':False,'ledgerPath':str(BASE/'runtime/private/ordinary-activation'/run_id/'sessions.jsonl'),'ownerMode':0o600,'parentMode':0o700,'createExclusive':True,'noFollow':True,'appendFsync':True,'outputLifecycle':'RUN_APPEND_ONLY_OUTPUT'}
def backup_output_contract(run_id,adapter_ref):
    output=BASE/'runtime/backups'/('ordinary-'+run_id)
    return {'adapterRef':copy.deepcopy(adapter_ref),'outputDirectory':str(output),'encryptedPath':str(output/'backup.aes'),'restoreReceiptPath':str(BASE/'runtime/private/ordinary-activation'/run_id/'restore-proof.json'),'successCriteria':{'restoreVerified':True,'temporarySchemaRemoved':True},'outputLifecycle':'RUN_GENERATED_OUTPUT'}
def freeze_adapter_candidate(draft):
    value=copy.deepcopy(draft)
    require(all(value.get('humanConfirmations',{}).get(k)=='HUMAN_CONFIRMED' for k in HUMAN_FIELDS),'PENDING_HUMAN_NOT_EXECUTION_CANDIDATE')
    require(value.get('schema')=='print_demo' and value.get('environment')=='CLOUD_DEMO' and value.get('scope')=={'tenantId':1,'packageId':1,'roleIds':[1,2]},'scope_expansion_forbidden')
    require(str(uuid.UUID(value['runId']))==value['runId'] and value['designSha256']==DESIGN_SHA,'run_design_binding_invalid')
    require(value['advertisingGate']=={'property':'print.advertising.enabled','effectiveValue':True},'gate_not_frozen_true')
    inputs=value['inputs'];require(set(inputs)==set(INPUT_GROUPS),'incomplete_input_schema')
    require(inputs['design']==[{'path':str(BASE/p),'sha256':sha} for p,sha in ((DESIGN_PATH,DESIGN_SHA),(PERMISSION_MAP_PATH,PERMISSION_MAP_SHA),(CONFIRMATION_PATH,CONFIRMATION_SHA))],'wrong_design_ref')
    for group,refs in inputs.items():
        require(isinstance(refs,list) and refs,'empty_frozen_input_group')
        for ref in refs:validate_ref(ref)
    require({r['path']:r['sha256'] for r in inputs['ddl']}=={str(BASE/p):sha for p,sha in DDL_FILES.items()},'ddl_refs_not_exact')
    require(value['ddlStatements']==ddl_statements(),'candidate_ddl_statements_not_exact')
    require(set(value['metadataStages'])==set(STAGES),'stage_graphs_incomplete')
    require(all(set(g)==METADATA_KEYS for g in value['metadataStages'].values()),'full_graph_keys_required')
    require(value['permissionProposal']==plan_permissions(value['permissionBefore']),'permission_matrix_not_from_frozen_proposal')
    require(value['sessionContract']==session_output_contract(value['runId']),'session_contract_not_exact')
    require(all(ref['path']!=value['sessionContract']['ledgerPath'] for refs in inputs.values() for ref in refs),'future_ledger_must_not_be_frozen_input')

    adapter_paths={str(BASE/p) for p in ('ops/db/ordinary_activation_common.py','ops/db/migrate-ordinary-modules.py','ops/db/ordinary_activation_db.py','ops/db/ordinary_activation_recovery.py','ops/db/render-ordinary-modules-plan.py','ops/runtime/ordinary_activation_runtime.py','ops/acceptance/ordinary_modules_activation_acceptance.py',*REQUIRED_ACCEPTANCE_ADAPTERS)}
    require({r['path'] for r in inputs['adapters']}==adapter_paths,'all_adapter_refs_required')
    adapter_refs={r['path']:r['sha256'] for r in inputs['adapters']}
    require(all(adapter_refs[str(BASE/path)]==sha for path,sha in REQUIRED_ACCEPTANCE_ADAPTERS.items()),'acceptance_adapter_sha_not_exact')
    for key,group in (('jar','artifacts'),('frontendManifest','artifacts'),('gateConfig','config')):
        require(validate_ref(value['release'][key]) in inputs[group],'release_ref_not_bound')
    backup=value['backupContract']
    require(validate_ref(backup['adapterRef']) in inputs['backup'],'backup_program_not_bound')
    require(backup==backup_output_contract(value['runId'],backup['adapterRef']),'backup_output_contract_not_exact')
    require(not ({backup['encryptedPath'],backup['restoreReceiptPath']} & {ref['path'] for refs in inputs.values() for ref in refs}),'future_backup_must_not_be_frozen_input')
    require(set(value['users'])=={'admin','reader'},'exact_two_users_required')
    for alias,role in (('admin',1),('reader',2)):
        actor=value['users'][alias];require(actor['tenantId']==1 and actor['roleId']==role and type(actor['userId']) is int and actor['userId']>0,'actor_scope_invalid')
        require(validate_ref(actor['credentialRef']) in inputs['config'] and actor['credentialRef']['path']==str(BASE/'runtime/secrets'/('smoke-'+alias+'.json')),'credential_ref_not_fixed')

    value.update(kind=ADAPTER_SCHEMA,status='FROZEN_SOURCE_ONLY_NOT_EXECUTABLE')
    return value
SOURCE_REVIEW_CONCLUSIONS={'PASS_SOURCE_ONLY'}
def validate_source_review_ref(ref):
    validate_ref(ref)
    path=pathlib.PurePosixPath(ref['path']);reviews=pathlib.PurePosixPath(str(BASE/'docs/reviews'))
    require(path.is_relative_to(reviews) and path!=reviews and path.suffix=='.json','review_path_not_in_reviews_allowlist')
    return ref
def read_source_review(ref):
    """Independent read-only reader. No artifact reader, connector, runner or journal use."""
    validate_source_review_ref(ref);path=pathlib.Path(ref['path']);reviews=BASE/'docs/reviews'
    require(all(not part.is_symlink() for part in (path,*path.parents)),'review_symlink_forbidden')
    require(path.resolve(strict=True).is_relative_to(reviews.resolve(strict=True)),'review_resolved_path_outside_allowlist')
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    with os.fdopen(fd,'rb') as stream:
        info=os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_nlink==1 and info.st_size<=32*1024**2,'single_regular_review_file_required')
        return stream.read()
def adapter_review_bindings(candidate):
    return {'candidateSha256':digest(canon(candidate)),'runId':candidate['runId'],'inputsSha256':digest(canon(candidate['inputs'])),'adapterSha256':{r['path']:r['sha256'] for r in candidate['inputs']['adapters']}}
def bind_adapter_review(candidate,review_raw,review_ref):
    validate_source_review_ref(review_ref)
    require(isinstance(review_raw,bytes),'exact_review_bytes_required');review=strict_json(review_raw)
    require(candidate.get('status')=='FROZEN_SOURCE_ONLY_NOT_EXECUTABLE','candidate_not_frozen')
    plan_sha=digest(canon(candidate));review_sha=digest(review_raw)
    require(review_ref['sha256']==review_sha and review.get('actualModel')=='gpt-6-astra' and review.get('actualEffort')=='high' and review.get('conclusion') in SOURCE_REVIEW_CONCLUSIONS,'exact_high_source_only_review_required')
    require('verdict' not in review or review['verdict']==review['conclusion'],'conflicting_review_verdict')
    require(review.get('bindings')==adapter_review_bindings(candidate),'review_binding_mismatch')
    return {'kind':ADAPTER_SCHEMA,'status':'BOUND_SOURCE_ONLY_NOT_EXECUTABLE','candidate':copy.deepcopy(candidate),'candidateSha256':plan_sha,'review':copy.deepcopy(review),'reviewRef':copy.deepcopy(review_ref),'reviewSha256':review_sha}
class ReceiptBook:
    def __init__(self,envelope):
        require(envelope.get('status')=='BOUND_SOURCE_ONLY_NOT_EXECUTABLE','bound_envelope_required')
        self.run_id=envelope['candidate']['runId'];self.plan_sha=envelope['candidateSha256'];self.review_sha=envelope['reviewSha256'];self.inputs_sha=digest(canon(envelope['candidate']['inputs']));self.rows=[]
    def add(self,stage,status,**safe):
        require(status in ('INTENT','OBSERVED','UNKNOWN'),'receipt_status_invalid')
        def clean(value):
            if isinstance(value,dict):
                require(not any(re.search(r'password|secret|credential|accessToken|refreshToken|authorization|rawToken',k,re.I) for k in value),'secret_receipt_key_forbidden')
                for item in value.values():clean(item)
            elif isinstance(value,(list,tuple)):
                for item in value:clean(item)
        clean(safe)
        row={'schema':ADAPTER_SCHEMA,'runId':self.run_id,'planSha256':self.plan_sha,'reviewSha256':self.review_sha,'inputsSha256':self.inputs_sha,'sequence':len(self.rows)+1,'stage':stage,'status':status,**safe}
        self.rows.append(row);return copy.deepcopy(row)

def verify_frozen_inputs(candidate,read_ref=None):
    require(read_ref is not None,'SOURCE_ONLY_NO_INPUT_READER')
    for refs in candidate['inputs'].values():
        for ref in refs:require(digest(read_ref(copy.deepcopy(ref)))==ref['sha256'],'frozen_input_bytes_drift')
