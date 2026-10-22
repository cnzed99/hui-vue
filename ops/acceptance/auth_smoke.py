#!/usr/bin/env python3
"""Source-verified Yudao auth smoke. No raw payload, credential or token output.

Default: two read-only negative GET probes. --allow-login additionally creates
and revokes a test session using a server-local owner-only credential JSON.
This is not role, persistence, printing, browser, or user acceptance.
"""
import argparse
import datetime
import json
import os
import pathlib
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROUTES = {
    'login': '/admin-api/system/auth/login',
    'permission': '/admin-api/system/auth/get-permission-info',
    'logout': '/admin-api/system/auth/logout',
}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def credentials(path):
    p = pathlib.Path(path)
    if p.is_symlink():
        raise ValueError('credential_symlink_not_allowed')
    info = p.stat()
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise ValueError('credential_file_must_be_owned_and_private')
    if info.st_size > 8192:
        raise ValueError('credential_file_too_large')
    c = json.loads(p.read_text())
    if not isinstance(c, dict) or not all(isinstance(c.get(k), str) and c[k] for k in ('username', 'password')):
        raise ValueError('credential_schema_invalid')
    return c

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--base-url', default='http://127.0.0.1:48080')
    ap.add_argument('--tenant-id', type=int, help='Actual seeded tenant, never inferred.')
    ap.add_argument('--credentials', help='Private server-local JSON with username/password/tenantId.')
    ap.add_argument('--allow-login', action='store_true')
    ap.add_argument('--report', default='/home/ubuntu/print-system/evidence/acceptance/auth-smoke.json')
    args = ap.parse_args()
    url = urllib.parse.urlsplit(args.base_url)
    if url.scheme not in ('http', 'https') or url.hostname not in ('127.0.0.1', 'localhost', '::1') or url.path not in ('', '/') or url.username or url.password or url.query or url.fragment:
        raise ValueError('only_loopback_origin_allowed')
    if args.allow_login and not args.credentials:
        raise ValueError('login_requires_private_credential_file')
    c = credentials(args.credentials) if args.allow_login else {}
    tenant = args.tenant_id if args.tenant_id is not None else c.get('tenantId')
    if isinstance(tenant, bool) or not isinstance(tenant, int) or tenant < 0:
        raise ValueError('actual_tenant_id_required')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    cases = []

    def request(name, method='GET', token=None, data=None):
        headers = {'Accept': 'application/json', 'tenant-id': str(tenant)}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        payload = None
        if data is not None:
            headers['Content-Type'] = 'application/json'
            payload = json.dumps(data).encode('utf-8')
        req = urllib.request.Request(args.base_url.rstrip('/') + ROUTES[name], data=payload, headers=headers, method=method)
        try:
            response = opener.open(req, timeout=10)
        except urllib.error.HTTPError as exc:
            response = exc
        except Exception as exc:
            return {'transport_error': type(exc).__name__}
        with response:
            status = response.code
            raw = response.read(1048577)
        if len(raw) > 1048576:
            return {'http_status': status, 'payload_error': 'response_too_large'}
        try:
            body = json.loads(raw)
        except Exception:
            return {'http_status': status, 'payload_error': 'non_json_response'}
        if not isinstance(body, dict):
            return {'http_status': status, 'payload_error': 'non_object_response'}
        return {'http_status': status, 'body': body}

    def code(result):
        value = result.get('body', {}).get('code')
        return value if isinstance(value, (int, str)) else None

    def ok(result):
        return result.get('http_status') == 200 and code(result) == 0

    def denied(result):
        # Framework source writes CommonResult 401; HTTP status may be 200.
        return result.get('http_status') == 401 or code(result) == 401

    def record(case_id, result, passed):
        row = {'id': case_id, 'status': 'PASS' if passed else 'FAIL',
               'http_status': result.get('http_status'), 'business_code': code(result)}
        for key in ('transport_error', 'payload_error'):
            if key in result:
                row[key] = result[key]
        cases.append(row)

    result = request('permission')
    record('M1-03-no-token', result, denied(result))
    result = request('permission', token='acceptance-invalid-' + uuid.uuid4().hex)
    record('M1-03-forged-token', result, denied(result))

    if args.allow_login:
        token = None
        logout_result = None
        try:
            result = request('login', 'POST', data={'username': c['username'], 'password': c['password']})
            data = result.get('body', {}).get('data')
            if isinstance(data, dict) and isinstance(data.get('accessToken'), str):
                token = data['accessToken']
            record('M1-04-login', result, ok(result) and bool(token))
            if token:
                user_id = data.get('userId')
                result = request('permission', token=token)
                permission = result.get('body', {}).get('data')
                valid = (isinstance(permission, dict) and
                         isinstance(permission.get('user'), dict) and
                         permission['user'].get('id') == user_id and user_id is not None and
                         all(isinstance(permission.get(k), list) for k in ('roles', 'permissions', 'menus')))
                record('M1-04-permission-info', result, ok(result) and valid)
        finally:
            if token:
                logout_result = request('logout', 'POST', token=token)
                record('M1-04-logout', logout_result, ok(logout_result) and logout_result.get('body', {}).get('data') is True)
        if token:
            result = request('permission', token=token)
            record('M1-04-revoked-token', result, denied(result))
    else:
        cases.append({'id': 'M1-04-session-roundtrip', 'status': 'NOT_RUN',
                      'reason': 'Requires --allow-login and private server-local credential JSON.'})

    report = {
        'generated_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope': 'Source-verified auth endpoint smoke only',
        'state_changes': 'Test session login/logout only' if args.allow_login else 'None; negative GET probes only',
        'cases': cases,
        'not_covered': ['role_action_permissions', 'cross_store_ownership', 'expired_refresh_tokens',
                        'database_restart_persistence', 'printing_business', 'visual_parity', 'user_acceptance'],
    }
    target = pathlib.Path(args.report)
    target.parent.mkdir(parents=True, exist_ok=True)
    # Reports deliberately exclude payloads, URLs, usernames and tokens.
    content = json.dumps(report, ensure_ascii=False, indent=2)
    target.write_text(content)
    os.chmod(target, 0o600)
    print(json.dumps({'passed': sum(x['status']=='PASS' for x in cases),
                      'failed': sum(x['status']=='FAIL' for x in cases),
                      'not_run': sum(x['status']=='NOT_RUN' for x in cases)}))
    return 1 if any(x['status']=='FAIL' for x in cases) else 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as exc:
        # Avoid accidentally printing input credentials or server response text.
        print(json.dumps({'status': 'ERROR', 'error_type': type(exc).__name__}))
        sys.exit(2)
