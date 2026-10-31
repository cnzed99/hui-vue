#!/usr/bin/env python3
"""Synthetic M5 text-print runtime evidence. Never records submitted text or credentials."""

from __future__ import annotations

import concurrent.futures
import hashlib
import http.client
import json
import os
import pathlib
import secrets
import subprocess
import time
import uuid
from datetime import datetime, timezone


BASE = pathlib.Path("/home/ubuntu/print-system")
ENGINEERING = BASE / "runtime/worktrees/m5-text-engineering-20261031"
BACKEND_PORT = 48080
EDGE_PORT = 18080
ORIGIN = "http://127.0.0.1:18080"
APP_BASE = "/app-api/print/miniapp/demo"
ADMIN_BASE = "/admin-api/print/miniapp-demo"
MYSQL = ["mysql", "--defaults-extra-file=" + str(BASE / "runtime/secrets/mysql-app.cnf"), "-N", "-B"]
checks: list[dict] = []
response_bodies: list[bytes] = []


class VerificationFailure(RuntimeError):
    pass


def record(name: str, passed: bool, detail: object | None = None) -> None:
    item = {"name": name, "passed": bool(passed)}
    if detail is not None:
        item["detail"] = detail
    checks.append(item)
    if not passed:
        raise VerificationFailure(name)


def sql(statement: str) -> str:
    result = subprocess.run(MYSQL, input=statement, text=True, capture_output=True, cwd=BASE)
    if result.returncode != 0:
        raise VerificationFailure("database_query_failed")
    return result.stdout.strip()


def request(port: int, method: str, path: str, *, token: str | None = None,
            body: bytes | None = None, content_type: str | None = None,
            headers: dict[str, str] | None = None, app: bool = False) -> tuple[int, object, dict, bytes]:
    final = {"Accept": "application/json", "Host": "127.0.0.1"}
    if app:
        final["Origin"] = ORIGIN
    else:
        final["tenant-id"] = "1"
    if token:
        final["Authorization"] = "Bearer " + token
    if content_type:
        final["Content-Type"] = content_type
    if headers:
        final.update(headers)
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
    connection.request(method, path, body=body, headers=final)
    response = connection.getresponse()
    raw = response.read()
    status = response.status
    response_headers = dict(response.headers)
    connection.close()
    response_bodies.append(raw)
    try:
        payload: object = json.loads(raw.decode("utf-8"))
    except Exception:
        payload = raw
    return status, payload, response_headers, raw


def json_request(port: int, method: str, path: str, *, token: str | None = None,
                 value: object | None = None, headers: dict[str, str] | None = None,
                 app: bool = False) -> tuple[int, object, dict, bytes]:
    body = None if value is None else json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
    return request(port, method, path, token=token, body=body,
                   content_type="application/json" if value is not None else None,
                   headers=headers, app=app)


def data(response: tuple[int, object, dict, bytes]) -> object:
    status, payload, _, _ = response
    if status != 200 or not isinstance(payload, dict) or payload.get("code") != 0:
        raise VerificationFailure("expected_success")
    return payload.get("data")


def code(response: tuple[int, object, dict, bytes]) -> int | None:
    payload = response[1]
    value = payload.get("code") if isinstance(payload, dict) else None
    return value if type(value) is int else None


def issue() -> dict:
    value = data(request(EDGE_PORT, "POST", APP_BASE + "/sessions", body=b"", app=True))
    if not isinstance(value, dict) or not isinstance(value.get("accessToken"), str):
        raise VerificationFailure("invalid_demo_session")
    return value


def create_text(session: dict, content: str, key: str, content_type: str = "application/json",
                port: int = EDGE_PORT) -> tuple[int, object, dict, bytes]:
    body = json.dumps({"content": content}, ensure_ascii=False, separators=(",", ":")).encode()
    return request(port, "POST", APP_BASE + "/text-files", token=session["accessToken"], body=body,
                   content_type=content_type, headers={"Idempotency-Key": key}, app=True)


