#!/usr/bin/env python3
"""M4 runtime verification against the loopback development deployment."""

from __future__ import annotations

import concurrent.futures
import hashlib
import http.client
import json
import os
import pathlib
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone


BASE = pathlib.Path("/home/ubuntu/print-system")
BACKEND_PORT = 48080
EDGE_PORT = 18080
ORIGIN = "http://127.0.0.1:18080"
APP_BASE = "/app-api/print/miniapp/demo"
ADMIN_BASE = "/admin-api/print/miniapp-demo"
MYSQL = ["mysql", "--defaults-extra-file=" + str(BASE / "runtime/secrets/mysql-app.cnf"), "-N", "-B"]
checks: list[dict] = []


class VerificationFailure(RuntimeError):
    pass


def record(name: str, passed: bool, detail: object | None = None) -> None:
    item = {"name": name, "passed": bool(passed)}
    if detail is not None:
        item["detail"] = detail
    checks.append(item)
    if not passed:
        raise VerificationFailure(name)


def request(port: int, method: str, path: str, *, token: str | None = None,
            body: bytes | None = None, content_type: str | None = None,
            headers: dict[str, str] | None = None, app: bool = False) -> tuple[int, object, dict]:
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
    try:
        payload: object = json.loads(raw.decode("utf-8"))
    except Exception:
        payload = raw
    return status, payload, response_headers


def json_request(port: int, method: str, path: str, *, token: str | None = None,
                 value: object | None = None, headers: dict[str, str] | None = None,
                 app: bool = False) -> tuple[int, object, dict]:
    body = None if value is None else json.dumps(value, separators=(",", ":")).encode()
    return request(port, method, path, token=token, body=body,
                   content_type="application/json" if value is not None else None,
                   headers=headers, app=app)


def data(response: tuple[int, object, dict]) -> object:
    status, payload, _ = response
    if status != 200 or not isinstance(payload, dict) or payload.get("code") != 0:
        raise VerificationFailure("expected_success:" + repr((status, payload)))
    return payload.get("data")


def code(response: tuple[int, object, dict]) -> int | None:
    payload = response[1]
    return payload.get("code") if isinstance(payload, dict) and type(payload.get("code")) is int else None


def sql(statement: str, *, expected_failure: bool = False) -> str:
    result = subprocess.run(MYSQL, cwd=BASE, input=statement, text=True, capture_output=True)
    if expected_failure:
        record("database_rejected_immutable_mutation", result.returncode != 0)
        return ""
    if result.returncode != 0:
        raise VerificationFailure("database_query_failed")
    return result.stdout.strip()


def make_pdf() -> bytes:
    stream = b"BT /F1 18 Tf 72 720 Td (M4 runtime verification) Tj ET\n"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"endstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode() + value + b"\nendobj\n")
    xref = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode())
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode())
    output.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(output)


def issue() -> dict:
    issued = data(request(BACKEND_PORT, "POST", APP_BASE + "/sessions", body=b"", app=True))
    record("demo_session_issued", isinstance(issued, dict) and bool(issued.get("accessToken")))
    return issued


def upload(session: dict, content: bytes, run_id: str) -> dict:
    boundary = "----m4" + uuid.uuid4().hex
    multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                 f"filename=\"m4-{run_id}.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").encode()
    multipart += content + f"\r\n--{boundary}--\r\n".encode()
    result = data(request(BACKEND_PORT, "POST", APP_BASE + "/files",
                          token=session["accessToken"], body=multipart,
                          content_type="multipart/form-data; boundary=" + boundary,
                          headers={"Idempotency-Key": "m4-upload-" + run_id}, app=True))
    for _ in range(50):
        if result["status"] in ("READY", "FAILED", "DELETED"):
            break
        time.sleep(0.25)
        result = data(request(BACKEND_PORT, "GET", APP_BASE + "/file?id=" + result["id"],
                              token=session["accessToken"], app=True))
    record("pdf_ready_with_authoritative_page_count", result["status"] == "READY" and result["pageCount"] == 1)
    return result


