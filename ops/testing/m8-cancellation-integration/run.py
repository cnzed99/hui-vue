#!/usr/bin/env python3
"""Actual HTTP/Java/MySQL M8 checks in a disposable clone; never uses runtime writes."""
import argparse
import concurrent.futures
import datetime
import hashlib
import http.client
import json
import os
import pathlib
import re
import secrets
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path('/home/ubuntu/print-system')
ENG = ROOT / 'runtime/worktrees/m8-cancel-refund-engineering-20261102'
BACKEND = ROOT / 'runtime/worktrees/m8-cancel-refund-backend-20261102'


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, (int, float)):
        return str(value)
    return 'CONVERT(0x' + str(value).encode().hex() + ' USING utf8mb4)'


class SqlSession:
    def __init__(self, database):
        self.proc = subprocess.Popen(['sudo', '-n', 'mysql', '-N', '-B', '--raw', '--unbuffered', database],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     text=True, bufsize=1)

    def execute(self, statement):
        marker = 'marker_' + secrets.token_hex(8)
        self.proc.stdin.write(statement + '\nSELECT ' + literal(marker) + ';\n')
        self.proc.stdin.flush()
        lines = []
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError('persistent_mysql_session_failed')
            if line.strip() == marker:
                return lines
            lines.append(line.rstrip('\n'))

    def close(self):
        if self.proc.poll() is None:
            try:
                self.execute('ROLLBACK;')
                self.proc.stdin.close()
                self.proc.wait(timeout=5)
            except Exception:
                self.proc.terminate()
                self.proc.wait(timeout=5)