def wait_file(session: dict, value: dict) -> dict:
    current = value
    for _ in range(60):
        if current.get("status") in ("READY", "FAILED", "DELETED"):
            return current
        time.sleep(0.25)
        current = data(request(EDGE_PORT, "GET", APP_BASE + "/file?id=" + current["id"],
                               token=session["accessToken"], app=True))
    raise VerificationFailure("file_processing_timeout")


def login_admin() -> str:
    credentials = json.loads((BASE / "runtime/secrets/smoke-admin.json").read_text())
    value = data(json_request(BACKEND_PORT, "POST", "/admin-api/system/auth/login",
                              value={"username": credentials["username"], "password": credentials["password"]}))
    return value["accessToken"]


def action(token: str, task_id: str, key: str, value: dict) -> dict:
    result = data(json_request(BACKEND_PORT, "POST", f"{ADMIN_BASE}/print-tasks/{task_id}/actions",
                               token=token, value=value, headers={"Idempotency-Key": key}))
    return result["currentTask"]


def file_log_offset(path: str) -> int:
    result = subprocess.run(["sudo", "-n", "stat", "-c", "%s", path], text=True, capture_output=True)
    return int(result.stdout.strip()) if result.returncode == 0 and result.stdout.strip().isdigit() else 0


def file_log_since(path: str, offset: int) -> bytes:
    result = subprocess.run(["sudo", "-n", "tail", "-c", f"+{offset + 1}", path], capture_output=True)
    if result.returncode != 0:
        raise VerificationFailure("log_read_failed")
    return result.stdout


