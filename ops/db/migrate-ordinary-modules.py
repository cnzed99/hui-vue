#!/usr/bin/env python3
"""SOURCE ONLY: independent ordinary-module activation candidate. Default zero actions.
--prepare uses fixed read-only collection and emits a permanently pending draft.
--apply validates exact approvals, then refuses the unimplemented real runtime adapter.
No old executor is imported or invoked. run_model is injectable fake-transport state logic only.
"""
import argparse, fcntl, json, os, pathlib, stat, sys, uuid
sys.dont_write_bytecode=True
from ordinary_activation_common import *
LOCK=BASE/'runtime/locks/fixture-mutation.lock'
SOURCES=('ops/acceptance/unified_v3_session_ports.py','ops/acceptance/unified_v3_acceptance_ports.py','ops/db/unified_activation_db_ports.py','ops/db/ordinary_activation_common.py','ops/db/migrate-ordinary-modules.py','ops/db/ordinary_activation_db.py','ops/runtime/ordinary_activation_runtime.py','ops/db/ordinary_activation_recovery.py','ops/db/render-ordinary-modules-plan.py','ops/acceptance/ordinary_modules_activation_acceptance.py')
CONFIRMATIONS=('permissionMatrix','advertisingGateTrue','acceptanceRecordRetention','maintenanceWindow','localWriterExclusion')
def source_hashes():
    return {p:digest(exact_file(BASE/p)) for p in (*SOURCES,*DDL_FILES,DESIGN_PATH,PERMISSION_MAP_PATH,CONFIRMATION_PATH)}
def prepare():
    db=ReadOnlyMysql()
    try:
        ident=identity(db); graph=metadata(db); snapshot=permissions(db,graph)
        require(identity(db)==ident and metadata(db)==graph and permissions(db,graph)==snapshot,'baseline_changed_during_collection')
        planned=plan_permissions(snapshot)
        return {'kind':'UNIFIED_ACTIVATION_DRAFT_V3','status':'DRAFT_NOT_EXECUTABLE','humanStatus':'PENDING_HUMAN','permissionMatrix':'CONFIRMED_HUMAN_SOURCE_ONLY','advertisingGate':{'target':True,'confirmation':'PENDING_HUMAN'},'designSha256':DESIGN_SHA,'identity':ident,'metadata':graph,'permissions':snapshot,'permissionProposal':planned,'frozenFiles':source_hashes(),'baselineConcurrency':'READ_ONLY_UNLOCKED_MUST_RECHECK_UNDER_EXECUTION_LOCK','metadataStagesStatus':'NOT_RENDERED','runId':None,'runtimeAcceptance':'NOT_RUN','SLOT_REMOVAL_HTTP':'NOT_RUN','missing':['human_confirmations','authoritative_render','release_artifacts','effective_gate_configuration','backup_restore','private_session_contract','Astra_PASS_MIGRATION_EXECUTION']}
    finally: db.close()
def verify_plan(plan,review,plan_sha,run_id,confirmation):
    require(plan.get('status')=='EXECUTION_CANDIDATE' and plan.get('kind')=='UNIFIED_ACTIVATION_EXECUTION_PLAN_V3','pending_draft_not_executable')
    require(str(uuid.UUID(run_id))==run_id and plan['runId']==run_id and confirmation=='CONFIRM_ORDINARY:'+run_id+':'+plan_sha,'run_or_human_confirmation_mismatch')
    require(all(plan['humanConfirmations'].get(k)=='HUMAN_CONFIRMED' for k in CONFIRMATIONS),'human_confirmation_pending')
    require(plan['advertisingGate']['effectiveValue'] is True and plan['schema']=='print_demo' and plan['environment']=='CLOUD_DEMO','scope_or_gate_mismatch')
    require(plan['designSha256']==DESIGN_SHA and digest(exact_file(BASE/DESIGN_PATH))==DESIGN_SHA,'design_changed')
    hashes=source_hashes(); require(plan['frozenFiles']==hashes,'source_changed')
    require(review['actualModel']=='gpt-6-astra' and review['actualEffort']=='high' and review['conclusion']=='PASS_MIGRATION_EXECUTION','missing_astra_execution_review')
    require(review['bindings']=={'planSha256':plan_sha,'runId':run_id,'frozenFiles':hashes},'astra_binding_mismatch')
    require(set(plan['metadataStages'])==set(STAGES) and all(set(g)==METADATA_KEYS for g in plan['metadataStages'].values()),'missing_exact_graphs')
    proof=plan['metadataProvenance']
    require(proof['kind']=='AUTHORITATIVE_ISOLATED_RENDER' and proof['temporarySchemaRemoved'] is True and proof['sourceSchemaConnected'] is False and proof['baselineExactMatch'] is True,'missing_render_provenance')
    require(proof['stageSha256']=={k:digest(canon(v)) for k,v in plan['metadataStages'].items()},'graph_provenance_mismatch')
    require(plan['release']['newJarSha256'] and plan['release']['frontendManifestSha256'] and plan['release']['effectiveGateConfigSha256'],'release_not_frozen')
    require(plan_permissions(plan['permissionBefore'])==plan['permissionProposal'],'permission_proposal_mismatch')
    ddl_statements()