class Harness:
    def __init__(self, args):
        self.args = args
        self.database = 'print_m8_it_' + str(int(time.time())) + '_' + secrets.token_hex(3)
        self.db_user = 'm8it_' + secrets.token_hex(5)
        self.db_password = secrets.token_hex(24)
        self.redis_password = secrets.token_hex(24)
        self.output = pathlib.Path(args.output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.jar = pathlib.Path(args.jar).resolve()
        self.app = None
        self.redis = None
        self.logs = []
        self.sessions = []
        self.checks = []
        self.timeline = []
        self.origin = 'http://127.0.0.1:18080'
        self.port = 49180
        self.redis_port = 16389
        self.admin_token = secrets.token_hex(16)
        self.base_env = dict(os.environ)
        self.started = time.time()
        self.jar_sha256 = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.source_head = subprocess.check_output(['git', '-C', str(BACKEND), 'rev-parse', 'HEAD'], text=True).strip()
        if self.source_head != args.expected_head:
            raise RuntimeError('backend_head_mismatch')
        if self.jar_sha256 != args.expected_jar_sha256:
            raise RuntimeError('backend_jar_hash_mismatch')
        self.scratch = pathlib.Path(tempfile.mkdtemp(prefix='m8-integration-', dir=ROOT / 'runtime/scratch'))
        self.scratch.chmod(0o700)

    def sql(self, statement, database=True, allow_error=False):
        command = ['sudo', '-n', 'mysql', '--default-character-set=utf8mb4', '-N', '-B', '--raw']
        if database:
            command.append(self.database)
        result = subprocess.run(command, input=statement, text=True, capture_output=True)
        if result.returncode and not allow_error:
            raise RuntimeError('mysql_operation_failed_no_secret_output')
        return result

    def scalar(self, statement):
        return self.sql(statement).stdout.strip()

    def record(self, name, **facts):
        self.checks.append({'name': name, 'result': 'PASS', **facts})
        print('PASS ' + name, flush=True)

    def check(self, condition, name):
        if not condition:
            raise AssertionError(name)

    def setup(self):
        self.check(shutil.disk_usage(ROOT).free >= 10 * 1024**3, 'disk_before')
        self.sql('CREATE DATABASE `' + self.database + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;', False)
        dump = self.scratch / 'clone.sql'
        with dump.open('wb') as handle:
            clone = subprocess.run(['sudo', '-n', 'mysqldump', '--single-transaction', '--routines', '--triggers', 'print_demo'], stdout=handle, stderr=subprocess.DEVNULL)
        self.check(clone.returncode == 0, 'clone_dump')
        with dump.open('rb') as handle:
            imported = subprocess.run(['sudo', '-n', 'mysql', self.database], stdin=handle, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.check(imported.returncode == 0, 'clone_import')
        dump.unlink()
        with (ENG / 'ops/db/print-miniapp-demo-cancellation-refund-v1.sql').open('rb') as handle:
            applied = subprocess.run(['sudo', '-n', 'mysql', self.database], stdin=handle, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.check(applied.returncode == 0, 'clone_m8_migration')
        self.sql("CREATE USER '" + self.db_user + "'@'127.0.0.1' IDENTIFIED BY '" + self.db_password + "'; GRANT SELECT,INSERT,UPDATE,DELETE ON `" + self.database + "`.* TO '" + self.db_user + "'@'127.0.0.1';", False)
        self.sql("INSERT INTO system_oauth2_access_token(user_id,user_type,user_info,access_token,refresh_token,client_id,scopes,expires_time,tenant_id) VALUES(1,2,'{}'," + literal(self.admin_token) + "," + literal(secrets.token_hex(16)) + ",'default','[]',DATE_ADD(NOW(),INTERVAL 1 HOUR),1);")
        raw = self.scalar('SELECT quote_snapshot_json FROM print_miniapp_demo_order_item ORDER BY created_at_ms DESC LIMIT 1;')
        self.snapshot_template = json.loads(raw)
        redis_log = (self.scratch / 'redis.log').open('wb')
        self.logs.append(redis_log)
        self.redis = subprocess.Popen(['redis-server', '--bind', '127.0.0.1', '--port', str(self.redis_port), '--requirepass', self.redis_password,
                                       '--dir', str(self.scratch), '--save', '', '--appendonly', 'no'], stdout=redis_log, stderr=subprocess.STDOUT, start_new_session=True)
        self.start_app(True)
        self.owner = self.ok('POST', '/app-api/print/miniapp/demo/sessions', auth=None)
        self.other = self.ok('POST', '/app-api/print/miniapp/demo/sessions', auth=None)
        self.record('isolated_runtime_started', database_is_disposable=True, app_port=self.port, redis_port=self.redis_port,
                    backend_head=self.source_head, jar_sha256=self.jar_sha256)

    def start_app(self, enabled):
        logging_path = self.scratch / 'logback.xml'
        logging_path.write_text('<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%d %-5level %logger{40} - %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="CONSOLE"/></root></configuration>')
        config = {
            'server': {'address': '127.0.0.1', 'port': self.port},
            'spring': {'datasource': {'dynamic': {'datasource': {'master': {
                'name': self.database, 'url': 'jdbc:mysql://127.0.0.1:3306/' + self.database + '?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true',
                'username': self.db_user, 'password': self.db_password}}, 'druid': {'max-active': 8}}},
                'data': {'redis': {'port': self.redis_port, 'database': 0, 'password': self.redis_password}}},
            'logging': {'config': 'file:' + str(logging_path), 'file': {'name': str(self.scratch / 'backend-file.log')}},
            'print': {'miniapp-demo': {'enabled': True, 'issuance-enabled': True, 'tenant-id': 1, 'store-id': 1,
                'allowed-origin': self.origin, 'edge-host': '127.0.0.1', 'ttl-seconds': 3600,
                'max-active-sessions': 500, 'max-issue-per-minute': 100,
                'orders': {'enabled': True, 'max-orders-per-principal': 100},
                'payments': {'enabled': True}, 'print-tasks': {'enabled': True, 'lease-seconds': 60},
                'cancellations': {'enabled': enabled},
                'files': {'enabled': False, 'storage-root': str(self.scratch / 'files'), 'runtime-jar': str(self.jar)},
                'image': {'enabled': False}, 'text': {'enabled': False}, 'quotes': {'enabled': False}}}}
        config_path = self.scratch / 'override.properties'
        properties = []
        def flatten(path, value):
            if isinstance(value, dict):
                for key, child in value.items():
                    flatten(path + ('.' if path else '') + key, child)
            else:
                properties.append(path + '=' + (str(value).lower() if isinstance(value, bool) else str(value)))
        flatten('', config)
        config_path.write_text('\n'.join(properties) + '\n')
        config_path.chmod(0o600)
        env = dict(self.base_env)
        env['PRINT_RUNTIME_DIR'] = str(self.scratch)
        for key in list(env):
            if key.startswith('PRINT_MINIAPP_DEMO_'):
                del env[key]
        log = (self.scratch / ('app-' + ('enabled' if enabled else 'disabled') + '.log')).open('wb')
        self.logs.append(log)
        self.app = subprocess.Popen(['java', '-Xms64m', '-Xmx512m', '-XX:MaxMetaspaceSize=256m', '-XX:+UseSerialGC', '-jar', str(self.jar),
            '--spring.profiles.active=cloud', '--spring.config.additional-location=file:' + str(ROOT / 'ops/runtime/application-cloud.yaml') + ',file:' + str(config_path)],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        for _ in range(100):
            if self.app.poll() is not None:
                raise RuntimeError('isolated_app_start_failed_no_secret_output')
            try:
                response = self.http('GET', '/admin-api/system/auth/get-permission-info', auth=None)
                if response[0] == 200 and response[1].get('code') in (0, 401):
                    return
            except Exception:
                pass
            time.sleep(0.5)
        raise RuntimeError('isolated_app_health_timeout')

    def stop_app(self):
        if self.app is not None and self.app.poll() is None:
            os.killpg(self.app.pid, signal.SIGTERM)
            try:
                self.app.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(self.app.pid, signal.SIGKILL)
                self.app.wait(timeout=5)

    def http(self, method, path, data=None, auth='owner', key=None, headers=None):
        request_headers = {'Host': '127.0.0.1', 'Origin': self.origin, 'Content-Type': 'application/json'}
        if auth == 'owner':
            request_headers['Authorization'] = 'Bearer ' + self.owner['accessToken']
        elif auth == 'other':
            request_headers['Authorization'] = 'Bearer ' + self.other['accessToken']
        elif auth == 'admin':
            request_headers.update({'Authorization': 'Bearer ' + self.admin_token, 'tenant-id': '1'})
        if key:
            request_headers['Idempotency-Key'] = key
        if headers:
            request_headers.update(headers)
        body = None if data is None else json.dumps(data).encode()
        request = urllib.request.Request('http://127.0.0.1:' + str(self.port) + path, data=body, method=method, headers=request_headers)
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                return response.status, json.loads(response.read()), dict(response.headers)
        except urllib.error.HTTPError as response:
            return response.code, json.loads(response.read()), dict(response.headers)

    def ok(self, method, path, data=None, auth='owner', key=None):
        status, envelope, _ = self.http(method, path, data, auth, key)
        self.check(status == 200 and envelope.get('code') == 0, 'http_success_' + path + '_status_' + str(status) + '_code_' + str(envelope.get('code')))
        return envelope['data']

    def order(self, state='UNPAID'):
        now = int(time.time() * 1000)
        file_id, quote_id = str(uuid.uuid4()), str(uuid.uuid4())
        snapshot = dict(self.snapshot_template)
        snapshot.update(fileId=file_id, createdAtMs=now, expiresAtMs=now + 600000)
        h = secrets.token_hex(32)
        snapshot['fileSha256'] = h
        principal = self.owner['principalId']
        self.sql("INSERT INTO print_miniapp_demo_file(id,tenant_id,store_id,principal_id,idempotency_sha256,content_sha256,display_name,size_bytes,page_count,status,failure_code,storage_key,created_at_ms,updated_at_ms,expires_at_ms,processing_attempts) VALUES(" + ','.join(map(literal, [file_id, 1, 1, principal, secrets.token_hex(32), h, 'integration-fixture.pdf', 512, snapshot['pagesPerCopy'], 'READY', None, str(uuid.uuid4()), now, now, now+3600000, 0])) + ');')
        self.sql('INSERT INTO print_miniapp_demo_quote(id,tenant_id,store_id,principal_id,file_id,price_profile_id,idempotency_sha256,input_sha256,snapshot_json,created_at_ms,expires_at_ms) VALUES(' + ','.join(map(literal, [quote_id, 1, 1, principal, file_id, snapshot['priceProfileId'], secrets.token_hex(32), secrets.token_hex(32), json.dumps(snapshot), now, now+600000])) + ');')
        order = self.ok('POST', '/app-api/print/miniapp/demo/orders', {'quoteId': quote_id}, key='order-' + quote_id)
        if state == 'PAID':
            self.pay(order)
            order = self.get_order(order['id'])
        return order

    def get_order(self, order_id):
        return self.ok('GET', '/app-api/print/miniapp/demo/order?id=' + order_id)

    def pay(self, order):
        return self.http('POST', '/app-api/print/miniapp/demo/payments', {'orderId': order['id'], 'mode': 'DEMO'}, key='pay-' + order['id'])

    def task(self, order):
        return self.ok('POST', '/admin-api/print/miniapp-demo/orders/' + order['id'] + '/print-task', {}, 'admin', 'task-' + order['id'])

    def action(self, task, action, **extra):
        body = {'action': action, 'expectedVersion': task['version'], **extra}
        return self.http('POST', '/admin-api/print/miniapp-demo/print-tasks/' + task['id'] + '/actions', body, 'admin', secrets.token_hex(16))

    def cancellation(self, order, key=None, version=None):
        return self.http('POST', '/app-api/print/miniapp/demo/cancellations', {'orderId': order['id'], 'expectedVersion': order['version'] if version is None else version}, key=key or 'cancel-' + order['id'])

    def read_cancel(self, order_id):
        return self.ok('GET', '/app-api/print/miniapp/demo/cancellation?orderId=' + order_id)

    def assert_refund(self, order_id):
        read = self.read_cancel(order_id)
        result = read['cancellation']
        refund = result['refundReceipt']
        self.check(result['originalResult']['outcome'] == 'CANCELLED_REFUNDED' and refund is not None, 'refund_result')
        self.check(refund['amount'] == result['currentOrder']['totalAmount'] == refund['debit']['amount'] == refund['credit']['amount'], 'refund_amount')
        self.check(refund['debit']['account'] == 'DEMO_STORE' and refund['credit']['account'] == 'DEMO_CUSTOMER', 'reverse_accounts')
        self.check(self.scalar('SELECT COUNT(*) FROM print_miniapp_demo_refund_journal WHERE order_id=' + literal(order_id)) == '2', 'two_refund_journals')
        return result

    def run_basics(self):
        unpaid = self.order()
        first = self.cancellation(unpaid, 'unpaid-idem')
        self.check(first[0] == 200 and first[1]['data']['originalResult']['outcome'] == 'CANCELLED_UNPAID', 'unpaid_cancel')
        self.check(self.pay(unpaid)[1].get('code') != 0, 'cancelled_payment_rejected')
        replay = self.cancellation(unpaid, 'unpaid-idem')
        self.check(replay[1]['data']['originalResult'] == first[1]['data']['originalResult'], 'same_key_replay')
        conflict = self.cancellation(unpaid, 'unpaid-idem', version=2)
        self.check(conflict[0] == 409 and conflict[1]['code'] == 1934005, 'same_key_changed_input_conflict')
        recorded = self.cancellation(unpaid, 'different-key')
        self.check(recorded[0] == 409 and recorded[1]['code'] == 1934006 and recorded[1]['data']['outcome'] == 'CANCELLED_UNPAID', 'different_key_existing')
        self.check(self.http('GET', '/app-api/print/miniapp/demo/cancellation?orderId=' + unpaid['id'], auth='other')[0] == 404, 'cross_owner_hidden')
        self.record('unpaid_idempotency_isolation', replay=True, input_conflict=True, different_key_conflict=True, cross_owner_http=404)

        paid = self.order('PAID')
        cancelled = self.cancellation(paid)
        self.check(cancelled[0] == 200, 'paid_no_task_cancel')
        self.assert_refund(paid['id'])
        self.record('paid_no_task_full_refund', journals=2)

        queued = self.order('PAID')
        task = self.task(queued)
        queued = self.get_order(queued['id'])
        self.check(self.cancellation(queued)[0] == 200, 'queued_cancel')
        self.assert_refund(queued['id'])
        view = self.ok('GET', '/app-api/print/miniapp/demo/print-result?orderId=' + queued['id'])
        self.check(view['state'] == 'CANCELLED' and view['receipt'] is None, 'cancel_task_no_receipt')
        self.check(self.action(task, 'CLAIM')[1].get('code') != 0, 'cancelled_claim_rejected')
        self.record('first_queue_cancel_refund_no_receipt', state='CANCELLED', receipt=None, claim_rejected=True)

        manual = self.order('PAID')
        task = self.task(manual)
        claimed = self.action(task, 'CLAIM')
        self.check(claimed[1].get('code') == 0, 'manual_claim')
        task = claimed[1]['data']['currentTask']
        manual = self.get_order(manual['id'])
        requested = self.cancellation(manual)
        self.check(requested[1]['data']['originalResult']['outcome'] == 'MANUAL_REVIEW_REQUIRED', 'manual_result')
        self.check(self.get_order(manual['id'])['version'] == manual['version'], 'manual_version_unchanged')
        accept = self.action(task, 'ACCEPT', leaseId=task['leaseId'], fence=task['fence'])
        self.check(accept[1].get('code') == 0, 'manual_task_can_advance')
        after = self.read_cancel(manual['id'])
        self.check(after['cancellation']['originalResult']['taskStateBefore'] == 'CLAIMED' and after['currentOrder']['printState'] == 'ACCEPTED' and after['cancellation']['refundReceipt'] is None, 'historical_manual_current_task')
        self.record('manual_review_preserves_task_and_allows_progress', historical='CLAIMED', current='ACCEPTED', refunded=False)

        rollback = self.order('PAID')
        self.sql("CREATE TRIGGER m8_it_fail_refund BEFORE INSERT ON print_miniapp_demo_refund_journal FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='integration forced rollback';")
        failed = self.cancellation(rollback)
        self.sql('DROP TRIGGER m8_it_fail_refund;')
        self.check(failed[0] == 503, 'injected_failure_response')
        self.check(self.get_order(rollback['id'])['version'] == 2 and self.read_cancel(rollback['id'])['cancellation'] is None, 'full_rollback_order')
        for table in ['print_miniapp_demo_cancellation', 'print_miniapp_demo_refund_receipt', 'print_miniapp_demo_refund_journal']:
            self.check(self.scalar('SELECT COUNT(*) FROM ' + table + ' WHERE order_id=' + literal(rollback['id'])) == '0', 'rollback_empty_' + table)
        self.record('mid_write_failure_rolls_back_all', http=503, cancellation_rows=0, refund_receipts=0, refund_journals=0)
        self.saved_cancelled = paid

    def gate(self, table, condition):
        gate_name = 'm8gate_' + secrets.token_hex(5)
        holder = SqlSession(self.database)
        self.sessions.append(holder)
        self.check(holder.execute('SELECT GET_LOCK(' + literal(gate_name) + ',0);') == ['1'], 'gate_acquired')
        self.sql('DELIMITER //\nCREATE TRIGGER m8_it_gate BEFORE UPDATE ON ' + table + ' FOR EACH ROW BEGIN IF ' + condition + ' THEN SET @m8_gate=GET_LOCK(' + literal(gate_name) + ',15); SET @m8_release=RELEASE_LOCK(' + literal(gate_name) + '); END IF; END//\nDELIMITER ;')
        return gate_name, holder

    def wait_pending_gate(self, gate_name):
        for _ in range(100):
            # performance_schema is disabled on this 4 GiB host. PROCESSLIST is an existing
            # read-only source and exposes the actual candidate connection waiting in GET_LOCK.
            pending = self.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB=" + literal(self.database) + " AND STATE='User lock';")
            if int(pending) > 0:
                return
            time.sleep(0.05)
        raise AssertionError('winner_not_waiting_at_gate')

    def race(self, kind, winner):
        order = self.order('UNPAID' if kind == 'payment' else 'PAID')
        task = self.task(order) if kind == 'claim' else None
        order = self.get_order(order['id'])
        if winner == 'cancellation':
            table = 'print_miniapp_demo_order'
            condition = 'OLD.id=' + literal(order['id']) + " AND NEW.order_state='CANCELLED'"
        elif kind == 'payment':
            table = 'print_miniapp_demo_order'
            condition = 'OLD.id=' + literal(order['id']) + " AND NEW.payment_state='SUCCEEDED'"
        elif kind == 'create_task':
            table = 'print_miniapp_demo_order'
            condition = 'OLD.id=' + literal(order['id']) + " AND NEW.print_state='QUEUED'"
        else:
            table = 'print_miniapp_demo_print_task'
            condition = 'OLD.id=' + literal(task['id']) + " AND NEW.state='CLAIMED'"
        gate_name, holder = self.gate(table, condition)
        def rival():
            if kind == 'payment':
                return self.pay(order)
            if kind == 'create_task':
                return self.http('POST', '/admin-api/print/miniapp-demo/orders/' + order['id'] + '/print-task', {}, 'admin', 'race-task-' + order['id'])
            return self.action(task, 'CLAIM')
        first_fn = (lambda: self.cancellation(order)) if winner == 'cancellation' else rival
        second_fn = rival if winner == 'cancellation' else (lambda: self.cancellation(order))
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(first_fn)
            self.wait_pending_gate(gate_name)
            second = pool.submit(second_fn)
            time.sleep(0.15)
            self.check(not first.done() and not second.done(), 'real_overlap_' + kind + '_' + winner)
            holder.execute('SELECT RELEASE_LOCK(' + literal(gate_name) + ');')
            result_first, result_second = first.result(), second.result()
        holder.close()
        self.sql('DROP TRIGGER m8_it_gate;')
        self.check(result_first[1].get('code') == 0, 'winner_success_' + kind + '_' + winner)
        if winner == 'cancellation':
            self.check(result_second[1].get('code') != 0, 'rival_rejected_' + kind)
            self.check(self.get_order(order['id'])['orderState'] == 'CANCELLED', 'cancelled_final_' + kind)
        else:
            self.check(result_second[0] == 409 and result_second[1].get('code') == 1934004, 'stale_version_' + kind)
            self.check(self.read_cancel(order['id'])['cancellation'] is None, 'no_stale_record_' + kind)
        self.record('race_' + kind + '_winner_' + winner, overlap_observed=True,
                    winner_code=result_first[1].get('code'), loser_http=result_second[0], loser_code=result_second[1].get('code'))

    def snapshot_read(self):
        order = self.order('PAID')
        gate_name, holder = self.gate('print_miniapp_demo_order', 'OLD.id=' + literal(order['id']) + " AND NEW.order_state='CANCELLED'")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            write = pool.submit(lambda: self.cancellation(order))
            self.wait_pending_gate(gate_name)
            before = [self.read_cancel(order['id']) for _ in range(6)]
            self.check(all(r['currentOrder']['orderState'] == 'OPEN' and r['cancellation'] is None and r['capability']['action'] == 'CANCEL_AND_REFUND' for r in before), 'rr_old_snapshot')
            admin_before = [self.ok('GET', '/admin-api/print/miniapp-demo/orders/' + order['id'], auth='admin') for _ in range(3)]
            self.check(all(r['orderState'] == 'OPEN' and r['cancellation'] is None for r in admin_before), 'rr_admin_old_snapshot')
            holder.execute('SELECT RELEASE_LOCK(' + literal(gate_name) + ');')
            self.check(write.result()[1].get('code') == 0, 'rr_write_success')
        holder.close()
        self.sql('DROP TRIGGER m8_it_gate;')
        after = [self.read_cancel(order['id']) for _ in range(6)]
        self.check(all(r['currentOrder']['orderState'] == 'CANCELLED' and r['cancellation']['refundReceipt'] is not None and r['capability']['action'] == 'NONE' for r in after), 'rr_new_snapshot')
        admin_after = [self.ok('GET', '/admin-api/print/miniapp-demo/orders/' + order['id'], auth='admin') for _ in range(3)]
        self.check(all(r['orderState'] == 'CANCELLED' and r['cancellation']['refundReceipt'] is not None and r['cancellation']['originalResult']['outcome'] == 'CANCELLED_REFUNDED' for r in admin_after), 'rr_admin_new_snapshot')
        self.record('rr_owner_admin_read_no_torn_state', owner_old_snapshots=len(before), owner_new_snapshots=len(after), admin_old_snapshots=len(admin_before), admin_new_snapshots=len(admin_after))

    def disabled_reads(self):
        self.stop_app()
        self.start_app(False)
        result = self.read_cancel(self.saved_cancelled['id'])
        self.check(result['cancellation']['refundReceipt'] is not None, 'disabled_existing_read')
        fresh = self.order()
        rejected = self.cancellation(fresh)
        self.check(rejected[0] == 404 and rejected[1].get('code') == 1934001, 'disabled_post')
        self.record('disabled_post_keeps_existing_get', existing_refund_read=True, new_write_code=1934001)

    def cleanup(self):
        for session in self.sessions:
            try:
                session.close()
            except Exception:
                pass
        self.stop_app()
        if self.redis is not None and self.redis.poll() is None:
            os.killpg(self.redis.pid, signal.SIGTERM)
            self.redis.wait(timeout=10)
        for handle in self.logs:
            handle.close()
        if re.fullmatch(r'print_m8_it_[0-9]+_[0-9a-f]{6}', self.database):
            self.sql('DROP DATABASE IF EXISTS `' + self.database + '`;', False)
        if re.fullmatch(r'm8it_[0-9a-f]{10}', self.db_user):
            self.sql("DROP USER IF EXISTS '" + self.db_user + "'@'127.0.0.1';", False)
        self.database_removed = self.sql('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=' + literal(self.database), False).stdout.strip() == '0'
        self.db_user_removed = self.sql('SELECT COUNT(*) FROM mysql.user WHERE user=' + literal(self.db_user), False).stdout.strip() == '0'
        self.check(self.database_removed and self.db_user_removed, 'isolated_db_and_user_removed')
        if self.scratch.resolve().parent == (ROOT / 'runtime/scratch').resolve() and self.scratch.name.startswith('m8-integration-'):
            shutil.rmtree(self.scratch)
        self.check(not self.scratch.exists(), 'scratch_removed')
        self.check(shutil.disk_usage(ROOT).free >= 10 * 1024**3, 'disk_after')

    def save(self, result, failure=None):
        report = {'result': result, 'startedUtc': datetime.datetime.fromtimestamp(self.started, datetime.timezone.utc).isoformat(),
                  'finishedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'scope': self.args.scope, 'backendHead': self.source_head,
                  'jarSha256': self.jar_sha256, 'sqlSha256': hashlib.sha256((ENG / 'ops/db/print-miniapp-demo-cancellation-refund-v1.sql').read_bytes()).hexdigest(),
                  'checks': self.checks, 'cleanup': {'scratchRemoved': not self.scratch.exists(), 'appStopped': self.app is None or self.app.poll() is not None,
                  'redisStopped': self.redis is None or self.redis.poll() is not None,
                  'databaseRemoved': getattr(self, 'database_removed', False), 'dbUserRemoved': getattr(self, 'db_user_removed', False)}, 'failure': failure,
                  'boundary': 'Disposable database and local HTTP candidate only; real payments, real printers, WeChat device and user acceptance NOT_RUN'}
        (self.output / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', required=True)
    parser.add_argument('--expected-head', required=True)
    parser.add_argument('--expected-jar-sha256', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--scope', choices=['full', 'snapshot'], default='full')
    args = parser.parse_args()
    os.umask(0o077)
    harness = Harness(args)
    result = 'FAIL'
    failure = None
    try:
        harness.setup()
        if args.scope == 'full':
            harness.run_basics()
            for kind in ['payment', 'create_task', 'claim']:
                for winner in [kind, 'cancellation']:
                    harness.race(kind, winner)
        harness.snapshot_read()
        if args.scope == 'full':
            harness.disabled_reads()
        result = 'PASS'
    except Exception as error:
        failure = type(error).__name__ + ':' + str(error)
        print('FAIL ' + failure, flush=True)
    finally:
        try:
            harness.cleanup()
        except Exception as error:
            result = 'FAIL'
            failure = 'cleanup:' + type(error).__name__
        harness.save(result, failure)
    print('M8_INTEGRATION_' + result, flush=True)
    return 0 if result == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
