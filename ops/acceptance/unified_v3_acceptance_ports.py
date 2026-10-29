"""Source-only response schemas, one-shot concurrent cases and browser evidence ports."""
import copy
import concurrent.futures
import re
import threading
from urllib.parse import urlsplit
from ordinary_activation_common import require, canon, digest


def scalar(kind, nullable=False, **extra):
    return {'type': kind, **({'nullable': True} if nullable else {}), **extra}


def obj(properties):
    return {'type': 'object', 'required': list(properties), 'properties': properties, 'additional': False}


def array(items):
    return {'type': 'array', 'items': items}


CATEGORY_ID = '[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}'


def _decimal_range_pattern(low, high):
    """Canonical unsigned decimal strings in an inclusive integer interval."""
    def fixed(left, right):
        if left == right:
            return left
        if left == '0' * len(left) and right == '9' * len(right):
            return '[0-9]{' + str(len(left)) + '}'
        if len(left) == 1:
            return '[' + left + '-' + right + ']'
        if left[0] == right[0]:
            return left[0] + fixed(left[1:], right[1:])
        width = len(left) - 1
        parts = [left[0] + fixed(left[1:], '9' * width),
                 right[0] + fixed('0' * width, right[1:])]
        if int(left[0]) + 1 <= int(right[0]) - 1:
            parts.append('[' + str(int(left[0]) + 1) + '-' + str(int(right[0]) - 1) +
                         '][0-9]{' + str(width) + '}')
        return '(?:' + '|'.join(parts) + ')'
    return '(?:' + '|'.join(fixed(str(max(low, 10 ** (width - 1))),
                                    str(min(high, 10 ** width - 1)))
                              for width in range(len(str(low)), len(str(high)) + 1)) + ')'


def serialized_long_total_schema():
    # NumberSerializer.java:31 uses strict bounds; Long.MIN/MAX bound the strings.
    safe = 9007199254740991
    pattern = '(?:' + _decimal_range_pattern(safe, 2**63 - 1) + '|-' + _decimal_range_pattern(safe, 2**63) + ')'
    return scalar('union', variants=[scalar('integer', minimum=-safe + 1, maximum=safe - 1),
                                     scalar('string', pattern=pattern)])


