#!/usr/bin/env python3
"""Run one synthetic M7 image upload through the deployed edge without logging credentials."""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import pathlib
import secrets
import time
from datetime import datetime, timezone
from urllib.parse import quote


BASE = pathlib.Path("/home/ubuntu/print-system")
EDGE_PORT = 18080
BACKEND_PORT = 48080
ORIGIN = "http://127.0.0.1:18080"
APP_BASE = "/app-api/print/miniapp/demo"
ADMIN_BASE = "/admin-api/print/miniapp-demo"


class VerificationFailure(RuntimeError):
    pass


def request(method: str, path: str, *, token: str | None = None, body: bytes = b"",
            content_type: str | None = None, headers: dict[str, str] | None = None) -> tuple[int, object]:
    final = {"Accept": "application/json", "Host": "127.0.0.1", "Origin": ORIGIN}
    if token:
        final["Authorization"] = "Bearer " + token
    if content_type:
        final["Content-Type"] = content_type
    if headers:
        final.update(headers)
    connection = http.client.HTTPConnection("127.0.0.1", EDGE_PORT, timeout=30)
    connection.request(method, path, body=body, headers=final)
    response = connection.getresponse()
    raw = response.read()
    status = response.status
    connection.close()
    try:
        payload: object = json.loads(raw.decode("utf-8"))
    except Exception:
        payload = {"nonJsonSha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
    return status, payload


def successful(response: tuple[int, object]) -> object:
    status, payload = response
    if status != 200 or not isinstance(payload, dict) or payload.get("code") != 0:
        raise VerificationFailure(f"expected_success_http_{status}")
    return payload.get("data")


def edge_json(method: str, path: str, *, token: str, value: object,
              idempotency_key: str | None = None) -> object:
    headers = {"Idempotency-Key": idempotency_key} if idempotency_key else None
    body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return successful(request(method, path, token=token, body=body,
                              content_type="application/json", headers=headers))


def admin_json(method: str, path: str, *, value: object,
               token: str | None = None, idempotency_key: str | None = None) -> object:
    headers = {"Accept": "application/json", "Host": "127.0.0.1", "tenant-id": "1",
               "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    connection = http.client.HTTPConnection("127.0.0.1", BACKEND_PORT, timeout=30)
    connection.request(method, path, body=body, headers=headers)
    response = connection.getresponse()
    raw = response.read()
    status = response.status
    connection.close()
    try:
        payload: object = json.loads(raw.decode("utf-8"))
    except Exception:
        payload = {"nonJsonSha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
    return successful((status, payload))


def task_action(token: str, task_id: str, key: str, value: dict) -> dict:
    result = admin_json("POST", f"{ADMIN_BASE}/print-tasks/{task_id}/actions",
                        token=token, value=value, idempotency_key=key)
    if not isinstance(result, dict) or not isinstance(result.get("currentTask"), dict):
        raise VerificationFailure("invalid_task_action")
    return result["currentTask"]


def multipart_image(path: pathlib.Path) -> tuple[bytes, str]:
    boundary = "----M7Image" + secrets.token_hex(12)
    content_type = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="m7-runtime{path.suffix.lower()}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("ascii")
    body = head + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode("ascii")
    return body, "multipart/form-data; boundary=" + boundary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture", type=pathlib.Path)
    parser.add_argument("--evidence", type=pathlib.Path)
    args = parser.parse_args()
    if not args.fixture.is_file():
        raise VerificationFailure("fixture_missing")

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
    issued = successful(request("POST", APP_BASE + "/sessions"))
    if not isinstance(issued, dict) or not isinstance(issued.get("accessToken"), str):
        raise VerificationFailure("invalid_session")

    body, content_type = multipart_image(args.fixture)
    created = successful(request(
        "POST", APP_BASE + "/image-files", token=issued["accessToken"], body=body,
        content_type=content_type, headers={"Idempotency-Key": "m7-image-" + run_id},
    ))
    if not isinstance(created, dict) or not isinstance(created.get("id"), str):
        raise VerificationFailure("invalid_created_file")

    current = created
    for _ in range(80):
        if current.get("status") in {"READY", "FAILED", "DELETED"}:
            break
        time.sleep(0.25)
        current = successful(request(
            "GET", APP_BASE + "/file?id=" + quote(created["id"]), token=issued["accessToken"],
        ))
        if not isinstance(current, dict):
            raise VerificationFailure("invalid_file_status")

    if current.get("status") != "READY" or current.get("pageCount") != 1:
        raise VerificationFailure("image_not_ready_single_page")

    token = issued["accessToken"]
    quote_value = edge_json(
        "POST", APP_BASE + "/quotes", token=token,
        value={"fileId": current["id"], "paper": "A4", "color": "BLACK_WHITE",
               "copies": 1, "side": "SIMPLEX"},
        idempotency_key="m7-image-quote-" + run_id,
    )
    if not isinstance(quote_value, dict) or quote_value.get("status") != "VALID":
        raise VerificationFailure("image_quote_invalid")
    order = edge_json("POST", APP_BASE + "/orders", token=token,
                      value={"quoteId": quote_value["id"]},
                      idempotency_key="m7-image-order-" + run_id)
    if not isinstance(order, dict) or not isinstance(order.get("id"), str):
        raise VerificationFailure("image_order_invalid")
    payment = edge_json("POST", APP_BASE + "/payments", token=token,
                        value={"orderId": order["id"], "mode": "DEMO"},
                        idempotency_key="m7-image-pay-" + run_id)
    if not isinstance(payment, dict) or payment.get("state") != "SUCCEEDED":
        raise VerificationFailure("image_simulated_payment_failed")

    credentials = json.loads((BASE / "runtime/secrets/smoke-admin.json").read_text(encoding="utf-8"))
    login = admin_json("POST", "/admin-api/system/auth/login",
                       value={"username": credentials["username"], "password": credentials["password"]})
    if not isinstance(login, dict) or not isinstance(login.get("accessToken"), str):
        raise VerificationFailure("admin_login_failed")
    admin_token = login["accessToken"]
    task = admin_json("POST", f"{ADMIN_BASE}/orders/{order['id']}/print-task",
                      token=admin_token, value={}, idempotency_key="m7-image-task-" + run_id)
    if not isinstance(task, dict) or not isinstance(task.get("id"), str):
        raise VerificationFailure("image_task_invalid")
    task = task_action(admin_token, task["id"], "m7-image-claim-" + run_id,
                       {"action": "CLAIM", "expectedVersion": 1})
    task = task_action(admin_token, task["id"], "m7-image-accept-" + run_id,
                       {"action": "ACCEPT", "expectedVersion": 2,
                        "leaseId": task["leaseId"], "fence": task["fence"]})
    task = task_action(admin_token, task["id"], "m7-image-start-" + run_id,
                       {"action": "START", "expectedVersion": 3,
                        "leaseId": task["leaseId"], "fence": task["fence"]})
    task = task_action(admin_token, task["id"], "m7-image-success-" + run_id,
                       {"action": "SUCCEED", "expectedVersion": 4,
                        "leaseId": task["leaseId"], "fence": task["fence"], "receiptSeq": 1})
    if task.get("state") != "SUCCEEDED" or task.get("receipt", {}).get("simulation") is not True:
        raise VerificationFailure("image_simulated_receipt_failed")
    result = successful(request("GET", APP_BASE + "/print-result?orderId=" + quote(order["id"]),
                                token=token))
    if not isinstance(result, dict) or result.get("state") != "SUCCEEDED" \
            or result.get("receipt", {}).get("simulation") is not True:
        raise VerificationFailure("image_result_failed")

    evidence = {
        "runId": run_id,
        "result": "PASS",
        "fixtureSha256": hashlib.sha256(args.fixture.read_bytes()).hexdigest(),
        "fixtureBytes": args.fixture.stat().st_size,
        "fileId": current["id"],
        "status": current["status"],
        "pageCount": current["pageCount"],
        "renderVersion": current.get("renderVersion"),
        "quoteId": quote_value["id"],
        "orderId": order["id"],
        "paymentState": payment["state"],
        "taskId": task["id"],
        "taskState": task["state"],
        "simulatedReceipt": True,
        "boundaries": {"realPayment": "NOT_RUN", "realPrinter": "NOT_RUN",
                       "wechatDevice": "NOT_RUN", "externalAcceptance": "NOT_RUN"},
    }
    rendered = json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