def run() -> dict:
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
    sentinels = ["M5_PRIV_" + secrets.token_hex(16) for _ in range(5)]
    normal_content = "打印联调 " + run_id + "\n第二行 Print 123"
    protected_values = sentinels + [normal_content]
    protected_digests = [hashlib.sha256(value.encode()).hexdigest() for value in protected_values]
    journal_since = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S")
    access_offset = file_log_offset("/var/log/nginx/access.log")
    error_offset = file_log_offset("/var/log/nginx/error.log")
    access_id = int(sql("SELECT IFNULL(MAX(id),0) FROM infra_api_access_log;"))
    error_id = int(sql("SELECT IFNULL(MAX(id),0) FROM infra_api_error_log;"))

    owner = issue()
    other = issue()
    record("demo_sessions_issued", owner["principalId"] != other["principalId"])

    main_key = "m5-text-main-" + run_id
    created = data(create_text(owner, normal_content, main_key))
    main_file = wait_file(owner, created)
    record("text_pdf_ready", main_file["status"] == "READY" and 1 <= main_file["pageCount"] <= 20)
    replay = data(create_text(owner, normal_content, main_key, port=BACKEND_PORT))
    record("same_key_same_text_replays", replay["id"] == main_file["id"])
    conflict = create_text(owner, normal_content + " changed", main_key, port=BACKEND_PORT)
    record("same_key_different_text_conflicts", conflict[0] == 409 and code(conflict) == 1_927_007)

    concurrent_key = "m5-text-race-" + run_id
    concurrent_content = "并发幂等 " + run_id
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        responses = list(pool.map(lambda _: create_text(owner, concurrent_content, concurrent_key, port=BACKEND_PORT), range(4)))
    concurrent_rows = [data(value) for value in responses if value[0] == 200]
    busy_responses = [value for value in responses if value[0] == 429 and code(value) == 1_932_005]
    record("concurrent_requests_are_success_or_render_busy", bool(concurrent_rows)
           and len(concurrent_rows) + len(busy_responses) == len(responses))
    concurrent_ids = {value["id"] for value in concurrent_rows}
    record("concurrent_same_key_converges", len(concurrent_ids) == 1)
    concurrent_file = wait_file(owner, concurrent_rows[0])
    record("concurrent_file_ready", concurrent_file["status"] == "READY")
    settled_replay = data(create_text(owner, concurrent_content, concurrent_key, port=BACKEND_PORT))
    record("concurrent_key_replay_returns_same_file", settled_replay["id"] == concurrent_file["id"])
    key_hash = hashlib.sha256(concurrent_key.encode()).hexdigest()
    row_count = sql("SELECT COUNT(*) FROM print_miniapp_demo_file WHERE tenant_id=1 AND store_id=1 AND principal_id='"
                    + owner["principalId"] + "' AND idempotency_sha256='" + key_hash + "';")
    record("concurrent_key_has_one_database_row", row_count == "1")

    third = wait_file(owner, data(create_text(owner, "第三个文件 " + run_id, "m5-text-third-" + run_id,
                                              port=BACKEND_PORT)))
    record("third_active_file_ready", third["status"] == "READY")
    cross = request(EDGE_PORT, "GET", APP_BASE + "/file?id=" + main_file["id"],
                    token=other["accessToken"], app=True)
    record("cross_principal_file_hidden", cross[0] == 404 and code(cross) == 1_927_008)

    malformed = request(BACKEND_PORT, "POST", APP_BASE + "/text-files", token=owner["accessToken"],
                        body=("{\"content\":\"" + sentinels[0]).encode(), content_type="application/json",
                        headers={"Idempotency-Key": "m5-privacy-malformed-" + run_id}, app=True)
    record("malformed_json_fixed_error", malformed[0] == 400 and code(malformed) == 1_932_002)
    time.sleep(6.2)
    wrong_type = create_text(owner, sentinels[1], "m5-privacy-jsonx-" + run_id, "application/jsonx", EDGE_PORT)
    record("jsonx_fixed_error", wrong_type[0] == 400 and code(wrong_type) == 1_932_002)
    unsupported_type = create_text(owner, sentinels[2], "m5-privacy-text-" + run_id, "text/plain", BACKEND_PORT)
    record("unsupported_media_fixed_error", unsupported_type[0] == 400 and code(unsupported_type) == 1_932_002)
    unsupported_glyph = create_text(owner, sentinels[3] + " 🚀", "m5-privacy-glyph-" + run_id,
                                    port=BACKEND_PORT)
    record("unsupported_glyph_fixed_error", unsupported_glyph[0] == 422 and code(unsupported_glyph) == 1_932_004)
    file_limit = create_text(owner, sentinels[4], "m5-privacy-limit-" + run_id, port=BACKEND_PORT)
    record("file_stage_limit_fixed_error", file_limit[0] == 429 and code(file_limit) == 1_927_005)
    method = request(EDGE_PORT, "GET", APP_BASE + "/text-files", token=owner["accessToken"], app=True)
    record("edge_rejects_non_post", method[0] == 403)
    rate_responses = [request(EDGE_PORT, "POST", APP_BASE + "/text-files", token=owner["accessToken"],
                              body=b"{}", content_type="application/json",
                              headers={"Idempotency-Key": f"m5-rate-{run_id}-{index}"}, app=True)
                      for index in range(8)]
    record("edge_rate_limiter_is_active", any(response[0] == 429 for response in rate_responses))

    quote = data(json_request(EDGE_PORT, "POST", APP_BASE + "/quotes", token=owner["accessToken"],
                              value={"fileId": main_file["id"], "paper": "A4", "color": "BLACK_WHITE",
                                     "copies": 2, "side": "SIMPLEX"},
                              headers={"Idempotency-Key": "m5-text-quote-" + run_id}, app=True))
    quote_snapshot = quote["snapshot"]
    record("authoritative_quote_valid", quote["status"] == "VALID"
           and quote_snapshot["pagesPerCopy"] == main_file["pageCount"]
           and quote_snapshot["copies"] == "2"
           and quote_snapshot["totalSheets"] == str(main_file["pageCount"] * 2))
    order = data(json_request(EDGE_PORT, "POST", APP_BASE + "/orders", token=owner["accessToken"],
                              value={"quoteId": quote["id"]},
                              headers={"Idempotency-Key": "m5-text-order-" + run_id}, app=True))
    payment = data(json_request(EDGE_PORT, "POST", APP_BASE + "/payments", token=owner["accessToken"],
                                value={"orderId": order["id"], "mode": "DEMO"},
                                headers={"Idempotency-Key": "m5-text-pay-" + run_id}, app=True))
    record("simulated_payment_succeeded", payment["state"] == "SUCCEEDED")

    admin = login_admin()
    task = data(json_request(BACKEND_PORT, "POST", f"{ADMIN_BASE}/orders/{order['id']}/print-task",
                             token=admin, value={}, headers={"Idempotency-Key": "m5-text-task-" + run_id}))
    task = action(admin, task["id"], "m5-text-claim-" + run_id, {"action": "CLAIM", "expectedVersion": 1})
    task = action(admin, task["id"], "m5-text-accept-" + run_id,
                  {"action": "ACCEPT", "expectedVersion": 2, "leaseId": task["leaseId"], "fence": task["fence"]})
    task = action(admin, task["id"], "m5-text-start-" + run_id,
                  {"action": "START", "expectedVersion": 3, "leaseId": task["leaseId"], "fence": task["fence"]})
    task = action(admin, task["id"], "m5-text-success-" + run_id,
                  {"action": "SUCCEED", "expectedVersion": 4, "leaseId": task["leaseId"],
                   "fence": task["fence"], "receiptSeq": 1})
    record("admin_unique_task_has_simulated_receipt", task["state"] == "SUCCEEDED" and task.get("receipt", {}).get("simulation") is True)
    result_path = APP_BASE + "/print-result?orderId=" + order["id"]
    missing_session_get = request(EDGE_PORT, "GET", result_path, app=True)
    record("print_result_get_requires_session", missing_session_get[0] == 401)
    wrong_session_get = request(EDGE_PORT, "GET", result_path, token="A" * 43, app=True)
    record("print_result_get_rejects_wrong_session", wrong_session_get[0] == 401)
    wrong_origin = request(EDGE_PORT, "GET", result_path, token=owner["accessToken"],
                           headers={"Origin": "http://invalid.example"}, app=True)
    record("print_result_rejects_wrong_origin", wrong_origin[0] == 403)
    missing_session_head = request(EDGE_PORT, "HEAD", result_path, app=True)
    record("print_result_rejects_head_without_session", missing_session_head[0] == 403)
    owner_head = request(EDGE_PORT, "HEAD", result_path, token=owner["accessToken"], app=True)
    record("print_result_rejects_head_with_session", owner_head[0] == 403)
    rejected_post = request(EDGE_PORT, "POST", result_path, token=owner["accessToken"], app=True)
    record("print_result_rejects_post", rejected_post[0] == 403)
    rejected_options = request(EDGE_PORT, "OPTIONS", result_path, token=owner["accessToken"], app=True)
    record("print_result_rejects_options", rejected_options[0] == 403)
    record("print_result_rejections_are_no_store", all(
        "no-store" in response[2].get("Cache-Control", "").lower()
        for response in (missing_session_get, wrong_session_get, wrong_origin, missing_session_head,
                         owner_head, rejected_post, rejected_options)))
    adjacent_result = request(EDGE_PORT, "GET", APP_BASE + "/print-result-neighbor?orderId=" + order["id"],
                              token=owner["accessToken"], app=True)
    record("print_result_adjacent_path_is_closed", adjacent_result[0] == 404)
    result_response = request(EDGE_PORT, "GET", result_path, token=owner["accessToken"], app=True)
    record("miniapp_result_http_success", result_response[0] == 200 and code(result_response) == 0,
           {"status": result_response[0], "code": code(result_response)})
    record("miniapp_result_is_no_store",
           "no-store" in result_response[2].get("Cache-Control", "").lower())
    result = data(result_response)
    record("miniapp_result_returns_receipt", result["state"] == "SUCCEEDED" and result.get("receipt", {}).get("simulation") is True)
    hidden_result = request(EDGE_PORT, "GET", result_path,
                            token=other["accessToken"], app=True)
    record("cross_principal_result_hidden", hidden_result[0] == 404)
    logout = json_request(BACKEND_PORT, "POST", "/admin-api/system/auth/logout", token=admin, value={})
    record("admin_session_logged_out", code(logout) == 0)

    time.sleep(1.5)
    response_leaks = sum(1 for raw in response_bodies for value in protected_values if value.encode() in raw)
    journal_result = subprocess.run(["sudo", "-n", "journalctl", "-u", "print-backend.service",
                                     "--since", journal_since, "--no-pager"], capture_output=True)
    if journal_result.returncode != 0:
        raise VerificationFailure("journal_read_failed")
    journal = journal_result.stdout
    nginx_logs = file_log_since("/var/log/nginx/access.log", access_offset) + file_log_since("/var/log/nginx/error.log", error_offset)
    access_columns = "CONCAT_WS('',IFNULL(request_params,''),IFNULL(response_body,''),IFNULL(result_msg,''))"
    error_columns = "CONCAT_WS('',IFNULL(request_params,''),IFNULL(exception_message,''),IFNULL(exception_root_cause_message,''),IFNULL(exception_stack_trace,''))"
    predicates_access = " OR ".join("INSTR(" + access_columns + ",'" + value + "')>0" for value in protected_values)
    predicates_error = " OR ".join("INSTR(" + error_columns + ",'" + value + "')>0" for value in protected_values)
    access_leaks = int(sql(f"SELECT COUNT(*) FROM infra_api_access_log WHERE id>{access_id} AND ({predicates_access});"))
    error_leaks = int(sql(f"SELECT COUNT(*) FROM infra_api_error_log WHERE id>{error_id} AND ({predicates_error});"))
    record("responses_do_not_echo_text", response_leaks == 0)
    record("journal_does_not_persist_text", all(value.encode() not in journal for value in protected_values))
    record("nginx_logs_do_not_persist_text", all(value.encode() not in nginx_logs for value in protected_values))
    record("api_access_log_does_not_persist_text", access_leaks == 0)
    record("api_error_log_does_not_persist_text", error_leaks == 0)

    return {
        "status": "PASS",
        "runId": run_id,
        "executedAt": datetime.now(timezone.utc).isoformat(),
        "deployment": {
            "backendRelease": os.path.basename(os.path.realpath(BASE / "runtime/releases/current")),
            "miniappRelease": os.readlink("/var/www/print-system/miniapp"),
        },
        "fixture": {
            "principalId": owner["principalId"], "fileId": main_file["id"], "orderId": order["id"],
            "taskId": task["id"], "protectedValueSha256": protected_digests, "retained": True,
            "reason": "Immutable simulated task and receipt retained as isolated development evidence.",
        },
        "checks": checks,
        "boundaries": {"realPayment": "NOT_RUN", "realPrinter": "NOT_RUN", "wechatDevice": "NOT_RUN", "externalAcceptance": "NOT_RUN"},
    }


def main() -> int:
    os.umask(0o077)
    target = ENGINEERING / "evidence/miniapp" / ("m5-text-runtime-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")
    try:
        report = run()
    except Exception as exc:
        report = {"status": "FAIL", "executedAt": datetime.now(timezone.utc).isoformat(),
                  "errorType": type(exc).__name__, "failedCheck": checks[-1]["name"] if checks else "initialization",
                  "checks": checks,
                  "boundaries": {"realPayment": "NOT_RUN", "realPrinter": "NOT_RUN", "wechatDevice": "NOT_RUN", "externalAcceptance": "NOT_RUN"}}
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "checks": len(checks), "report": str(target.relative_to(ENGINEERING))}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