def endpoint_schema(path, method='GET'):
    """Exact serialized record/map fields read from the frozen Java source DTOs."""
    base = urlsplit(path).path
    text, maybe_text = scalar('string'), scalar('string', True)
    integer, boolean = scalar('integer'), scalar('boolean')
    identifier = scalar('string', pattern=CATEGORY_ID)
    long_total = serialized_long_total_schema()
    version = scalar('string', pattern='[0-9]+')
    category = obj({'id': identifier, 'version': version, 'name': text,
                    'fileCount': scalar('union', True, variants=[integer, scalar('string', pattern='[0-9]+')]),
                    'sort': scalar('integer', True)})
    product = obj({**{key: text for key in ('id', 'code', 'name', 'categoryPath', 'catalogKind')},
                   **{key: maybe_text for key in ('unitPrice', 'currency', 'priceDescription')}})
    library = obj({**{key: text for key in ('id', 'libraryStoreName', 'fileName', 'categoryPath',
                         'visibility', 'auditStatus', 'uploadedAt')},
                   **{key: maybe_text for key in ('price', 'currency', 'printCount', 'viewCount',
                                                  'favoriteCount', 'downloadCount')},
                   'pages': scalar('integer', True)})
    terminal = obj({**{key: text for key in ('id', 'name', 'hostName', 'updatedAt')},
                    **{key: maybe_text for key in ('operatingSystem', 'clientVersion', 'ipAddress')},
                    'mobileSelfService': scalar('boolean', True), 'enabled': scalar('boolean', True),
                    'onlineStatus': scalar('string', enum=['UNKNOWN'])})
    if base == '/admin-api/print/store/current':
        fields = ('storeName', 'notice', 'provinceCode', 'provinceName', 'cityCode', 'cityName',
                  'districtCode', 'districtName', 'streetCode', 'streetName', 'address',
                  'contact', 'telephone', 'qq', 'openTime', 'closeTime')
        profile = obj({**{key: maybe_text for key in fields}, 'businessOpen': scalar('boolean', True)})
        return obj({'profileVersion': version, 'configured': boolean, 'profile': profile,
                    'regionDictionaryStatus': scalar('string', enum=['NOT_CONFIGURED']),
                    'officialAccountQrStatus': scalar('string', enum=['NOT_CONFIGURED']),
                    'officialAccountQrUrl': scalar('null'),
                    'miniProgramQrStatus': scalar('string', enum=['NOT_CONFIGURED']),
                    'miniProgramQrUrl': scalar('null')})
    if base == '/admin-api/print/library/settings':
        return obj({'version': version, 'configured': boolean, 'name': text, 'description': text,
                    'miniProgramPathStatus': scalar('string', enum=['NOT_CONFIGURED']),
                    'miniProgramPath': scalar('null')})
    if base == '/admin-api/print/library/categories' and method == 'POST':
        return category
    if base == '/admin-api/print/library/categories':
        return obj({'list': array(category), 'total': long_total, 'page': integer, 'pageSize': integer})
    if re.fullmatch('/admin-api/print/library/categories/' + CATEGORY_ID, base):
        return category
    directory = {'/admin-api/print/directory/my-products': product,
                 '/admin-api/print/directory/platform-products': product,
                 '/admin-api/print/directory/library': library,
                 '/admin-api/print/directory/terminals': terminal}
    if base in directory:
        return obj({'list': array(directory[base]), 'total': long_total, 'page': integer,
                    'pageSize': integer, 'categories': array(text), 'dataBasis': text})
    if base == '/admin-api/print/directory/printers':
        printer = obj({**{key: text for key in ('id', 'name', 'terminalId', 'terminalName', 'updatedAt')},
                       'address': maybe_text, 'defaultPrinter': scalar('boolean', True),
                       'mobileSelfService': scalar('boolean', True),
                       'reportStatus': scalar('string', enum=['NOT_REPORTED'])})
        option = obj({'id': text, 'name': text, 'hostName': text,
                      'mobileSelfService': scalar('boolean', True)})
        return obj({'list': array(printer), 'total': long_total, 'page': integer, 'pageSize': integer,
                    'terminals': array(option),
                    'statusBasis': scalar('string', enum=['DEVICE_REPORT_NOT_CONNECTED'])})
    if base == '/admin-api/print/statistics/terminal':
        point = obj({**{key: text for key in ('day', 'terminal', 'scene', 'currency', 'orders',
                                            'invalidAmountCount')},
                     'simulation': boolean, 'amountSnapshot': maybe_text})
        return obj({'list': array(point), 'scenes': array(text), 'startDate': text, 'endDate': text,
                    'basis': scalar('string', enum=['CURRENT_ACTOR_ORDER_SNAPSHOTS']),
                    'timezone': scalar('string', enum=['Asia/Shanghai']),
                    'transactionAmountDefinition': scalar('string', enum=['PENDING_CONFIRMATION'])})
    if base in ('/admin-api/print/statistics/paper-total', '/admin-api/print/statistics/paper-daily'):
        paper = obj({**{key: text for key in ('id', 'terminal', 'printer', 'paper', 'total', 'updatedAt')},
                     **{key: maybe_text for key in ('remaining', 'warning', 'simplex', 'duplex', 'day')},
                     'simulation': boolean})
        return obj({'list': array(paper), 'total': text, 'page': integer, 'size': integer})
    positions = ('COMPUTER_TERMINAL', 'TOUCH_TERMINAL', 'FULLSCREEN_TERMINAL', 'PORTRAIT_TERMINAL',
                 'CLOUD_BOX', 'ORDER_PAGE', 'COMPUTER_DESKTOP', 'MINI_SHARE')
    if base.startswith('/admin-api/print/advertising/') and base.rsplit('/', 1)[1] in positions:
        return obj({'position': scalar('string', enum=[base.rsplit('/', 1)[1]]), 'version': version,
                    'configured': boolean, 'customEnabled': boolean, 'successMessageHtml': text,
                    'assets': array(obj({'slot': text, 'url': text})),
                    'deliveryStatus': scalar('string', enum=['NOT_CONNECTED'])})
    raise ValueError('endpoint_schema_not_frozen')


