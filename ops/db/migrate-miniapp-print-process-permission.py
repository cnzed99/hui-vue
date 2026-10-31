#!/usr/bin/env python3
"""Grant only print:order:process to the demo tenant administrator after reviewed backup."""
import argparse, datetime, fcntl, json, pathlib, shutil, subprocess

BASE = pathlib.Path(__file__).resolve().parents[2]
MENU_ID = 2009
PERMISSION = 'print:order:process'

def sql(value):
    result = subprocess.run(['sudo', '-n', 'mysql', '-N', '-B', 'print_demo'], input=value,
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('permission_sql_failed')
    return result.stdout.strip()

def guard():
    row = sql(f"SELECT CONCAT(permission,'|',type,'|',parent_id,'|',status,'|',CAST(deleted AS UNSIGNED)) "
              f"FROM system_menu WHERE id={MENU_ID};")
    if row and row != PERMISSION + '|3|2000|0|0':
        raise RuntimeError('menu_id_conflict')
    if sql(f"SELECT COUNT(*) FROM system_menu WHERE permission='{PERMISSION}' AND id<>{MENU_ID};") != '0':
        raise RuntimeError('permission_conflict')
    for code in ('tenant_admin', 'print_reader'):
        if sql(f"SELECT COUNT(*) FROM system_role WHERE tenant_id=1 AND code='{code}' "
               "AND deleted=0 AND status=0;") != '1':
            raise RuntimeError('role_missing')
    if sql("SELECT COUNT(*) FROM system_tenant WHERE id=1 AND package_id=1 AND deleted=0;") != '1':
        raise RuntimeError('tenant_missing')
    if sql("SELECT COUNT(*) FROM system_tenant_package WHERE id=1 AND deleted=0;") != '1':
        raise RuntimeError('package_missing')

def metrics():
    return sql(f"SELECT GROUP_CONCAT(r.code ORDER BY r.code) FROM system_role_menu rm "
               "JOIN system_role r ON r.id=rm.role_id AND r.tenant_id=rm.tenant_id "
               f"WHERE rm.tenant_id=1 AND rm.deleted=0 AND rm.menu_id={MENU_ID} "
               "AND r.code IN ('tenant_admin','print_reader');")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if not args.apply:
        guard()
        print(json.dumps({'status': 'NOT_APPLIED', 'permission': PERMISSION}))
        return
    if shutil.disk_usage(BASE).free < 10 * 1024 ** 3:
        raise RuntimeError('low_disk')
    lock_path = BASE / 'runtime/locks/fixture-mutation.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        guard()
        backup = subprocess.run(['python3', 'ops/backup/backup_verify.py'], cwd=BASE,
                                capture_output=True)
        if backup.returncode:
            raise RuntimeError('backup_failed')
        source = f"""START TRANSACTION;
INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible)
SELECT {MENU_ID},'Process miniapp print order','{PERMISSION}',3,9,2000,'',0,b'0'
WHERE NOT EXISTS(SELECT 1 FROM system_menu WHERE id={MENU_ID});
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',{MENU_ID})
WHERE id=1 AND NOT JSON_CONTAINS(menu_ids,'{MENU_ID}');
INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,{MENU_ID},1,'miniapp-m4','miniapp-m4' FROM system_role r
WHERE r.tenant_id=1 AND r.code='tenant_admin' AND r.deleted=0
AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.tenant_id=1
  AND rm.role_id=r.id AND rm.menu_id={MENU_ID});
COMMIT;"""
        sql(source)
        first = metrics()
        guard()
        sql(source)
        second = metrics()
        if first != 'tenant_admin' or second != first:
            raise RuntimeError('grant_verification_failed')
        if sql(f"SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id "
               f"AND r.tenant_id=rm.tenant_id WHERE rm.tenant_id=1 AND r.code='print_reader' "
               f"AND rm.menu_id={MENU_ID} AND rm.deleted=0;") != '0':
            raise RuntimeError('reader_write_grant')
        report = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'permission': PERMISSION, 'adminGranted': True,
                  'readerDeniedBySeed': True, 'idempotentSecondApplication': True,
                  'preMigrationBackupVerified': True, 'runtimeAcceptance': 'NOT_RUN'}
        output = BASE / 'evidence/database/miniapp-print-process-permission.json'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report))

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(json.dumps({'failed': True, 'exceptionClass': type(exc).__name__}))
        raise SystemExit(1)
