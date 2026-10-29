#!/usr/bin/env python3
"""Source-only authoritative isolated renderer candidate. Default NOT_RENDERED.
CLI --render-draft remains disabled pending an exact isolated-action review.
No production-schema connection; produces permanently nonexecutable graphs after proven cleanup.
Unsupported existing FK/trigger/type reconstruction fails closed and requires renderer extension review.
"""
import argparse, collections, importlib.util, json, os, pathlib, re, shutil, stat, subprocess, sys, uuid
sys.dont_write_bytecode=True
from ordinary_activation_common import *
need=require
sha=digest
read=exact_file
SCHEMA_PATTERN=r'print_ordinary_render_[a-f0-9]{32}'
def baseline_statements(graph, tables):
    """Support only the finite existing graph. Exact rendered equality is mandatory later."""
    need(set(graph) == METADATA_KEYS and not graph['foreignKeys'] and not graph['triggers'],
         'unsupported_baseline_fk_or_trigger_requires_separate_renderer')
    need({'print_store','system_tenant','system_tenant_package','system_role','system_menu','system_role_menu'} <= {row[0] for row in graph['tables']} <= set(tables)-set(NEW_TABLES),
         'unexpected_baseline_tables')
    statements = []
    for table,engine,collation in graph['tables']:
        need(engine == 'InnoDB' and collation == 'utf8mb4_unicode_ci','unsupported_table_attributes')
        columns = [row for row in graph['columns'] if row[0] == table]
        need([row[2] for row in columns] == list(range(1,len(columns)+1)),'column_order_incomplete')
        definitions = []
        for _,name,ordinal,kind,nullable,default,extra,charset,column_collation,generation in columns:
            need(re.fullmatch(r'(?:bigint|int|tinyint|datetime|text|bit\(1\)|varchar\([1-9][0-9]{0,4}\))',kind),
                 'unsupported_column_type')
            need(nullable in ('YES','NO') and generation == '', 'unsupported_column_generation')
            need((charset,column_collation) in ((None,None),('utf8mb4','utf8mb4_unicode_ci')),
                 'unsupported_column_collation')
            need(extra in ('','auto_increment','DEFAULT_GENERATED',
                           'DEFAULT_GENERATED on update CURRENT_TIMESTAMP'),'unsupported_column_extra')
            definition = identifier(name)+' '+kind
            if charset is not None:
                definition += ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'
            definition += ' NULL' if nullable == 'YES' else ' NOT NULL'
            if default is not None:
                need(isinstance(default,str),'unsupported_default_value')
                if default == 'CURRENT_TIMESTAMP':
                    need(kind == 'datetime','timestamp_default_on_non_datetime')
                    definition += ' DEFAULT CURRENT_TIMESTAMP'
                elif kind == 'bit(1)':
                    need(default in ("b'0'","b'1'"),'unsupported_bit_default')
                    definition += ' DEFAULT '+default
                elif kind in ('bigint','int','tinyint'):
                    need(re.fullmatch(r'-?[0-9]+',default),'unsupported_numeric_default')
                    definition += ' DEFAULT '+default
                else:
                    need(re.fullmatch(r'[a-zA-Z0-9_# .:/-]*',default),'unsupported_text_default')
                    definition += " DEFAULT '"+default+"'"
            elif nullable == 'YES':
                definition += ' DEFAULT NULL'
            if extra == 'auto_increment':
                definition += ' AUTO_INCREMENT'
            if 'on update' in extra:
                definition += ' ON UPDATE CURRENT_TIMESTAMP'
            definitions.append(definition)
        indexes = collections.defaultdict(list)
        for row in graph['indexes']:
            if row[0] == table:
                indexes[row[1]].append(row)
        for name,rows in sorted(indexes.items()):
            need([row[2] for row in rows] == list(range(1,len(rows)+1)),'index_order_incomplete')
            need(all(row[3] == rows[0][3] and row[5] is None and row[6:] == ['BTREE','YES',None]
                     for row in rows),'unsupported_index_definition')
            fields = ','.join(identifier(row[4]) for row in rows)
            if name == 'PRIMARY':
                need(rows[0][3] == 0,'invalid_primary_key')
                definitions.append('PRIMARY KEY ('+fields+')')
            else:
                need(rows[0][3] in (0,1),'invalid_index_uniqueness')
                definitions.append(('UNIQUE KEY ' if rows[0][3] == 0 else 'KEY ')+identifier(name)+' ('+fields+')')
        checks = [row for row in graph['checks'] if row[0] == table]
        for _,name,clause,enforced in checks:
            # This is the sole baseline CHECK. Do not execute arbitrary SQL from JSON input.
            need((table,name,clause,enforced) ==
                 ('print_store','ck_print_store_status','(`status` in (0,1))','YES'),
                 'unsupported_baseline_check')
            definitions.append('CONSTRAINT '+identifier(name)+' CHECK ('+clause+')')
        statements.append('CREATE TABLE '+identifier(table)+' (\n'+',\n'.join(definitions)+
                          '\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci')
    return statements