class ConcurrentCases:
    """Two simultaneous CAS requests, one success plus one frozen conflict, no retry."""
    def __init__(self, http, sql):
        self.http, self.sql = http, sql
        self.started = set()

    def run(self, group, sessions):
        from ordinary_modules_activation_acceptance import common_result
        self.http.guard()
        require(group['id'] not in self.started, 'concurrent_group_unknown_no_replay')
        self.started.add(group['id'])
        calls = group['calls']
        require(isinstance(calls, list) and len(calls) == 2 and len({call['id'] for call in calls}) == 2,
                'exact_two_concurrent_calls_required')
        require(all(call['alias'] == 'admin' and call['method'] == 'PUT' and
                    call['path'] == calls[0]['path'] and call['body']['expectedVersion'] ==
                    group['beforeVersion'] for call in calls) and calls[0]['body'] != calls[1]['body'],
                'concurrent_cas_contract_not_frozen')
        require(group['successDataSchema'] == endpoint_schema(calls[0]['path'], 'PUT') and
                type(group['conflictCode']) is int and group['conflictCode'] != 0,
                'concurrent_endpoint_schema_or_conflict_code_not_frozen')
        require(isinstance(group['beforeVersion'], str) and group['beforeVersion'].isdigit() and
                self.sql.case_readback(group['readbackKey']) == group['expectedBefore'],
                'concurrent_before_drift')
        gate = threading.Barrier(2)
        def request(call):
            gate.wait(timeout=10)
            return self.http.raw('127.0.0.1', 48080, 'PUT', call['path'],
                                 copy.deepcopy(call['body']), sessions[call['alias']])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(request, call) for call in calls]
            replies = [future.result(timeout=30) for future in futures]
        winners = []
        results = []
        for call, reply in zip(calls, replies):
            success = reply.get('body', {}).get('code') == 0
            if success:
                data = common_result(reply, group['successDataSchema'], group['successHttp'], 0)
                require(data[group['versionField']] == str(int(group['beforeVersion']) + 1),
                        'concurrent_success_version_wrong')
                winners.append(call['id'])
            else:
                common_result(reply, group['conflictDataSchema'], group['conflictHttp'], group['conflictCode'])
            results.append({'id': call['id'], 'http': reply['status'], 'code': reply['body']['code']})
        require(len(winners) == 1, 'concurrent_requires_one_success_one_conflict')
        after = self.sql.case_readback(group['readbackKey'])
        require(after == group['expectedAfterByWinner'][winners[0]], 'concurrent_sql_readback_wrong')
        return {'id': group['id'], 'winner': winners[0], 'results': results,
                'readbackSha256': digest(canon(after))}


def validate_case_proof(contract, proof):
    groups = contract.get('concurrentCases')
    if groups is None:
        require(proof == contract['expectedProof'], 'reviewed_HTTP_cases_not_proven')
        return
    require(set(contract['expectedProof']) == {'cases'} and set(proof) == {'cases', 'concurrentCases'} and
            proof['cases'] == contract['expectedProof']['cases'], 'reviewed_case_proof_shape_or_fixed_mismatch')
    expected = {group['id']: group for group in groups}
    actual = {row['id']: row for row in proof['concurrentCases']}
    require(len(expected) == len(groups) and len(actual) == len(proof['concurrentCases']) and
            set(actual) == set(expected), 'concurrent_group_proof_incomplete')
    for key, group in expected.items():
        row = actual[key]
        calls = {call['id'] for call in group['calls']}
        require(set(row) == {'id', 'winner', 'results', 'readbackSha256'} and row['winner'] in calls and
                len(row['results']) == 2 and {item['id'] for item in row['results']} == calls,
                'concurrent_outcome_proof_schema')
        for result in row['results']:
            outcome = (group['successHttp'], 0) if result['id'] == row['winner'] else (
                group['conflictHttp'], group['conflictCode'])
            require(set(result) == {'id', 'http', 'code'} and type(result['http']) is int and
                    type(result['code']) is int and (result['http'], result['code']) == outcome,
                    'concurrent_frozen_outcome_mismatch')
        require(row['readbackSha256'] == digest(canon(group['expectedAfterByWinner'][row['winner']])),
                'concurrent_winner_readback_not_frozen')