def quote(session: dict, file_id: str, copies: int, run_id: str) -> dict:
    body = {"fileId": file_id, "paper": "A4", "color": "BLACK_WHITE",
            "copies": copies, "side": "SIMPLEX"}
    result = data(json_request(BACKEND_PORT, "POST", APP_BASE + "/quotes",
                               token=session["accessToken"], value=body,
                               headers={"Idempotency-Key": f"m4-quote-{run_id}-{copies}"}, app=True))
    record(f"quote_{copies}_valid", result["status"] == "VALID")
    return result


def create_and_pay(session: dict, quote_id: str, run_id: str, label: str) -> dict:
    order = data(json_request(BACKEND_PORT, "POST", APP_BASE + "/orders",
                              token=session["accessToken"], value={"quoteId": quote_id},
                              headers={"Idempotency-Key": f"m4-order-{run_id}-{label}"}, app=True))
    payment = data(json_request(BACKEND_PORT, "POST", APP_BASE + "/payments",
                                token=session["accessToken"], value={"orderId": order["id"], "mode": "DEMO"},
                                headers={"Idempotency-Key": f"m4-pay-{run_id}-{label}"}, app=True))
    record(f"order_{label}_paid", order["id"] == payment["orderId"] and payment["state"] == "SUCCEEDED")
    return order


def login(label: str) -> str:
    credentials = json.loads((BASE / f"runtime/secrets/smoke-{label}.json").read_text())
    response = json_request(BACKEND_PORT, "POST", "/admin-api/system/auth/login",
                            value={"username": credentials["username"], "password": credentials["password"]})
    result = data(response)
    record(f"{label}_login", isinstance(result, dict) and bool(result.get("accessToken")))
    return result["accessToken"]


def create_task(token: str, order_id: str, key: str) -> tuple[int, object, dict]:
    return json_request(BACKEND_PORT, "POST", f"{ADMIN_BASE}/orders/{order_id}/print-task",
                        token=token, value={}, headers={"Idempotency-Key": key})


def action(token: str, task_id: str, key: str, value: dict) -> tuple[int, object, dict]:
    return json_request(BACKEND_PORT, "POST", f"{ADMIN_BASE}/print-tasks/{task_id}/actions",
                        token=token, value=value, headers={"Idempotency-Key": key})


def app_result(session: dict, order_id: str, port: int = BACKEND_PORT) -> tuple[int, object, dict]:
    return request(port, "GET", APP_BASE + "/print-result?orderId=" + order_id,
                   token=session["accessToken"], app=True)


def task_from_action(response: tuple[int, object, dict]) -> dict:
    result = data(response)
    if not isinstance(result, dict) or not isinstance(result.get("currentTask"), dict):
        raise VerificationFailure("invalid_action_response")
    return result["currentTask"]


