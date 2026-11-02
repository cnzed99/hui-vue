#!/usr/bin/env python3
"""Run the M8 Nginx candidate on temporary loopback ports against a mock upstream."""
import hashlib
import http.client
import json
import pathlib
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path('/home/ubuntu/print-system')
CANDIDATE = pathlib.Path(__file__).with_name('nginx-print-system.conf')
EVIDENCE = ROOT / 'evidence/testing/m8-nginx-candidate'
checks = []
received = []


def sha(data):
    return hashlib.sha256(data).hexdigest()


def runtime_snapshot():
    states = subprocess.check_output(['systemctl', 'is-active', 'nginx', 'print-backend.service'], text=True).splitlines()
    config = subprocess.check_output(['sudo', '-n', 'nginx', '-T'], stderr=subprocess.DEVNULL)
    return {'services': states, 'nginxConfigSha256': sha(config)}


class Mock(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        self.answer()

    def do_POST(self):
        self.answer()

    def answer(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        record = {'method': self.command, 'path': self.path, 'bodyBytes': len(body),
                  'authorization': self.headers.get('Authorization'), 'origin': self.headers.get('Origin'),
                  'host': self.headers.get('Host')}
        received.append(record)
        payload = json.dumps(record).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def request(port, method, path, body=None):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
    connection.request(method, path, body=body, headers={
        'Host': '127.0.0.1', 'Origin': 'http://127.0.0.1:18080',
        'Authorization': 'Bearer M8_PROBE_DUMMY', 'Content-Type': 'application/json'})
    response = connection.getresponse()
    result = {'status': response.status, 'headers': dict(response.getheaders()), 'body': response.read()}
    connection.close()
    return result


def check(name, condition):
    checks.append({'name': name, 'pass': bool(condition)})
    if not condition:
        raise AssertionError(name)


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    scratch_root = ROOT / 'runtime/scratch'
    scratch_root.mkdir(exist_ok=True)
    if shutil.disk_usage(ROOT).free < 10 * 1024 ** 3:
        raise RuntimeError('below 10GiB free-space floor')
    scratch = pathlib.Path(tempfile.mkdtemp(prefix='m8-nginx-probe.', dir=scratch_root))
    mock = None
    nginx = None
    before = runtime_snapshot()
    started = time.time()
    result = {'candidateSha256': sha(CANDIDATE.read_bytes()), 'scope': 'isolated loopback proxy + mock upstream',
              'before': before}
    try:
        mock = ThreadingHTTPServer(('127.0.0.1', 0), Mock)
        mock_port = mock.server_address[1]
        threading.Thread(target=mock.serve_forever, daemon=True).start()
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        candidate = CANDIDATE.read_text()
        check('exact GET route', candidate.count('location = /app-api/print/miniapp/demo/cancellation {') == 1)
        check('exact POST route', candidate.count('location = /app-api/print/miniapp/demo/cancellations {') == 1)
        check('fallback preserved', 'location ^~ /app-api/ { return 404; }' in candidate)
        isolated = candidate.replace('listen 127.0.0.1:18080;', f'listen 127.0.0.1:{port};')
        isolated = isolated.replace('http://127.0.0.1:48080', f'http://127.0.0.1:{mock_port}')
        config = ('worker_processes 1;\npid ' + str(scratch / 'nginx.pid') + ';\n'
                  'error_log ' + str(scratch / 'error.log') + ' warn;\nevents { worker_connections 64; }\n'
                  'http { access_log off; client_body_temp_path ' + str(scratch / 'body') + ';\n'
                  'limit_conn_zone $binary_remote_addr zone=print_miniapp_upload:1m;\n'
                  'limit_req_zone $binary_remote_addr zone=print_miniapp_upload_rate:1m rate=10r/m;\n'
                  'limit_req_zone $binary_remote_addr zone=print_miniapp_quote_rate:1m rate=30r/m;\n'
                  + isolated + '\n}\n')
        config_path = scratch / 'nginx.conf'
        config_path.write_text(config)
        syntax = subprocess.run(['nginx', '-t', '-p', str(scratch), '-c', str(config_path)], capture_output=True, text=True)
        (EVIDENCE / 'nginx-test.log').write_text(syntax.stdout + syntax.stderr)
        check('nginx -t', syntax.returncode == 0)
        nginx = subprocess.Popen(['nginx', '-p', str(scratch), '-c', str(config_path), '-g', 'daemon off;'],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.05)
        else:
            raise RuntimeError('temporary nginx did not listen')
        read_path = '/app-api/print/miniapp/demo/cancellation?orderId=00000000-0000-0000-0000-000000000001'
        write_path = '/app-api/print/miniapp/demo/cancellations'
        read = request(port, 'GET', read_path)
        check('GET forwards query', read['status'] == 200 and json.loads(read['body'])['path'] == read_path)
        for key, value in [('Cache-Control', 'no-store'), ('Pragma', 'no-cache'), ('X-Content-Type-Options', 'nosniff')]:
            check('GET ' + key, read['headers'].get(key) == value)
        for method, path in [('HEAD', read_path), ('POST', read_path), ('PUT', read_path),
                             ('GET', write_path), ('HEAD', write_path), ('PUT', write_path), ('OPTIONS', write_path)]:
            prior = len(received)
            response = request(port, method, path)
            check(method + ' denied for ' + path.split('?')[0], response['status'] == 403 and len(received) == prior)
        for path in [write_path + '/', write_path + '-other', '/app-api/print/miniapp/demo/cancellation-other']:
            prior = len(received)
            response = request(port, 'GET', path)
            check('adjacent 404 ' + path, response['status'] == 404 and len(received) == prior)
        response = request(port, 'POST', write_path, b'x' * 1024)
        forwarded = json.loads(response['body'])
        check('POST 1024 bytes accepted', response['status'] == 200 and forwarded['bodyBytes'] == 1024)
        check('forwarded Authorization', forwarded['authorization'] == 'Bearer M8_PROBE_DUMMY')
        check('forwarded Origin', forwarded['origin'] == 'http://127.0.0.1:18080')
        check('forwarded Host', forwarded['host'] == '127.0.0.1')
        check('POST no-store', response['headers'].get('Cache-Control') == 'no-store' and response['headers'].get('Pragma') == 'no-cache')
        prior = len(received)
        response = request(port, 'POST', write_path, b'x' * 1025)
        check('POST 1025 bytes rejected before upstream', response['status'] == 413 and len(received) == prior)
        statuses = [request(port, 'POST', write_path, b'{}')['status'] for _ in range(12)]
        check('POST low-frequency rate limit', 429 in statuses and set(statuses).issubset({200, 429}))
        result['rateStatuses'] = statuses
        result['status'] = 'PASS_PROXY_CANDIDATE_ONLY'
    except BaseException as error:
        result['status'] = 'FAIL'
        result['error'] = str(error)
        raise
    finally:
        if nginx is not None:
            nginx.send_signal(signal.SIGQUIT)
            try:
                nginx.wait(timeout=5)
            except subprocess.TimeoutExpired:
                nginx.kill()
                nginx.wait(timeout=5)
        if mock is not None:
            mock.shutdown()
            mock.server_close()
        result['after'] = runtime_snapshot()
        result['runningConfigAndServicesUnchanged'] = result['after'] == before
        checks.append({'name': 'running config/services unchanged', 'pass': result['runningConfigAndServicesUnchanged']})
        if not result['runningConfigAndServicesUnchanged']:
            result['status'] = 'FAIL'
        result['checks'] = checks
        result['passed'] = sum(item['pass'] for item in checks)
        result['total'] = len(checks)
        result['durationSeconds'] = round(time.time() - started, 3)
        result['upstreamRequestCount'] = len(received)
        if scratch.is_symlink() or scratch.resolve().parent != scratch_root.resolve() or not scratch.name.startswith('m8-nginx-probe.'):
            raise RuntimeError('unsafe scratch cleanup target')
        shutil.rmtree(scratch)
        result['scratchRemoved'] = not scratch.exists()
        (EVIDENCE / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    if result['status'] != 'PASS_PROXY_CANDIDATE_ONLY':
        raise RuntimeError('probe failed')


if __name__ == '__main__':
    main()