def validate_scoped_readback(contract, proof, observed):
    groups = contract.get('concurrentCases')
    if groups is None:
        require(observed == contract['expectedSqlReadback'], 'HTTP_write_not_persisted')
        return
    require(isinstance(observed, dict) and set(observed) == {'fixed', 'concurrent'} and
            observed['fixed'] == contract['expectedSqlReadback'], 'fixed_scoped_readback_not_exact')
    winners = {row['id']: row['winner'] for row in proof['concurrentCases']}
    require(set(observed['concurrent']) == set(winners), 'concurrent_scoped_readback_incomplete')
    for group in groups:
        require(observed['concurrent'][group['id']] == group['expectedAfterByWinner'][winners[group['id']]],
                'concurrent_scoped_readback_not_exact_winner')


def safe_session_bindings(sessions):
    keys = ('userId', 'tenantId', 'clientId', 'accessId', 'refreshId',
            'accessFingerprint', 'refreshFingerprint')
    require(set(sessions) == {'admin', 'reader'}, 'browser_exact_two_sessions_required')
    return {alias: {key: row[key] for key in keys} for alias, row in sessions.items()}


class BoundBrowserPort:
    """Validate runtime browser receipts against run, plan, routes and exact sessions."""
    def __init__(self, plan, verify=None, clear=None):
        self.plan = copy.deepcopy(plan)
        self.plan_sha256 = digest(canon(self.plan))
        self.verifier, self.clearer = verify, clear
        self.started = self.clear_started = False

    def verify(self, contract, sessions):
        require(not self.started and self.verifier is not None, 'CONTROLLED_BROWSER_UNAVAILABLE_OR_REPLAY')
        self.started = True
        require(contract == self.plan['browserContract'] and contract['mode'] == 'CONTROLLED_CALLBACK',
                'browser_contract_not_frozen')
        bindings = safe_session_bindings(sessions)
        callback_contract = {**copy.deepcopy(contract), 'runId': self.plan['runId'],
                             'planSha256': self.plan_sha256}
        proof = self.verifier(callback_contract, copy.deepcopy(bindings))
        require(isinstance(proof, dict) and set(proof) == {'status', 'runId', 'planSha256',
                'sessionBindings', 'viewport', 'routes'} and proof['status'] == 'PASS_BROWSER' and
                proof['runId'] == self.plan['runId'] and proof['planSha256'] == self.plan_sha256 and
                proof['sessionBindings'] == bindings and proof['viewport'] == contract['viewport'],
                'browser_run_session_binding_mismatch')
        rows = proof['routes']
        require(isinstance(rows, list) and all(isinstance(row, dict) and set(row) == {
                'alias', 'path', 'state', 'screenshotSha256'} for row in rows), 'browser_route_proof_schema')
        expected = {(row['alias'], row['path'], row['expectedState']) for row in contract['requiredRoutes']}
        actual = {(row['alias'], row['path'], row['state']) for row in rows}
        require(len(rows) == len(actual) and actual == expected and all(
                re.fullmatch('[0-9a-f]{64}', row['screenshotSha256']) for row in rows),
                'browser_route_or_screenshot_proof_incomplete')
        return 'ACK'

    def clear_auth(self, sessions):
        require(not self.clear_started and self.clearer is not None, 'BROWSER_AUTH_CLEAR_UNAVAILABLE_OR_REPLAY')
        self.clear_started = True
        bindings = safe_session_bindings(sessions)
        proof = self.clearer(copy.deepcopy(bindings))
        require(proof == {'runId': self.plan['runId'], 'sessionBindings': bindings, 'authResiduals': 0},
                'browser_auth_clear_not_exact')
        return 'ACK'


class ManualBrowserPort:
    """Explicit pending manual evidence; never substitutes an ACK for the browser gate."""
    def __init__(self, plan, record=None):
        self.plan, self.record = copy.deepcopy(plan), copy.deepcopy(record)

    def report(self):
        return {'status': 'PENDING_MANUAL_ACCEPTANCE', 'runId': self.plan['runId'],
                'requiredRoutes': copy.deepcopy(self.plan['browserContract']['requiredRoutes']),
                'recordPresent': self.record is not None, 'satisfiesTechnicalBrowserGate': False}

    def verify(self, contract, sessions):
        raise RuntimeError('MANUAL_ACCEPTANCE_PENDING_SEPARATE_REVIEWED_GATE_REQUIRED')

    def clear_auth(self, sessions):
        raise RuntimeError('MANUAL_BROWSER_AUTH_CLEAR_NOT_PROVEN')

