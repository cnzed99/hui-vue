#!/usr/bin/env python3
"""One fresh simulated PDF order loop against the local development edge."""
import argparse
import datetime
import importlib.util
import json
import pathlib
import uuid

ROOT = pathlib.Path('/home/ubuntu/print-system')
ENGINEERING = ROOT / 'runtime/worktrees/pdf-source-routing-engineering-20261102'
parser = argparse.ArgumentParser()
parser.add_argument('--evidence', required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('m4', ENGINEERING / 'ops/acceptance/m4_runtime_smoke.py')
m4 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m4)
m4.BACKEND_PORT = m4.EDGE_PORT
run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
report = {'schemaVersion': 1, 'runId': run_id, 'scope': 'fresh PDF upload/quote/order/simulated payment/admin task/simulated receipt/result over HTTP',
          'checks': m4.checks, 'realPayment': 'NOT_RUN', 'realPrinter': 'NOT_RUN', 'wechatDevice': 'NOT_RUN'}
admin = None
stage = 'catalog'
safe_pdf = m4.make_pdf()
pathlib.Path(args.evidence).parent.joinpath('safe-fixture.pdf').write_bytes(safe_pdf)
try:
    catalog = m4.data(m4.request(m4.EDGE_PORT, 'GET', '/app-api/print/miniapp/catalog', app=True))
    m4.record('catalog_pdf_only_and_upload_enabled', catalog['supportedExtensions'] == ['pdf'] and catalog['uploadEnabled'] is True)
    m4.record('catalog_two_pdf_sources', all(x['availability'] == 'PDF_AVAILABLE' for x in catalog['methods'] if x['id'] in ('wechat-chat', 'local-document')))
    stage = 'file'
    session = m4.issue()
    file_row = m4.upload(session, safe_pdf, run_id)
    stage = 'order'
    quote = m4.quote(session, file_row['id'], 1, run_id)
    order = m4.create_and_pay(session, quote['id'], run_id, 'pdf-source')
    stage = 'admin-task'
    admin = m4.login('admin')
    task = m4.data(m4.create_task(admin, order['id'], 'pdf-source-task-' + run_id))
    for action, expected_state in [('CLAIM', 'CLAIMED'), ('ACCEPT', 'ACCEPTED'), ('START', 'EXECUTING'), ('SUCCEED', 'SUCCEEDED')]:
        body = {'action': action, 'expectedVersion': task['version']}
        if action != 'CLAIM': body.update(leaseId=task['leaseId'], fence=task['fence'])
        if action == 'SUCCEED': body['receiptSeq'] = 1
        task = m4.task_from_action(m4.action(admin, task['id'], 'pdf-source-' + action.lower() + '-' + run_id, body))
        m4.record('task_' + expected_state.lower(), task['state'] == expected_state)
    stage = 'result'
    result = m4.data(m4.app_result(session, order['id'], m4.EDGE_PORT))
    m4.record('miniapp_same_simulated_terminal_result', result['state'] == 'SUCCEEDED' and result['receipt']['simulation'] is True and result['receipt']['receiptSeq'] == 1)
    detail = m4.data(m4.request(m4.EDGE_PORT, 'GET', m4.ADMIN_BASE + '/orders/' + order['id'], token=admin))
    m4.record('admin_same_task_result', detail['printTask']['id'] == task['id'] and detail['printTask']['state'] == 'SUCCEEDED')
    report.update(status='PASS', fixtureFileId=file_row['id'], fixtureOrderId=order['id'], fixtureTaskId=task['id'])
except Exception as exc:
    report.update(status='FAIL', failure={'stage': stage, 'type': type(exc).__name__})
finally:
    if admin:
        try: m4.json_request(m4.EDGE_PORT, 'POST', '/admin-api/system/auth/logout', token=admin, value={})
        except Exception: pass
    pathlib.Path(args.evidence).write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({'status': report['status'], 'checks': len(m4.checks), 'runId': run_id, 'wechatDevice': 'NOT_RUN'}))
raise SystemExit(0 if report['status'] == 'PASS' else 1)