class Isolated:
    """All SQL is captured by category and refused if it names the production schema."""
    def __init__(self,schema,journal,executor):
        need(re.fullmatch(SCHEMA_PATTERN,schema),'temporary_schema_name_invalid')
        self.schema,self.journal,self.executor = schema,journal,executor
        self.connections = collections.Counter()

    def event(self,phase,**values):
        row = dict(phase=phase,**values)
        self.journal.write(canon(row)+b'\n')
        self.journal.flush()
        os.fsync(self.journal.fileno())

    def command(self,sql,database,category):
        need(database in ('information_schema',self.schema),'database_connection_outside_allowlist')
        need(re.search(r'\bprint_demo\b',sql,re.I) is None,'production_schema_sql_forbidden')
        need(';\n' not in sql and ';' not in sql,'multi_statement_sql_forbidden')
        if category == 'catalog':
            allowed = ["SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+self.schema+"'",
                       'CREATE DATABASE '+identifier(self.schema)+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
                       'DROP DATABASE '+identifier(self.schema)]
            need(database == 'information_schema' and sql in allowed,'catalog_sql_not_exact')
        elif category == 'metadata':
            need(database == self.schema and sql.startswith('SELECT JSON_ARRAY(')
                 and 'information_schema.' in sql,'metadata_sql_not_isolated')
        elif category == 'identity':
            need(database == self.schema and sql ==
                 "SELECT JSON_OBJECT('schema',DATABASE(),'serverUuid',@@server_uuid,'version',@@version,'currentUser',CURRENT_USER(),'sqlMode',@@sql_mode)",
                 'identity_query_not_fixed')
        elif category == 'empty':
            need(database == self.schema and sql in ['SELECT COUNT(*) FROM '+identifier(t) for t in TABLES],
                 'row_count_query_not_fixed')
        else:
            need(category == 'structure' and database == self.schema
                 and sql in self.allowed_structure,'structure_not_frozen')
        self.event('SQL_INTENT',database=database,category=category,sqlSha256=sha(sql.encode()))
        self.connections[database] += 1
        result = subprocess.run(['sudo','-n','/usr/bin/mysql','--batch','--raw','--skip-column-names',
            '--skip-reconnect','--connect-timeout=5','--default-character-set=utf8mb4',
            '--protocol=SOCKET','--socket='+SOCKET,'--database='+database],
            input=(sql+';\n').encode(),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=45)
        need(result.returncode == 0,'isolated_mysql_operation_failed')
        self.event('SQL_OBSERVED',database=database,category=category,sqlSha256=sha(sql.encode()))
        return result.stdout.decode().splitlines()

    def objects(self,sql):
        need(sql.count("'print_demo'") > 0,'metadata_scope_literal_missing')
        rebound = sql.replace("'print_demo'","'"+self.schema+"'")
        rows = [json.loads(line) for line in self.command(rebound,self.schema,'metadata')]
        # Only the schema-coordinate field of FK metadata is renamed to the target namespace.
        if 'REFERENCED_TABLE_SCHEMA' in sql:
            for row in rows:
                need(row[4] == self.schema,'foreign_key_escaped_isolated_schema')
                row[4] = 'print_demo'
        return rows

    def present(self):
        rows = self.command("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+
                            self.schema+"'",'information_schema','catalog')
        need(rows in (['0'],['1']),'schema_presence_result_unknown')
        self.event('SCHEMA_PRESENCE_OBSERVED',schema=self.schema,rowCount=int(rows[0]))
        return rows == ['1']
