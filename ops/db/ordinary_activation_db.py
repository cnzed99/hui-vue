"""Parameter-bound DB adapter, SOURCE ONLY. Construction/import never connects.
Port factory must explicitly provide query(sql,params), execute(sql,params)->rowcount,
objects(sql), close(), and commit()->ACK. No default/live connector, retry or reconnect.
"""
import copy,json
from ordinary_activation_common import require,canon,digest,metadata,identity,plan_permissions,PERMISSIONS,STAGES,GRANTS,ddl_statements,permissions
def ddl_sql_shas():return tuple(digest(sql.encode()) for sql in ddl_statements())
TABLES={'tenants':'system_tenant','packages':'system_tenant_package','roles':'system_role','menus':'system_menu','grants':'system_role_menu'}
MENU_COLUMNS=('id','name','permission','type','sort','parent_id','path','icon','component','component_name','status','visible','keep_alive','always_show','creator','create_time','updater','update_time','deleted')
GRANT_COLUMNS=('id','role_id','menu_id','creator','create_time','updater','update_time','deleted','tenant_id')
class UnknownCommit(RuntimeError):pass
class DatabaseAdapter:
    def __init__(self,plan,connect=None):
        self.plan=copy.deepcopy(plan);self.connect=connect;self.db=None;self.lease=None;self.journal=None;self.receipts=None;self.ddl_index=0;self.commit_started=False;self.committed=False;self.transaction=False;self.used=False
    def bind(self,lease,journal,receipts):self.lease,self.journal,self.receipts=lease,journal,receipts
    def guard(self):
        require(self.lease is not None,'lock_first_required');self.lease.check();require(self.connect is not None,'SOURCE_ONLY_NO_DB_CONNECTOR')
    def event(self,stage,status,**safe):
        self.guard();self.journal.append(stage+'_'+status,**safe);self.receipts.add(stage,status,**safe)
    def start(self):
        self.guard();require(not self.used,'database_adapter_one_run_only');self.used=True;self.db=self.connect()
        require(identity(self.db)==self.plan['dbIdentity'],'database_identity_drift')
        require(metadata(self.db)==self.plan['metadataStages']['G0'],'baseline_graph_drift')
        require(self.snapshot(self.db,False)==self.plan['permissionBefore'],'baseline_physical_permissions_drift')
        self.event('DB_BASELINE','OBSERVED')
    def snapshot(self,db,locked):
        # Per-column JSON projection preserves every physical field and normalizes BIT/JSON.
        return permissions(db,self.plan['metadataStages']['G0'],locked)
    def ddl(self,statement):
        self.guard();require(self.db is not None and self.ddl_index<len(STAGES)-1 and digest(statement.encode())==ddl_sql_shas()[self.ddl_index],'ddl_not_next_exact_frozen_statement')
        require(metadata(self.db)==self.plan['metadataStages'][STAGES[self.ddl_index]],'ddl_predecessor_graph_drift')
        stage=STAGES[self.ddl_index+1];self.event(stage+'_DDL','INTENT',sqlSha256=ddl_sql_shas()[self.ddl_index])
        try:
            self.db.execute(statement,());require(metadata(self.db)==self.plan['metadataStages'][stage],'ddl_observed_graph_mismatch')
        except BaseException:self.event(stage+'_DDL','UNKNOWN');raise
        self.ddl_index+=1;self.event(stage+'_DDL','OBSERVED',graphSha256=digest(canon(self.plan['metadataStages'][stage])))
    def expected_permissions(self):
        before=self.plan['permissionBefore'];proposal=self.plan['permissionProposal'];require(proposal==plan_permissions(before),'proposal_changed')
        rows=self.plan['permissionRows'];menus=rows['menus'];grants=rows['grants']
        require(len(menus)==len(PERMISSIONS) and {m['id'] for m in menus}==set(PERMISSIONS),'sixteen_exact_menus_required')
        require(all(set(m)==set(MENU_COLUMNS) and m['permission']==PERMISSIONS[m['id']] and m['type']==3 and m['parent_id']==2000 and m['status']==0 and not m['deleted'] for m in menus),'full_menu_fields_or_permission_mismatch')
        require(len(grants)==len(GRANTS) and len({g['id'] for g in grants})==len(GRANTS) and sorted([g['role_id'],g['menu_id']] for g in grants)==sorted(proposal['newGrants']),'twenty_five_exact_grant_rows_required')
        require(all(set(g)==set(GRANT_COLUMNS) and g['tenant_id']==1 and not g['deleted'] for g in grants),'full_grant_fields_or_scope_mismatch')
        require(not ({g['id'] for g in grants}&{g['id'] for g in before['grants']}),'grant_physical_id_conflict')
        expected=copy.deepcopy(before)
        for key in ('menus','grants'):expected[key]=sorted(expected[key]+copy.deepcopy(rows[key]),key=lambda r:r['id'])
        package=next(p for p in expected['packages'] if p['id']==1);package['menu_ids']=proposal['packageAfter']
        return expected
    def permissions_transaction(self):
        self.guard();require(self.ddl_index==len(STAGES)-1 and not self.transaction and not self.commit_started,'permission_transaction_not_ready')
        expected=self.expected_permissions();self.event('PERMISSION_TRANSACTION','INTENT')
        self.db.execute('SET TRANSACTION ISOLATION LEVEL SERIALIZABLE',());self.db.execute('START TRANSACTION',());self.transaction=True
        try:
            require(self.snapshot(self.db,True)==self.plan['permissionBefore'],'permission_drift_before_dml')
            rows=self.plan['permissionRows']
            for table,columns,records in (('system_menu',MENU_COLUMNS,rows['menus']),('system_role_menu',GRANT_COLUMNS,rows['grants'])):
                sql='INSERT INTO '+table+' ('+','.join(columns)+') VALUES ('+','.join(['%s']*len(columns))+')'
                for record in records:require(self.db.execute(sql,tuple(record[k] for k in columns))==1,'permission_insert_rowcount')
            before_package=next(p for p in self.plan['permissionBefore']['packages'] if p['id']==1)
            require('update_time' in before_package,'full_package_physical_fields_required')
            require(self.db.execute('UPDATE system_tenant_package SET menu_ids=%s,update_time=%s WHERE id=%s AND deleted=%s',(json.dumps(self.plan['permissionProposal']['packageAfter'],separators=(',',':')),before_package['update_time'],1,0))==1,'package_update_rowcount')
            # Full locked physical readback immediately before COMMIT: old rows and all sixteen new menu fields preserved.
            require(self.snapshot(self.db,True)==expected,'precommit_physical_readback_mismatch')
            self.event('PERMISSION_PRECOMMIT','OBSERVED',physicalSha256=digest(canon(expected)))
        except BaseException:
            self.db.execute('ROLLBACK',());self.transaction=False;self.event('PERMISSION_ROLLBACK','OBSERVED');raise
        self.event('PERMISSION_COMMIT','INTENT');self.commit_started=True
        try:ack=self.db.commit();require(ack=='ACK','unknown_commit_ack')
        except BaseException:
            self.transaction=False;self.event('PERMISSION_COMMIT','UNKNOWN');raise UnknownCommit('COMMIT_UNKNOWN_NO_REPLAY') from None
        self.transaction=False;self.committed=True;self.event('PERMISSION_COMMIT','OBSERVED',ack='ACK')
        self.db.close();self.db=None
        fresh=self.connect()
        try:
            require(identity(fresh)==self.plan['dbIdentity'] and metadata(fresh)==self.plan['metadataStages'][STAGES[-1]],'independent_postcommit_identity_graph_mismatch')
            require(self.snapshot(fresh,False)==expected,'independent_postcommit_physical_readback_mismatch')
            self.event('INDEPENDENT_READBACK','OBSERVED',physicalSha256=digest(canon(expected)))
        finally:fresh.close()
        return expected
    def close(self):
        # Never infer COMMIT outcome, auto-rollback committed DDL, or open another connection for recovery.
        if self.db is not None:self.db.close();self.db=None