def run() -> dict:
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    session = issue()
    other = issue()
    pdf = make_pdf()
    file_row = upload(session, pdf, run_id)
    quotes = [quote(session, file_row["id"], copies, run_id) for copies in (2, 3, 4)]
    orders = [create_and_pay(session, item["id"], run_id, str(index + 1))
              for index, item in enumerate(quotes)]
    admin = login("admin")
    reader = login("reader")

    before = data(app_result(session, orders[0]["id"]))
    record("owner_result_before_task_is_explicit_null", before is None)
    cross = app_result(other, orders[0]["id"])
    record("cross_principal_result_hidden", cross[0] == 404 and code(cross) == 1_931_004)

    listing = data(request(BACKEND_PORT, "GET", ADMIN_BASE + "/orders?pageNo=1&pageSize=100", token=reader))
    record("query_only_role_can_list_orders", isinstance(listing, dict) and isinstance(listing.get("list"), list))
    denied = create_task(reader, orders[0]["id"], "m4-reader-denied-" + run_id)
    record("query_only_role_cannot_process", code(denied) == 403)
    record("denied_create_wrote_no_task", sql(f"SELECT COUNT(*) FROM print_miniapp_demo_print_task WHERE order_id='{orders[0]['id']}';") == "0")

    main_key = "m4-main-create-" + run_id
    main_task = data(create_task(admin, orders[0]["id"], main_key))
    replay = data(create_task(admin, orders[0]["id"], main_key))
    record("create_task_idempotent_replay", replay["id"] == main_task["id"] and main_task["state"] == "QUEUED")
    conflict = create_task(admin, orders[1]["id"], main_key)
    record("create_key_cannot_cross_orders", conflict[0] == 409 and code(conflict) == 1_931_003)

    keys = [f"m4-race-{run_id}-{index}" for index in range(6)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        responses = list(pool.map(lambda key: create_task(admin, orders[1]["id"], key), keys))
    race_tasks = [data(item) for item in responses]
    record("concurrent_create_converges_to_one_task", len({item["id"] for item in race_tasks}) == 1)
    record("database_has_one_task_per_order", sql(f"SELECT COUNT(*) FROM print_miniapp_demo_print_task WHERE order_id='{orders[1]['id']}';") == "1")
    expiring_claim_task = race_tasks[0]
    timeout_task = data(create_task(admin, orders[2]["id"], "m4-timeout-create-" + run_id))

    action_count_before = sql(f"SELECT COUNT(*) FROM print_miniapp_demo_print_task_action WHERE task_id='{main_task['id']}';")
    decimal_version = action(admin, main_task["id"], "m4-decimal-version-" + run_id,
                             {"action": "CLAIM", "expectedVersion": 1.0})
    record("decimal_expected_version_rejected", decimal_version[0] == 400 and code(decimal_version) == 1_931_002)
    record("invalid_decimal_wrote_no_action", sql(f"SELECT COUNT(*) FROM print_miniapp_demo_print_task_action WHERE task_id='{main_task['id']}';") == action_count_before)

    claim_body = {"action": "CLAIM", "expectedVersion": 1}
    main_task = task_from_action(action(admin, main_task["id"], "m4-main-claim-" + run_id, claim_body))
    record("main_task_claimed", main_task["state"] == "CLAIMED" and main_task["fence"] == 1)
    decimal_fence = action(admin, main_task["id"], "m4-decimal-fence-" + run_id,
                           {"action": "ACCEPT", "expectedVersion": 2, "leaseId": main_task["leaseId"], "fence": 1.0})
    record("decimal_fence_rejected", decimal_fence[0] == 400 and code(decimal_fence) == 1_931_002)
    main_task = task_from_action(action(admin, main_task["id"], "m4-main-accept-" + run_id,
                                        {"action": "ACCEPT", "expectedVersion": 2,
                                         "leaseId": main_task["leaseId"], "fence": main_task["fence"]}))
    main_task = task_from_action(action(admin, main_task["id"], "m4-main-start-" + run_id,
                                        {"action": "START", "expectedVersion": 3,
                                         "leaseId": main_task["leaseId"], "fence": main_task["fence"]}))
    record("main_task_executing", main_task["state"] == "EXECUTING" and main_task["version"] == 4)
    decimal_receipt = action(admin, main_task["id"], "m4-decimal-receipt-" + run_id,
                             {"action": "SUCCEED", "expectedVersion": 4,
                              "leaseId": main_task["leaseId"], "fence": main_task["fence"], "receiptSeq": 1.0})
    record("decimal_receipt_sequence_rejected", decimal_receipt[0] == 400 and code(decimal_receipt) == 1_931_002)

    observations: list[dict] = []
    stop = threading.Event()
    def observe() -> None:
        while not stop.is_set():
            response = app_result(session, orders[0]["id"])
            if response[0] == 200 and code(response) == 0:
                value = response[1].get("data")
                if isinstance(value, dict):
                    observations.append({"state": value.get("state"), "receipt": value.get("receipt") is not None})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        future = pool.submit(observe)
        success_response = action(admin, main_task["id"], "m4-main-succeed-" + run_id,
                                  {"action": "SUCCEED", "expectedVersion": 4,
                                   "leaseId": main_task["leaseId"], "fence": main_task["fence"], "receiptSeq": 1})
        time.sleep(0.3)
        stop.set()
        future.result(timeout=5)
    main_task = task_from_action(success_response)
    record("main_task_succeeded_with_one_receipt", main_task["state"] == "SUCCEEDED" and
           isinstance(main_task.get("receipt"), dict) and main_task["receipt"]["receiptSeq"] == 1)
    record("concurrent_reads_never_observe_torn_receipt", bool(observations) and all(
        (item["state"] == "SUCCEEDED") == item["receipt"] for item in observations))

    replay_claim = data(action(admin, main_task["id"], "m4-main-claim-" + run_id, claim_body))
    record("old_action_replay_preserves_original_result", replay_claim["originalActionResult"]["afterState"] == "CLAIMED" and
           replay_claim["currentTask"]["state"] == "SUCCEEDED")
    second_task = data(create_task(admin, orders[0]["id"], "m4-main-create-second-key-" + run_id))
    record("terminal_order_cannot_create_duplicate_task", second_task["id"] == main_task["id"])
    record("database_has_exactly_one_receipt", sql(f"SELECT COUNT(*) FROM print_miniapp_demo_print_receipt WHERE task_id='{main_task['id']}';") == "1")

    sql(f"UPDATE print_miniapp_demo_print_task_action SET actor_id=CONCAT(actor_id,'x') WHERE task_id='{main_task['id']}' LIMIT 1;", expected_failure=True)
    sql(f"UPDATE print_miniapp_demo_print_receipt SET reason='tamper' WHERE task_id='{main_task['id']}';", expected_failure=True)
    record("receipt_remains_unchanged_after_rejected_update",
           sql(f"SELECT result,IFNULL(reason,'NULL') FROM print_miniapp_demo_print_receipt WHERE task_id='{main_task['id']}';") == "SIMULATED_SUCCESS\tNULL")

    expiring_claim_task = task_from_action(action(admin, expiring_claim_task["id"], "m4-expire-claim-" + run_id,
                                                   {"action": "CLAIM", "expectedVersion": 1}))
    timeout_task = task_from_action(action(admin, timeout_task["id"], "m4-timeout-claim-" + run_id,
                                           {"action": "CLAIM", "expectedVersion": 1}))
    timeout_task = task_from_action(action(admin, timeout_task["id"], "m4-timeout-accept-" + run_id,
                                           {"action": "ACCEPT", "expectedVersion": 2,
                                            "leaseId": timeout_task["leaseId"], "fence": timeout_task["fence"]}))
    old_lease = {"leaseId": expiring_claim_task["leaseId"], "fence": expiring_claim_task["fence"]}
    wait_until = max(expiring_claim_task["leaseUntilMs"], timeout_task["leaseUntilMs"])
    wait_seconds = max(0.0, (wait_until - int(time.time() * 1000)) / 1000 + 0.6)
    print(json.dumps({"phase": "lease_wait", "seconds": round(wait_seconds, 2)}), flush=True)
    time.sleep(wait_seconds)

    expiring_claim_task = task_from_action(action(admin, expiring_claim_task["id"], "m4-requeue-" + run_id,
                                                   {"action": "REQUEUE_EXPIRED_CLAIM", "expectedVersion": 2}))
    expiring_claim_task = task_from_action(action(admin, expiring_claim_task["id"], "m4-reclaim-" + run_id,
                                                   {"action": "CLAIM", "expectedVersion": 3}))
    record("expired_claim_requeued_with_new_fence", expiring_claim_task["state"] == "CLAIMED" and expiring_claim_task["fence"] == 2)
    stale = action(admin, expiring_claim_task["id"], "m4-old-lease-" + run_id,
                   {"action": "ACCEPT", "expectedVersion": 4,
                    "leaseId": old_lease["leaseId"], "fence": old_lease["fence"]})
    record("old_lease_rejected_after_reclaim", stale[0] == 409 and code(stale) == 1_931_009)
    timeout_task = task_from_action(action(admin, timeout_task["id"], "m4-mark-unknown-" + run_id,
                                           {"action": "MARK_TIMEOUT_UNKNOWN", "expectedVersion": 3}))
    record("accepted_timeout_becomes_result_unknown_without_receipt",
           timeout_task["state"] == "RESULT_UNKNOWN" and timeout_task.get("receipt") is None)
    record("unknown_result_does_not_create_replacement_task",
           data(create_task(admin, orders[2]["id"], "m4-timeout-new-create-" + run_id))["id"] == timeout_task["id"])

    owner_result = data(app_result(session, orders[0]["id"], EDGE_PORT))
    record("edge_result_route_returns_terminal_receipt", owner_result["state"] == "SUCCEEDED" and owner_result["receipt"]["simulation"] is True)
    forbidden_method = request(EDGE_PORT, "POST", APP_BASE + "/print-result?orderId=" + orders[0]["id"],
                               token=session["accessToken"], body=b"", app=True)
    record("edge_result_route_is_get_only", forbidden_method[0] == 403)
    miniapp_page = request(EDGE_PORT, "GET", "/miniapp/#/pages/order-result/index", app=True)
    record("miniapp_reentry_shell_is_served", miniapp_page[0] == 200 and isinstance(miniapp_page[1], (bytes, bytearray)))
    detail = data(request(BACKEND_PORT, "GET", ADMIN_BASE + "/orders/" + orders[0]["id"], token=admin))
    record("admin_detail_exposes_same_terminal_task", detail["printTask"]["id"] == main_task["id"] and detail["printTask"]["state"] == "SUCCEEDED")

    for label, token in (("admin", admin), ("reader", reader)):
        logout = json_request(BACKEND_PORT, "POST", "/admin-api/system/auth/logout", token=token, value={})
        record(f"{label}_logout", code(logout) == 0)

    return {
        "status": "PASS",
        "runId": run_id,
        "executedAt": datetime.now(timezone.utc).isoformat(),
        "deployment": {
            "backendRelease": os.path.basename(os.path.realpath(BASE / "runtime/releases/current")),
            "miniappRelease": os.readlink("/var/www/print-system/miniapp"),
        },
        "fixture": {
            "principalId": session["principalId"],
            "otherPrincipalId": other["principalId"],
            "fileId": file_row["id"],
            "pdfSha256": hashlib.sha256(pdf).hexdigest(),
            "orderIds": [item["id"] for item in orders],
            "taskIds": [main_task["id"], expiring_claim_task["id"], timeout_task["id"]],
            "retained": True,
            "reason": "M4 actions and receipts are immutable by design; records remain as isolated development evidence.",
        },
        "checks": checks,
        "boundaries": {
            "environment": "loopback development demonstration",
            "realPayment": "NOT_RUN",
            "realPrinter": "NOT_RUN",
            "wechatDevice": "NOT_RUN",
            "externalAcceptance": "NOT_RUN",
        },
    }


def main() -> int:
    os.umask(0o077)
    target = BASE / "evidence/miniapp" / ("m4-runtime-verification-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")
    try:
        report = run()
    except Exception as exc:
        report = {
            "status": "FAIL",
            "executedAt": datetime.now(timezone.utc).isoformat(),
            "errorType": type(exc).__name__,
            "error": str(exc) if isinstance(exc, VerificationFailure) else "unexpected_runtime_error",
            "checks": checks,
            "boundaries": {"environment": "loopback development demonstration", "externalAcceptance": "NOT_RUN"},
        }
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    target.chmod(0o600)
    print(json.dumps({"status": report["status"], "report": str(target),
                      "passed": sum(1 for item in checks if item["passed"]),
                      "failed": sum(1 for item in checks if not item["passed"])}), flush=True)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