class SharedLock:
    def __init__(self,path): self.path=path; self.fd=None
    def __enter__(self):
        require(all(not p.is_symlink() for p in self.path.parents),'symlink_lock_parent')
        fd=os.open(self.path,os.O_RDWR|os.O_NOFOLLOW)
        try:
            info=os.fstat(fd); require(stat.S_ISREG(info.st_mode) and info.st_nlink==1,'unsafe_shared_lock')
            fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            require((info.st_dev,info.st_ino)==(self.path.stat().st_dev,self.path.stat().st_ino),'lock_replaced')
            self.fd=fd; return self
        except BaseException: os.close(fd); raise
    def check(self):
        require(self.fd is not None,'lock_not_owned')
        info=os.fstat(self.fd); actual=self.path.stat()
        require((info.st_dev,info.st_ino)==(actual.st_dev,actual.st_ino) and info.st_nlink==1,'lock_lost')
    def __exit__(self,*_): os.close(self.fd); self.fd=None
class Journal:
    def __init__(self,path,run_id,plan_sha):
        require(path.name==run_id+'.jsonl' and str(uuid.UUID(run_id))==run_id,'journal_run_mismatch')
        require(path.parent.is_dir() and path.parent.stat().st_uid==os.getuid() and stat.S_IMODE(path.parent.stat().st_mode)==0o700,'private_journal_directory_required')
        exclusive(path,b''); self.fd=os.open(path,os.O_WRONLY|os.O_APPEND|os.O_NOFOLLOW); self.seq=0; self.sha=plan_sha; self.run_id=run_id
    def append(self,phase,**safe):
        self.seq+=1; raw=canon(dict(sequence=self.seq,phase=phase,runId=self.run_id,planSha256=self.sha,**safe))+b'\n'
        require(os.write(self.fd,raw)==len(raw),'journal_short_write'); os.fsync(self.fd)
    def close(self): os.close(self.fd)
def run_model(plan,transport,journal_factory,lock_path=LOCK):
    """State machine for injected test adapters. CLI never supplies a live transport.
    All transport methods are strict proof-returning contracts, never generic shell/SQL.
    """
    with SharedLock(lock_path) as lock:
        # Contender failure occurs outside every service, database, journal and recovery branch.
        journal=journal_factory(); sessions=False; cleanup_started=False; ready=False
        def step(method,*args):
            lock.check(); require(getattr(transport,method)(*args) is True,method+'_not_proven')
        try:
            journal.append('LOCK_ACQUIRED'); step('preflight',plan)
            journal.append('CLOSE_INGRESS_INTENT'); step('close_ingress_and_drain'); journal.append('INGRESS_CLOSED')
            journal.append('STOP_BACKEND_INTENT'); step('stop_backend_connections_zero'); journal.append('WRITERS_ZERO')
            step('locked_baseline',plan); journal.append('BASELINE_RECHECKED')
            journal.append('BACKUP_INTENT'); step('backup_restore_verified',plan); journal.append('BACKUP_OBSERVED')
            for sql,stage in zip(ddl_statements(),STAGES[1:]):
                step('assert_closed'); journal.append(stage+'_DDL_INTENT')
                step('execute_ddl',sql); step('observe_graph',stage,plan['metadataStages'][stage]); journal.append(stage+'_DDL_OBSERVED')
            journal.append('PERMISSION_SERIALIZABLE_INTENT')
            step('permission_transaction_serializable_locked_recheck',plan['permissionBefore'],plan['permissionProposal'])
            journal.append('PERMISSION_COMMIT_INTENT'); step('commit_acknowledged'); journal.append('PERMISSION_COMMIT_ACK')
            step('independent_postcommit_readback',plan); journal.append('POSTCOMMIT_OBSERVED')
            step('install_one_frozen_release',plan); step('start_backend_loopback_gate_verified',plan)
            sessions=True; journal.append('SESSION_LOGIN_INTENT'); step('login_exact_ledger_sessions',plan)
            step('cache_noop_once_exact_rows',plan); step('http_browser_acceptance',plan)
            cleanup_started=True; journal.append('SESSION_CLEANUP_INTENT')
            step('cleanup_access_refresh_and_browser_zero',plan); sessions=False; journal.append('TOKEN_CLEAN')
            step('final_checks',plan); journal.append('READY_TO_REOPEN'); ready=True
            step('open_ingress_and_external_verify',plan); journal.append('COMPLETE')
            return {'status':'MODEL_COMPLETE','runtimeAcceptance':'SIMULATED_ONLY'}
        except BaseException:
            # Never rollback DDL, retry COMMIT/login, read another run, or automatically reopen.
            lock.check(); step('ensure_ingress_closed')
            if sessions:
                if cleanup_started:
                    # Adapter may already have changed session state: never replay an unknown result.
                    journal.append('SESSION_CLEANUP_UNKNOWN')
                else:
                    cleanup_started=True; journal.append('SESSION_CLEANUP_INTENT')
                    try: step('cleanup_access_refresh_and_browser_zero',plan); journal.append('TOKEN_CLEAN')
                    except BaseException: journal.append('SESSION_CLEANUP_UNKNOWN')
            journal.append('STOPPED_REQUIRES_REVIEWED_RECOVERY'); raise
        finally: journal.close()
