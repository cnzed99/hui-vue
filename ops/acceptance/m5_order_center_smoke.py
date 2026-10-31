#!/usr/bin/env python3
"""Focused M5 loopback verification; creates isolated demo-only rows."""

from __future__ import annotations

import hashlib
import http.client
import json
import pathlib
import time
import uuid
from datetime import datetime, timezone

BASE = pathlib.Path('/home/ubuntu/print-system')
ORIGIN = 'http://127.0.0.1:18080'
APP = '/app-api/print/miniapp/demo'
checks: list[dict] = []


def check(name: str, condition: bool, detail=None):
    checks.append({'name': name, 'passed': bool(condition), **({'detail': detail} if detail is not None else {})})
    if not condition:
        raise RuntimeError(name)


def request(port: int, method: str, path: str, token: str | None = None, body: bytes | None = None,
            content_type: str | None = None, headers: dict | None = None):
    final = {'Accept': 'application/json', 'Host': '127.0.0.1', 'Origin': ORIGIN}
    if token:
        final['Authorization'] = 'Bearer ' + token
    if content_type:
        final['Content-Type'] = content_type
    if headers:
        final.update(headers)
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=20)
    connection.request(method, path, body=body, headers=final)
    response = connection.getresponse()
    raw = response.read()
    try:
        parsed = json.loads(raw.decode()) if raw else None
    except (UnicodeDecodeError, json.JSONDecodeError):
        parsed = raw
    result = (response.status, parsed, dict(response.headers))
    connection.close()
    return result


def json_request(port: int, method: str, path: str, token: str | None = None, value=None, headers=None):
    body = None if value is None else json.dumps(value, separators=(',', ':')).encode()
    return request(port, method, path, token, body,
                   'application/json' if value is not None else None, headers)


def data(reply):
    status, body, _ = reply
    if status != 200 or not isinstance(body, dict) or body.get('code') != 0:
        raise RuntimeError('expected_success')
    return body.get('data')


def code(reply):
    body = reply[1]
    return body.get('code') if isinstance(body, dict) else None


def make_pdf():
    stream = b'BT /F1 18 Tf 72 720 Td (M5 order center) Tj ET\n'
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
        b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'endstream',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    ]
    output = bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
    offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(output)); output.extend(f'{index} 0 obj\n'.encode() + value + b'\nendobj\n')
    xref = len(output); output.extend(f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]: output.extend(f'{offset:010d} 00000 n \n'.encode())
    output.extend(f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    return bytes(output)


def issue():
    return data(request(48080, 'POST', APP + '/sessions', body=b''))


def upload(session, run_id):
    content = make_pdf(); boundary = '----m5' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="m5-{run_id}.pdf"\r\n'
            'Content-Type: application/pdf\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    row = data(request(48080, 'POST', APP + '/files', session['accessToken'], body,
                       'multipart/form-data; boundary=' + boundary, {'Idempotency-Key': 'm5-upload-' + run_id}))
    for _ in range(60):
        if row['status'] in ('READY', 'FAILED', 'DELETED'): break
        time.sleep(.25)
        row = data(request(48080, 'GET', APP + '/file?id=' + row['id'], session['accessToken']))
    check('pdf_ready', row['status'] == 'READY' and row['pageCount'] == 1)
    return row


def create_order(session, file_id, copies, run_id):
    quote = data(json_request(48080, 'POST', APP + '/quotes', session['accessToken'],
        {'fileId': file_id, 'paper': 'A4', 'color': 'BLACK_WHITE', 'copies': copies, 'side': 'SIMPLEX'},
        {'Idempotency-Key': f'm5-quote-{run_id}-{copies}'}))
    return data(json_request(48080, 'POST', APP + '/orders', session['accessToken'], {'quoteId': quote['id']},
        {'Idempotency-Key': f'm5-order-{run_id}-{copies}'}))


def run():
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    a, b = issue(), issue()
    file_row = upload(a, run_id)
    first = create_order(a, file_row['id'], 1, run_id)
    time.sleep(.01)
    second = create_order(a, file_row['id'], 2, run_id)

    page1_reply = request(48080, 'GET', APP + '/orders?pageNo=1&pageSize=1', a['accessToken'])
    page1 = data(page1_reply)
    page2 = data(request(48080, 'GET', APP + '/orders?pageNo=2&pageSize=1', a['accessToken']))
    check('owner_scoped_total_and_has_more', page1['total'] == 2 and page1['hasMore'] is True and page2['hasMore'] is False)
    check('deterministic_distinct_pages', [row['id'] for row in page1['list']] == [second['id']]
          and [row['id'] for row in page2['list']] == [first['id']])
    check('no_store_response', 'no-store' in page1_reply[2].get('Cache-Control', ''))

    empty = data(request(48080, 'GET', APP + '/orders?pageNo=1&pageSize=20', b['accessToken']))
    check('other_principal_empty_and_total_hidden', empty['list'] == [] and empty['total'] == 0 and empty['hasMore'] is False)
    cross = request(48080, 'GET', APP + '/order?id=' + first['id'], b['accessToken'])
    missing = request(48080, 'GET', APP + '/order?id=' + str(uuid.uuid4()), b['accessToken'])
    check('cross_and_missing_detail_indistinguishable', cross[0] == missing[0] == 404
          and code(cross) == code(missing) == 1_929_010)

    invalid = [
        '/orders', '/orders?pageNo=1', '/orders?pageNo=1&pageSize=20&extra=1',
        '/orders?pageNo=1&pageNo=2&pageSize=20', '/orders?pageNo=01&pageSize=20',
        '/orders?pageNo=10001&pageSize=20', '/orders?pageNo=1&pageSize=21'
    ]
    check('strict_query_validation', all(request(48080, 'GET', APP + path, a['accessToken'])[0] == 400 for path in invalid))
    edge = request(18080, 'GET', APP + '/orders?pageNo=1&pageSize=20', a['accessToken'])
    blocked = request(18080, 'PUT', APP + '/orders?pageNo=1&pageSize=20', a['accessToken'], b'{}', 'application/json')
    check('edge_get_allowed', edge[0] == 200 and data(edge)['total'] == 2)
    check('edge_unapproved_method_blocked', blocked[0] == 403)
    return run_id, a, b, first, second


report = {'schemaVersion': 1, 'scope': 'M5 order center focused loopback runtime verification',
          'startedAt': datetime.now(timezone.utc).isoformat(), 'checks': checks,
          'boundaries': {'wechatDevice': 'NOT_RUN', 'realPayment': 'NOT_RUN', 'realPrinter': 'NOT_RUN', 'externalAcceptance': 'NOT_RUN'}}
try:
    run_id, a, b, first, second = run()
    report.update(status='PASS', runId=run_id, fixture={'principalA': a['principalId'], 'principalB': b['principalId'],
        'orderIds': [first['id'], second['id']], 'retained': True})
except Exception as error:
    report.update(status='FAIL', failure={'type': type(error).__name__, 'stage': checks[-1]['name'] if checks else 'initialization'})
finally:
    report['finishedAt'] = datetime.now(timezone.utc).isoformat()
    report['passed'] = sum(1 for item in checks if item['passed']); report['failed'] = sum(1 for item in checks if not item['passed'])
    name = 'm5-order-center-runtime-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '.json'
    destination = BASE / 'evidence/miniapp' / name
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'passed': report['passed'], 'failed': report['failed'],
                      'report': str(destination.relative_to(BASE)), 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()}))
    if report['status'] != 'PASS': raise SystemExit(1)

