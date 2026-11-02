#!/usr/bin/env python3
"""Synthetic M9 edge/runtime smoke; never reads original-system or user files."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import pathlib
import secrets
import struct
import time
import zlib
from datetime import datetime, timezone
from urllib.parse import quote

BASE = pathlib.Path("/home/ubuntu/print-system")
HELPER_PATH = pathlib.Path(__file__).resolve().with_name("m7_image_runtime_smoke.py")
APP_BASE = "/app-api/print/miniapp/demo"
ADMIN_BASE = "/admin-api/print/miniapp-demo"

spec = importlib.util.spec_from_file_location("m7_runtime_helper", HELPER_PATH)
helper = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(helper)


def png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    scanline = bytes([0]) + bytes(rgb) * width
    raw = scanline * height
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def multipart(images: list[bytes]) -> tuple[bytes, str]:
    boundary = "----M9Collage" + secrets.token_hex(12)
    parts: list[bytes] = []
    for index, image in enumerate(images):
        parts.append((f"--{boundary}\r\n"
                      f'Content-Disposition: form-data; name="files"; filename="m9-{index}.png"\r\n'
                      "Content-Type: image/png\r\n\r\n").encode("ascii") + image + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode("ascii"))
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def expect_error(response: tuple[int, object], status: int, code: int) -> None:
    actual_status, payload = response
    if actual_status != status or not isinstance(payload, dict) or payload.get("code") != code:
        raise helper.VerificationFailure(f"expected_error_{status}_{code}_got_{actual_status}")


def upload_request(*args, **kwargs) -> tuple[int, object]:
    """Retry only edge throttling; every retry preserves the caller's idempotency key and bytes."""
    response: tuple[int, object] = (429, {})
    for attempt in range(4):
        response = helper.request(*args, **kwargs)
        if response[0] != 429:
            return response
        if attempt < 3:
            time.sleep(7)
    return response


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=pathlib.Path, required=True)
    args = parser.parse_args()
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
    first = png(120, 80, (247, 145, 48))
    second = png(80, 120, (33, 118, 255))
    body, content_type = multipart([first, second])
    key = "m9-runtime-" + run_id

    expect_error(upload_request("POST", APP_BASE + "/image-collages", body=body,
                                content_type=content_type, headers={"Idempotency-Key": key}), 401, 1_926_003)
    session = helper.successful(helper.request("POST", APP_BASE + "/sessions"))
    token = session["accessToken"]
    created = helper.successful(upload_request("POST", APP_BASE + "/image-collages", token=token,
                                body=body, content_type=content_type, headers={"Idempotency-Key": key}))
    replay = helper.successful(upload_request("POST", APP_BASE + "/image-collages", token=token,
                               body=body, content_type=content_type, headers={"Idempotency-Key": key}))
    if created.get("id") != replay.get("id"):
        raise helper.VerificationFailure("idempotent_replay_changed_file")
    reversed_body, reversed_type = multipart([second, first])
    expect_error(upload_request("POST", APP_BASE + "/image-collages", token=token,
                                body=reversed_body, content_type=reversed_type,
                                headers={"Idempotency-Key": key}), 409, 1_927_007)

    current = created
    for _ in range(100):
        if current.get("status") in {"READY", "FAILED", "DELETED"}:
            break
        time.sleep(0.25)
        current = helper.successful(helper.request("GET", APP_BASE + "/file?id=" + quote(created["id"]), token=token))
    if current.get("status") != "READY" or current.get("pageCount") != 1:
        raise helper.VerificationFailure("collage_not_ready_single_page")

    other = helper.successful(helper.request("POST", APP_BASE + "/sessions"))
    cross = helper.request("GET", APP_BASE + "/file?id=" + quote(created["id"]), token=other["accessToken"])
    expect_error(cross, 404, 1_927_008)

    quote_value = helper.edge_json("POST", APP_BASE + "/quotes", token=token,
        value={"fileId": current["id"], "paper": "A4", "color": "BLACK_WHITE", "copies": 1, "side": "SIMPLEX"},
        idempotency_key="m9-quote-" + run_id)
    order = helper.edge_json("POST", APP_BASE + "/orders", token=token,
        value={"quoteId": quote_value["id"]}, idempotency_key="m9-order-" + run_id)
    payment = helper.edge_json("POST", APP_BASE + "/payments", token=token,
        value={"orderId": order["id"], "mode": "DEMO"}, idempotency_key="m9-pay-" + run_id)
    if payment.get("state") != "SUCCEEDED":
        raise helper.VerificationFailure("simulated_payment_failed")

    credentials = json.loads((BASE / "runtime/secrets/smoke-admin.json").read_text(encoding="utf-8"))
    login = helper.admin_json("POST", "/admin-api/system/auth/login",
                              value={"username": credentials["username"], "password": credentials["password"]})
    admin_token = login["accessToken"]
    task = helper.admin_json("POST", f"{ADMIN_BASE}/orders/{order['id']}/print-task", token=admin_token,
                             value={}, idempotency_key="m9-task-" + run_id)
    task = helper.task_action(admin_token, task["id"], "m9-claim-" + run_id,
                              {"action": "CLAIM", "expectedVersion": 1})
    for action, version in (("ACCEPT", 2), ("START", 3), ("SUCCEED", 4)):
        value = {"action": action, "expectedVersion": version, "leaseId": task["leaseId"], "fence": task["fence"]}
        if action == "SUCCEED": value["receiptSeq"] = 1
        task = helper.task_action(admin_token, task["id"], f"m9-{action.lower()}-{run_id}", value)
    result = helper.successful(helper.request("GET", APP_BASE + "/print-result?orderId=" + quote(order["id"]), token=token))
    if task.get("state") != "SUCCEEDED" or result.get("state") != "SUCCEEDED" \
            or result.get("receipt", {}).get("simulation") is not True:
        raise helper.VerificationFailure("simulated_print_result_failed")

    evidence = {
        "runId": run_id, "result": "PASS", "inputCount": 2,
        "orderedInputSha256": [hashlib.sha256(first).hexdigest(), hashlib.sha256(second).hexdigest()],
        "fileId": current["id"], "pageCount": current["pageCount"], "status": current["status"],
        "idempotentReplaySameFile": True, "orderedConflictRejected": True, "crossPrincipalReadRejected": True,
        "quoteId": quote_value["id"], "orderId": order["id"], "paymentState": payment["state"],
        "taskState": task["state"], "simulatedReceipt": True,
        "boundaries": {"realPayment": "NOT_RUN", "realPrinter": "NOT_RUN", "wechatDevice": "NOT_RUN",
                       "externalAcceptance": "NOT_RUN", "originalSystemWrites": "NOT_RUN"},
    }
    rendered = json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