def render(args):
    raw_bytes=exact_file(args.prepare,True)
    require(digest(raw_bytes)==args.prepare_sha256,'prepare_changed')
    raw=strict_json(raw_bytes)
    require(raw['status']=='DRAFT_NOT_EXECUTABLE' and raw['humanStatus']=='PENDING_HUMAN' and raw['designSha256']==DESIGN_SHA,'pending_prepare_required')
    require(args.confirm=='CONFIRM_ISOLATED_RENDER:'+args.prepare_sha256,'isolated_render_confirmation_required')
    for path,expected in raw['frozenFiles'].items():
        require(digest(exact_file(BASE/path))==expected,'frozen_input_changed')
    require(args.executor_sha256==raw['frozenFiles']['ops/db/migrate-ordinary-modules.py'],'executor_binding_mismatch')
    plan_permissions(raw['permissions'])
    baseline=baseline_statements(raw['metadata'],TABLES); incremental=ddl_statements()
    require(shutil.disk_usage(BASE).free>=10*1024**3,'low_disk')
    output=args.output_dir
    require(output.is_absolute() and output.parent==BASE/'evidence/ordinary-activation' and not output.exists() and all(not p.is_symlink() for p in output.parents),'fresh_fixed_parent_output_required')
    os.mkdir(output,0o700); fsync_dir(output.parent)
    journal_path=output/'renderer-journal.jsonl'
    exclusive(journal_path,b'')
    schema='print_ordinary_render_'+uuid.uuid4().hex; graphs={}; attempted=False; cleaned=False; failed=False
    with journal_path.open('ab') as journal:
        isolated=Isolated(schema,journal,None); isolated.allowed_structure=baseline+incremental
        isolated.event('RENDER_INTENT',schema=schema,prepareSha256=args.prepare_sha256)
        try:
            require(not isolated.present(),'random_schema_collision')
            attempted=True
            isolated.command('CREATE DATABASE '+identifier(schema)+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci','information_schema','catalog')
            info=json.loads(isolated.command("SELECT JSON_OBJECT('schema',DATABASE(),'serverUuid',@@server_uuid,'version',@@version,'currentUser',CURRENT_USER(),'sqlMode',@@sql_mode)",schema,'identity')[0])
            require(info['schema']==schema and all(info[k]==raw['identity'][k] for k in ('serverUuid','version','currentUser','sqlMode')),'server_version_mode_identity_mismatch')
            for sql in baseline: isolated.command(sql,schema,'structure')
            graphs['G0']=metadata(isolated); require(graphs['G0']==raw['metadata'],'baseline_not_exact')
            for sql,stage in zip(incremental,STAGES[1:]):
                isolated.event(stage+'_DDL_INTENT'); isolated.command(sql,schema,'structure')
                graphs[stage]=metadata(isolated)
                isolated.event(stage+'_DDL_OBSERVED',graphSha256=digest(canon(graphs[stage])))
            for table in TABLES:
                if any(row[0]==table for row in graphs[STAGES[-1]]['tables']):
                    require(isolated.command('SELECT COUNT(*) FROM '+identifier(table),schema,'empty')==['0'],'unexpected_isolated_rows')
        except BaseException:
            failed=True; isolated.event('RENDER_FAILED_NO_DRAFT')
        finally:
            if attempted:
                try:
                    if isolated.present(): isolated.command('DROP DATABASE '+identifier(schema),'information_schema','catalog')
                    cleaned=not isolated.present(); isolated.event('TEMP_SCHEMA_CLEANUP_OBSERVED',schema=schema,absent=cleaned)
                except BaseException: isolated.event('TEMP_SCHEMA_CLEANUP_UNKNOWN',schema=schema)
        require(not failed and cleaned,'render_or_cleanup_not_proven')
        require(digest(exact_file(args.prepare,True))==args.prepare_sha256,'prepare_changed_during_render')
        for path,expected in raw['frozenFiles'].items(): require(digest(exact_file(BASE/path))==expected,'source_changed_during_render')
        proof={'kind':'AUTHORITATIVE_ISOLATED_RENDER','prepareSha256':args.prepare_sha256,'rendererSha256':digest(exact_file(pathlib.Path(__file__).resolve())),'metadataReaderSha256':raw['frozenFiles']['ops/db/ordinary_activation_common.py'],'schema':schema,'temporarySchemaRemoved':True,'sourceSchemaConnected':False,'sourceSchemaRead':False,'sourceSchemaWritten':False,'baselineExactMatch':True,'connectionsBySchema':dict(isolated.connections),'stageSha256':{s:digest(canon(g)) for s,g in graphs.items()},'normalization':'FK schema coordinate only; no other metadata rewriting'}
        draft=dict(raw,metadataStages=graphs,metadataProvenance=proof,metadataStagesStatus='AUTHORITATIVE_ISOLATED_RENDERED_DRAFT',runtimeAdapters='SKELETON_NOT_EXECUTABLE')
        for name,value in (('metadata-graphs.json',graphs),('isolation-proof.json',proof),('activation-plan.draft.json',draft)):
            exclusive(output/name,canon(value)+b'\n')
        isolated.event('DRAFT_FROZEN',status='DRAFT_NOT_EXECUTABLE')
    exclusive(output/'sha256.json',canon({p.name:digest(exact_file(p,True)) for p in output.iterdir()})+b'\n')
    return {'status':'DRAFT_NOT_EXECUTABLE','humanStatus':'PENDING_HUMAN','temporarySchemaRemoved':True}
def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--render-draft',action='store_true')
    p.add_argument('--prepare',type=pathlib.Path); p.add_argument('--prepare-sha256'); p.add_argument('--executor-sha256'); p.add_argument('--output-dir',type=pathlib.Path); p.add_argument('--confirm')
    a=p.parse_args()
    if not a.render_draft: print('PENDING_HUMAN / NOT_RENDERED'); return
    raise ValueError('SOURCE_ONLY_V3_ISOLATED_ACTION_REVIEW_REQUIRED')
    require(all((a.prepare,a.prepare_sha256,a.executor_sha256,a.output_dir,a.confirm)),'exact_render_inputs_required')
    print(json.dumps(render(a)))
if __name__=='__main__':
    try: main()
    except Exception as e:
        print(json.dumps({'status':'NO_EXECUTABLE_PLAN','exceptionClass':type(e).__name__}),file=sys.stderr); sys.exit(1)
