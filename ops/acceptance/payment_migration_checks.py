#!/usr/bin/env python3
"""Offline payment migration regressions. Never invokes SQL, backup, build or deployment."""
import ast
import copy
import datetime
import hashlib
import importlib.util
import json
import pathlib
import re
import sys
import tempfile
from types import SimpleNamespace
from contextlib import redirect_stdout
import io
from unittest.mock import patch

sys.dont_write_bytecode = True
BASE = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = BASE / 'ops/db/migrate-print-payments.py'
SCHEMA = BASE / 'ops/db/print-payments-v1.sql'
OUTPUT = BASE / 'evidence/acceptance/payment-migration-static-v2.json'

# Exact CHECK_CLAUSE representations captured during the failed A-to-B preflight.
# These are schema expressions, not database rows, and require no live connection.
REAL_A_ORDER = r"((`order_state` = _utf8mb4\'OPEN\') and (`payment_state` = _utf8mb4\'UNPAID\') and (`refund_state` = _utf8mb4\'NONE\') and (`delivery_mode` = _utf8mb4\'NONE\') and (`delivery_state` = _utf8mb4\'NOT_APPLICABLE\') and (`print_state` = _utf8mb4\'NOT_CREATED\') and (`version` = 1))"
EXPECTED_A_ORDER = "order_state='OPEN' AND payment_state='UNPAID' AND refund_state='NONE' AND delivery_mode='NONE' AND delivery_state='NOT_APPLICABLE' AND print_state='NOT_CREATED' AND version=1"
REAL_A_EVENT = r"((`event_type` = _utf8mb4\'ORDER_CREATED\') and (`aggregate_version` = 1) and (`simulation` = 0x01))"
EXPECTED_A_EVENT = "event_type='ORDER_CREATED' AND aggregate_version=1 AND simulation=b'1'"

# Actual B CHECK_CLAUSE captured by a read-only information_schema SELECT on
# 2026-10-23. This fixture is replayed offline, not read from a business table.
REAL_B_EVENT = r"((`simulation` = 0x01) and (((`event_type` = _ascii\'ORDER_CREATED\') and (`aggregate_version` = 1)) or ((`event_type` = _ascii\'PAYMENT_SIMULATED\') and (`aggregate_version` = 2))))"
REAL_B_ORDER = r"(((`order_state` = _ascii\'OPEN\') and (`payment_state` = _ascii\'UNPAID\') and (`refund_state` = _ascii\'NONE\') and (`delivery_mode` = _ascii\'NONE\') and (`delivery_state` = _ascii\'NOT_APPLICABLE\') and (`print_state` = _ascii\'NOT_CREATED\') and (`version` = 1)) or ((`order_state` = _ascii\'OPEN\') and (`payment_state` = _ascii\'SUCCEEDED\') and (`refund_state` = _ascii\'NONE\') and (`delivery_mode` = _ascii\'NONE\') and (`delivery_state` = _ascii\'NOT_APPLICABLE\') and (`print_state` = _ascii\'QUEUED\') and (`version` = 2)))"