def main():
    parser=argparse.ArgumentParser(description=__doc__); modes=parser.add_mutually_exclusive_group()
    modes.add_argument('--prepare',action='store_true'); modes.add_argument('--apply',action='store_true')
    for name in ('plan','plan-sha256','review','review-sha256','run-id','confirm'): parser.add_argument('--'+name)
    args=parser.parse_args()
    if args.apply: raise ValueError('SOURCE_ONLY_RUNTIME_HTTP_ADAPTERS_NOT_IMPLEMENTED')
    if args.prepare: print(json.dumps(prepare())); return
    if not args.apply: print('PENDING_HUMAN / NOT_APPLIED'); return
def run_source_adapters(envelope,db,runtime,acceptance,journal_factory,lock_factory=None,input_reader=None,review_reader=None):
    """Not reachable from CLI. Explicit injected ports only; lock precedes any input/port IO."""
    require(envelope.get('status')=='BOUND_SOURCE_ONLY_NOT_EXECUTABLE','source_bound_envelope_required')
    plan=envelope['candidate'];require(freeze_adapter_candidate(plan)==plan,'frozen_candidate_drift')
    require(envelope['candidateSha256']==digest(canon(plan)),'candidate_sha_mismatch')
    # Pure full-plan comparison precedes ALL readers, locks, journal construction and port calls.
    for adapter in (db,runtime,acceptance):
        require(isinstance(getattr(adapter,'plan',None),dict) and canon(adapter.plan)==canon(plan),'adapter_full_plan_mismatch')
    validate_source_review_ref(envelope['reviewRef'])
    # Always reread exact review bytes independently of the artifact/input reader.
    raw_review=(review_reader or read_source_review)(copy.deepcopy(envelope['reviewRef']))
    refreshed=bind_adapter_review(plan,raw_review,envelope['reviewRef'])
    require(canon(refreshed)==canon(envelope),'reread_review_envelope_mismatch')
    factory=lock_factory or (lambda:SharedLock(LOCK))
    with factory() as lease:
        lease.check();verify_frozen_inputs(plan,input_reader)
        journal=journal_factory();receipts=ReceiptBook(envelope)
        for adapter in (db,runtime,acceptance):adapter.bind(lease,journal,receipts)
        sessions=False
        try:
            journal.append('LOCK_ACQUIRED');runtime.preflight();runtime.close_ingress();runtime.stop_backend();db.start();runtime.backup_verified()
            for sql in plan['ddlStatements']:runtime.assert_closed();db.ddl(sql)
            expected=db.permissions_transaction();runtime.install_release();runtime.start_backend()
            sessions=True;acceptance.login();acceptance.cache_noop(expected);acceptance.accept();acceptance.cleanup();sessions=False
            journal.append('READY_TO_REOPEN');runtime.reopen();journal.append('COMPLETE_SOURCE_PORT_CHAIN')
            return {'status':'SOURCE_PORT_CHAIN_COMPLETE_NOT_LIVE_ACCEPTANCE','receipts':receipts.rows}
        except BaseException:
            runtime.keep_closed()
            if sessions and not acceptance.cleanup_started:
                try:acceptance.cleanup()
                except BaseException:journal.append('SESSION_CLEANUP_UNKNOWN')
            elif sessions:journal.append('SESSION_CLEANUP_UNKNOWN')
            journal.append('STOPPED_REQUIRES_REVIEWED_RECOVERY');raise
        finally:db.close();journal.close()

if __name__=='__main__':
    try: main()
    except Exception as error:
        print(json.dumps({'status':'NOT_APPLIED_OR_STOPPED','exceptionClass':type(error).__name__}),file=sys.stderr); sys.exit(1)