def main():
    results = []
    metadata_queries = []
    def forbidden_process(*args, **kwargs):
        raise AssertionError('External process forbidden: no database/DDL/backup/build may run')

    # Also blocks the legacy migration's subprocess namespace, even if this test is changed later.
    with patch('subprocess.run', forbidden_process):
        spec = importlib.util.spec_from_file_location('payment_migration_under_test', MIGRATION)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        def check(name, operation):
            try:
                operation()
                results.append({'name': name, 'passed': True})
            except Exception as error:
                results.append({'name': name, 'passed': False, 'error': str(error)})
        def equal(left, right):
            if module.checknorm(left) != module.checknorm(right):
                raise AssertionError('CHECK expressions should normalize equally')
        def unequal(left, right):
            if module.checknorm(left) == module.checknorm(right):
                raise AssertionError('Different SQL expressions must remain different')

        for column, expected, source in [
            ('delivery_state', 'NOT_APPLICABLE', REAL_A_ORDER),
            ('print_state', 'NOT_CREATED', REAL_A_ORDER),
            ('event_type', 'ORDER_CREATED', REAL_A_EVENT),
        ]:
            expression = re.search(r'`' + column + r'`\s*=\s*[^)]+', source).group()
            check('real_A_literal_' + column, lambda e=expression, c=column, v=expected: equal(e, c + "='" + v + "'"))
        check('real_A_complete_order_constraint', lambda: equal(REAL_A_ORDER, EXPECTED_A_ORDER))
        check('real_A_complete_event_constraint', lambda: equal(REAL_A_EVENT, EXPECTED_A_EVENT))
        check('ordinary_charset_literal', lambda: equal("x=_utf8mb4'NOT_APPLICABLE'", "x='NOT_APPLICABLE'"))
        check('bit_literal_metadata_hex', lambda: equal("simulation=b'1'", 'simulation=0x01'))
        check('SQL_quote_escape_equivalence', lambda: equal("x='it\\'s'", "x='it''s'"))
        for name, left, right in [
            ('retain_underscore_suffix', "x='NOT_APPLICABLE'", "x='NOT'"),
            ('retain_event_suffix', "x='ORDER_CREATED'", "x='ORDER'"),
            ('retain_literal_case', "x='OPEN'", "x='open'"),
            ('retain_literal_whitespace', "x='A B'", "x='AB'"),
            ('retain_literal_parentheses', "x='A(B)'", "x='AB'"),
            ('retain_charset_looking_literal_suffix', "x='prefix_utf8mb4'", "x='prefix'"),
        ]:
            check(name, lambda a=left, b=right: unequal(a, b))

        check('real_B_complete_order_constraint', lambda: equal(REAL_B_ORDER, module.ORDER_CHECK))
        check('real_B_complete_event_constraint', lambda: equal(REAL_B_EVENT, module.EVENT_CHECK))
        check('accept_redundant_parentheses_and_associative_grouping', lambda: equal('(a=1 AND (b=2 AND c=3))', '((a)=1) AND b=2 AND (c=3)'))
        bad_event = "(simulation=1 AND event_type='ORDER_CREATED' AND aggregate_version=1) OR (event_type='PAYMENT_SIMULATED' AND aggregate_version=2)"
        check('reject_real_P1_simulation_bypass_precedence', lambda: unequal(module.EVENT_CHECK, bad_event))
        def evaluate(node, values):
            if node[0]=='word': return values[node[1]]
            if node[0] in ('number','string'): return node[1]
            if node[0]=='and': return all(evaluate(n,values) for n in node[1:])
            if node[0]=='or': return any(evaluate(n,values) for n in node[1:])
            if node[:2]==('compare','='): return evaluate(node[2],values)==evaluate(node[3],values)
            raise AssertionError('Unexpected counterexample AST')
        def concrete_counterexample():
            row={'simulation':0,'event_type':'PAYMENT_SIMULATED','aggregate_version':2}
            if evaluate(module.checknorm(module.EVENT_CHECK),row) or not evaluate(module.checknorm(bad_event),row):
                raise AssertionError('Real precedence bypass counterexample not demonstrated')
        check('simulation_zero_payment_event_truth_table_counterexample', concrete_counterexample)
        def rejects_syntax(value):
            try: module.checknorm(value)
            except RuntimeError: return
            raise AssertionError('Unknown SQL grammar must fail closed')
        for name, expression in [
            ('function','abs(x)=1'), ('comment','x=1 /* ignored */'),
            ('arithmetic','x+1=2'), ('between','x BETWEEN 1 AND 2'),
            ('unsupported_charset',"x=_latin1'OPEN'"), ('trailing','x=1 garbage'),
            ('unbalanced','(x=1'), ('quoted_keyword',"x=1 'or' y=2"),
        ]:
            check('fail_closed_' + name, lambda e=expression: rejects_syntax(e))
        for name,left,right in [
            ('case', "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='OPEN'", "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='open'"),
            ('spaces', "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A B'", "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A  B'"),
            ('parentheses', "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A(B)'", "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AB'"),
        ]:
            def trigger_unequal(a=left,b=right):
                if module.norm(a)==module.norm(b): raise AssertionError('Trigger literal changed')
            check('trigger_preserves_literal_' + name, trigger_unequal)
        def trigger_syntax_equivalent():
            if module.norm("BEFORE|UPDATE|`print_order`| SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A B'") != module.norm("before|update|print_order|signal   sqlstate '45000' set message_text = 'A B'"):
                raise AssertionError('Equivalent trigger SQL syntax differs')
        check('trigger_canonicalizes_only_outside_literals', trigger_syntax_equivalent)

        # Build baseline metadata from the declared SQL, not the verifier's FK constant.
        # Negative fixtures below deliberately retain FK count while changing its meaning.
        declared = {}
        check_definitions = {}
        for table, body in re.findall(r'CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\n\) ENGINE=', SCHEMA.read_text(), re.S):
            indexes = {'PRIMARY': 'id'}
            indexes.update(dict(re.findall(r'UNIQUE KEY (\w+)\(([^)]+)\)', body)))
            table_checks = dict(re.findall(r'CONSTRAINT (\w+) CHECK\((.*)\)[,\n]', body + '\n'))
            check_definitions[table] = table_checks
            foreign_keys = []
            for name, columns, target, target_columns in re.findall(r'CONSTRAINT (\w+) FOREIGN KEY\(([^)]+)\) REFERENCES (\w+)\(([^)]+)\)', body):
                for ordinal, (column, target_column) in enumerate(zip(columns.split(','), target_columns.split(',')), 1):
                    foreign_keys.append([name, str(ordinal), column, 'print_demo', target, target_column, 'RESTRICT', 'RESTRICT'])
            declared[table] = {
                'engine': 'InnoDB',
                'indexes': {name: [c + '|FULL|BTREE|YES' for c in columns.split(',')] for name, columns in indexes.items()},
                'checks': [name + '|YES' for name in sorted(table_checks)],
                'fk': sorted(foreign_keys, key=lambda row: (row[0], int(row[1]))),
            }
        if len(declared) != 3 or sum(map(len, check_definitions.values())) != 10:
            raise AssertionError('Offline SQL fixture extraction changed; review test fixtures')
        state = copy.deepcopy(declared)
        def mock_sql(query):
            if not query.startswith('SELECT '):
                raise AssertionError('Non-read metadata request forbidden in mock: no DDL')
            metadata_queries.append(query)
            if 'CHECK_CLAUSE' in query:
                name = re.search("CONSTRAINT_NAME='([^']+)'", query).group(1)
                return next('print_demo|print_demo|' + table + '|CHECK|YES|' + text for table, checks in check_definitions.items() for key, text in checks.items() if key == name)
            table = re.search("(?:TABLE_NAME|r.TABLE_NAME)='([^']+)'", query).group(1)
            fixture = state[table]
            if 'SELECT ENGINE' in query:
                return fixture['engine']
            if 'information_schema.STATISTICS' in query:
                name = re.search("INDEX_NAME='([^']+)'", query).group(1)
                return '\n'.join(fixture['indexes'].get(name, []))
            if 'information_schema.TABLE_CONSTRAINTS' in query:
                return '\n'.join(fixture['checks'])
            if 'REFERENTIAL_CONSTRAINTS' in query:
                return '\n'.join('|'.join(row) for row in fixture['fk'])
            raise AssertionError('Unexpected metadata query')
        module.sql = mock_sql
        def verify_tables():
            for table in state:
                module.require_table_constraints(table, check_definitions[table])
        def reject_mutation(mutate, expected_error):
            nonlocal state
            state = copy.deepcopy(declared)
            mutate(state)
            try:
                verify_tables()
            except RuntimeError as error:
                if str(error) != expected_error:
                    raise AssertionError('Unexpected rejection: ' + str(error))
                return
            raise AssertionError('Defective schema was accepted')

        check('declared_SQL_complete_constraints_pass', verify_tables)
        bad_index = 'payment_unique_index_verification_failed'
        check('reject_missing_primary_key', lambda: reject_mutation(lambda s: s['print_payment_receipt']['indexes'].pop('PRIMARY'), bad_index))
        check('reject_missing_idempotency_unique_index', lambda: reject_mutation(lambda s: s['print_payment_receipt']['indexes'].pop('uk_print_payment_idempotency'), bad_index))
        check('reject_prefix_unique_index', lambda: reject_mutation(lambda s: s['print_payment_receipt']['indexes']['uk_print_payment_order'].__setitem__(0, 'order_id|8|BTREE|YES'), bad_index))
        check('reject_nontransactional_engine', lambda: reject_mutation(lambda s: s['print_job'].__setitem__('engine', 'MyISAM'), 'payment_table_engine_mismatch'))
        check('reject_missing_CHECK', lambda: reject_mutation(lambda s: s['print_job']['checks'].pop(), 'payment_table_check_set_mismatch'))
        check('reject_unenforced_CHECK', lambda: reject_mutation(lambda s: s['print_job']['checks'].__setitem__(0, s['print_job']['checks'][0].replace('|YES', '|NO')), 'payment_table_check_set_mismatch'))
        for name, column, value in [
            ('wrong_source_column', 2, 'wrong_column'), ('wrong_target_schema', 3, 'other_schema'),
            ('wrong_target_table', 4, 'other_table'), ('wrong_target_column', 5, 'other_id'),
            ('cascade_update', 6, 'CASCADE'), ('cascade_delete', 7, 'CASCADE'),
            ('set_null_delete', 7, 'SET NULL'),
        ]:
            check('reject_same_count_FK_' + name, lambda c=column, v=value: reject_mutation(lambda s: s['print_job']['fk'][0].__setitem__(c, v), 'payment_foreign_key_contract_mismatch'))
        check('reject_missing_FK_member', lambda: reject_mutation(lambda s: s['print_job']['fk'].pop(), 'payment_foreign_key_contract_mismatch'))
        check('reject_additional_FK', lambda: reject_mutation(lambda s: s['print_job']['fk'].append(['unreviewed_fk', '1', 'file_id', 'print_demo', 'print_file', 'id', 'RESTRICT', 'RESTRICT']), 'payment_foreign_key_contract_mismatch'))
        state = copy.deepcopy(declared)
        for fixture in state.values():
            for row in fixture['fk']:
                row[-2:] = ['NO ACTION', 'NO ACTION']
        check('accept_equivalent_InnoDB_NO_ACTION', verify_tables)

        def check_identity_rejected(field, value):
            record=['print_demo','print_demo','print_order_event','CHECK','YES',module.EVENT_CHECK]
            record[field]=value
            with patch.object(module,'check_state',return_value='|'.join(record)):
                try: module.require_check('chk_print_order_event_payment_v2','print_order_event',module.EVENT_CHECK)
                except RuntimeError as error:
                    if str(error)=='payment_check_identity_or_enforcement_mismatch': return
                    raise
            raise AssertionError('Wrong CHECK identity/enforcement accepted')
        for field,value in [(0,'other'),(1,'other'),(2,'print_order'),(3,'UNIQUE'),(4,'NO')]:
            check('reject_CHECK_identity_or_enforcement_field_' + str(field), lambda f=field,v=value: check_identity_rejected(f,v))

        # Execute actual main against controlled in-memory contracts. Every SQL,
        # backup and lock call is replaced. No external process/database is used.
        def run_payment_main(bad_name=None, bad_boundary='preflight'):
            protections={**module.OLD_CHECKS, **module.CHECKS}
            records={name:'print_demo|print_demo|'+table+'|CHECK|YES|'+text for name,(table,text) in protections.items()}
            calls=[]; boundary='preflight'
            def state_for_check(name):
                value=records.get(name,'')
                if bad_name==name and boundary==bad_boundary:
                    value=value.replace('|CHECK|YES|','|CHECK|NO|')
                return value
            def fake_sql(source):
                nonlocal boundary
                if source.startswith('SELECT COUNT(*)'):return '0'
                calls.append(('mock_ddl',source))
                if source==module.SCHEMA.read_text(): boundary='before_removal'
                if source=='DROP TRIGGER trg_print_order_immutable;': boundary='final'
                if 'DROP CHECK' in source:
                    name=re.search(r'DROP CHECK (\w+)',source).group(1);records.pop(name)
                return ''
            def fake_backup(*args,**kwargs):
                calls.append(('mock_backup',''));return SimpleNamespace(returncode=0)
            def fake_triggers(name):
                values={**module.legacy.TRIGGERS,**module.TRIGGERS}
                event,table,text=values[name]
                return 'BEFORE|'+event+'|'+table+'|'+text
            with tempfile.TemporaryDirectory(prefix='payment-migration-mock-') as temp:
                with patch.object(module,'BASE',pathlib.Path(temp)), patch.object(sys,'argv',['test','--apply']), \
                     patch.object(module.shutil,'disk_usage',return_value=SimpleNamespace(free=20*1024**3)), \
                     patch.object(module.fcntl,'flock',return_value=None), \
                     patch.object(module,'column_names',side_effect=lambda t:module.legacy.TABLES[t]), \
                     patch.object(module,'trigger_state',side_effect=fake_triggers), \
                     patch.object(module,'check_state',side_effect=state_for_check), \
                     patch.object(module,'inspect_columns',side_effect=lambda t:module.schema_columns()[t]), \
                     patch.object(module,'require_table_constraints',return_value=None), \
                     patch.object(module,'require_payment_tables',return_value=None), \
                     patch.object(module,'sql',side_effect=fake_sql), \
                     patch.object(module.subprocess,'run',side_effect=fake_backup), redirect_stdout(io.StringIO()):
                    try: module.main()
                    except RuntimeError as error:
                        if str(error)!='payment_check_identity_or_enforcement_mismatch':raise
                        if bad_boundary=='preflight' and calls:raise AssertionError('Backup/DDL ran before CHECK rejection')
                        if bad_boundary=='before_removal' and any('DROP' in source for _,source in calls):
                            raise AssertionError('Old protection removed before CHECK rejection')
                        if bad_boundary=='final' and not any('DROP CHECK' in source for _,source in calls):
                            raise AssertionError('Final-enforcement fixture did not reach final verification')
                        return
            raise AssertionError('Expected NOT ENFORCED CHECK rejection')
        for name in [*module.OLD_CHECKS,*module.CHECKS]:
            check('prebackup_zero_DDL_reject_NOT_ENFORCED_' + name, lambda n=name:run_payment_main(n))
            check('before_removal_reject_NOT_ENFORCED_' + name, lambda n=name:run_payment_main(n,'before_removal'))

        for name in module.CHECKS:
            check('final_reject_NOT_ENFORCED_' + name, lambda n=name:run_payment_main(n,'final'))

        def legacy_interleaving():
            legacy=module.legacy; phase='A_precheck';trace=[]
            def fake_state(name):
                trace.append(('read',phase,name))
                return '' if phase=='A_precheck' else 'B_installed_guard'
            def fake_lock(*args):
                nonlocal phase
                # A saw no B. B obtains/releases the shared lock and installs its
                # v2 guard. A then obtains the same lock and must re-read it.
                trace.append(('B_migrates_then_A_takes_lock',));phase='A_locked'
            def forbidden_sql(query):raise AssertionError('A issued SQL/DDL after B superseded it')
            with tempfile.TemporaryDirectory(prefix='legacy-migration-mock-') as temp:
                with patch.object(legacy,'BASE',pathlib.Path(temp)), patch.object(sys,'argv',['test','--apply']), \
                     patch.object(legacy.shutil,'disk_usage',return_value=SimpleNamespace(free=20*1024**3)), \
                     patch.object(legacy.fcntl,'flock',side_effect=fake_lock), \
                     patch.object(legacy,'trigger_state',side_effect=fake_state), \
                     patch.object(legacy,'sql',side_effect=forbidden_sql), redirect_stdout(io.StringIO()) as out:
                    legacy.main()
            expected=[('read','A_precheck','trg_print_order_update_guard_v2'),('B_migrates_then_A_takes_lock',),('read','A_locked','trg_print_order_update_guard_v2')]
            if trace!=expected or 'SUPERSEDED_BY_PAYMENT_V2' not in out.getvalue():
                raise AssertionError('A did not stop under lock after the controlled B interleaving')
        check('A_B_controlled_interleaving_superseded_zero_backup_DDL_no_guard_reinstall',legacy_interleaving)
        def default_never_connects():
            for target in (module,module.legacy):
                with patch.object(sys,'argv',['test']), patch.object(target,'sql',side_effect=AssertionError('Default accessed DB')), \
                     patch.object(target,'trigger_state',side_effect=AssertionError('Default accessed metadata')), redirect_stdout(io.StringIO()) as out:
                    target.main()
                if 'NOT_APPLIED' not in out.getvalue():raise AssertionError('Missing default NOT_APPLIED result')
        check('both_defaults_NOT_APPLIED_zero_database_or_process_calls',default_never_connects)

        def migration_ordering():
            # Structural check of actual main: do not invoke locks, backup or DDL.
            tree = ast.parse(MIGRATION.read_text())
            main_node = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'main')
            block = next(node for node in main_node.body if isinstance(node, ast.With)).body
            statements = [ast.unparse(node) for node in block]
            preflight = next(i for i, text in enumerate(statements) if 'current = inspect_columns(table)' in text)
            backup = next(i for i, text in enumerate(statements) if 'backup = subprocess.run' in text)
            create = statements.index('sql(SCHEMA.read_text())')
            verify = [i for i, text in enumerate(statements) if text == 'require_payment_tables(expected_columns, new_checks)']
            drop = next(i for i, text in enumerate(statements) if 'DROP CHECK' in text)
            if 'require_table_constraints(table, new_checks[table])' not in statements[preflight]:
                raise AssertionError('Existing tables are not fully checked before DDL')
            replacement = [i for i,text in enumerate(statements) if 'require_check(name, table, statement)' in text and 'CHECKS.items()' in text and 'OLD_CHECKS.items()' not in text]
            old = [i for i,text in enumerate(statements) if 'require_check(name, table, statement, optional=True)' in text and 'OLD_CHECKS.items()' in text]
            if not any(verify[0] < i < drop for i in replacement) or not any(verify[0] < i < drop for i in old):
                raise AssertionError('Order/event old and replacement CHECKs must be reverified before drop')
            if not replacement[-1] > drop:
                raise AssertionError('Final replacement CHECK verification is missing')
            if not (preflight < backup < create < verify[0] < drop < verify[1]):
                raise AssertionError('Full constraint verification must precede removal of A protection')
        check('preflight_and_complete_verification_precede_old_protection_removal', migration_ordering)

    report = {
        'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'kind': 'isolated_mock_and_static_control_flow',
        'migration_sha256': hashlib.sha256(MIGRATION.read_bytes()).hexdigest(),
        'schema_sha256': hashlib.sha256(SCHEMA.read_bytes()).hexdigest(),
        'legacy_migration_sha256': hashlib.sha256((BASE / 'ops/db/migrate-print-orders.py').read_bytes()).hexdigest(),
        'test_sha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
        'total': len(results), 'passed': sum(item['passed'] for item in results),
        'database_access': False, 'ddl_executed': False, 'external_processes_executed': False,
        'mock_metadata_queries': len(metadata_queries),
        'limits': 'Uses captured schema expressions and mocked information_schema. Does not replace real migration, transaction, HTTP or browser acceptance.',
        'checks': results,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'total': report['total'], 'database_access': False, 'ddl_executed': False, 'evidence': str(OUTPUT)}, ensure_ascii=False))
    if report['passed'] != report['total']:
        for result in results:
            if not result['passed']:
                print(result['name'] + ': ' + result['error'], file=sys.stderr)
        raise SystemExit(1)

if __name__ == '__main__':
    main()
