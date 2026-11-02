#!/usr/bin/env python3
"""M6 library vertical-loop runtime evidence; stores no credentials or session tokens."""

from __future__ import annotations

import concurrent.futures
import importlib.util
import json
import pathlib
import secrets
import subprocess
import time
from datetime import datetime, timezone


BASE = pathlib.Path("/home/ubuntu/print-system")
ENGINEERING = BASE / "runtime/worktrees/m5-text-engineering-20261031"
HELPER_PATH = ENGINEERING / "ops/acceptance/m5_text_runtime_smoke.py"
SPEC = importlib.util.spec_from_file_location("m5_runtime_helpers", HELPER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("runtime helper unavailable")
m = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)

APP = m.APP_BASE
EDGE = m.EDGE_PORT
BACKEND = m.BACKEND_PORT
checks: list[dict] = []


class Failure(RuntimeError):
    pass


def record(name: str, passed: bool, detail: object | None = None) -> None:
    item = {"name": name, "passed": bool(passed)}
    if detail is not None:
        item["detail"] = detail
    checks.append(item)
    if not passed:
        raise Failure(name)


def code(response) -> int | None:
    return m.code(response)


def data(response):
    return m.data(response)


def create_library(session: dict, entry_id: str, key: str, port: int = EDGE):
    return m.json_request(
        port,
        "POST",
        APP + "/library-files",
        token=session["accessToken"],
        value={"entryId": entry_id},
        headers={"Idempotency-Key": key},
        app=True,
    )


def wait_file(session: dict, value: dict) -> dict:
    return m.wait_file(session, value)


def restart_backend() -> None:
    result = subprocess.run(["sudo", "-n", "systemctl", "restart", "print-backend"], capture_output=True)
    if result.returncode != 0:
        raise Failure("backend_restart_failed")
    for _ in range(60):
        try:
            response = m.request(BACKEND, "GET", "/actuator/health")
            if response[0] == 200:
                return
        except Exception:
            pass
        time.sleep(2)
    raise Failure("backend_restart_timeout")


def run() -> dict:
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
    list_path = APP + "/library?pageNo=1&pageSize=20&keyword=&category=%E5%85%A8%E9%83%A8&sort=DEFAULT"
    listing_response = m.request(EDGE, "GET", list_path, app=True)
    listing = data(listing_response)
    record("public_catalog_lists_three_original_entries", listing["scope"] == "PUBLIC_DEMO_ONLY"
           and listing["total"] == 3 and len(listing["items"]) == 3)
    record("public_catalog_is_no_store", "no-store" in listing_response[2].get("Cache-Control", "").lower())
    entries = {item["id"]: item for item in listing["items"]}
    record("catalog_has_expected_page_counts", {key: value["pages"] for key, value in entries.items()} == {
        "demo-print-guide": 1, "demo-practice-notes": 2, "demo-weekly-plan": 3
    })
    for entry_id, entry in entries.items():
        response = m.request(EDGE, "GET", APP + "/library-entry?id=" + entry_id, app=True)
        detail = data(response)
        record("detail_pages_match_" + entry_id, detail["entry"]["pages"] == entry["pages"]
               and len(detail["previewPages"]) == entry["pages"]
               and detail["capabilities"]["favorite"] == "RESERVED_WECHAT_IDENTITY")

    bad_query = m.request(EDGE, "GET", APP + "/library?unknown=1", app=True)
    record("unknown_catalog_query_is_rejected", bad_query[0] == 400 and code(bad_query) == 1_933_001)
    record("library_head_is_rejected", m.request(EDGE, "HEAD", APP + "/library", app=True)[0] == 403)
    record("library_post_is_rejected", m.request(EDGE, "POST", APP + "/library", app=True)[0] == 403)
    record("library_files_get_is_rejected", m.request(EDGE, "GET", APP + "/library-files", app=True)[0] == 403)
    record("adjacent_library_path_is_closed", m.request(EDGE, "GET", APP + "/library-neighbor", app=True)[0] == 404)

    owner = m.issue()
    other = m.issue()
    main_key = "m6-library-main-" + run_id
    first = wait_file(owner, data(create_library(owner, "demo-print-guide", main_key)))
    record("library_file_reaches_ready_with_expected_pages", first["status"] == "READY" and first["pageCount"] == 1)
    replay = data(create_library(owner, "demo-print-guide", main_key, BACKEND))
    record("same_key_same_entry_replays", replay["id"] == first["id"])
    conflict = create_library(owner, "demo-practice-notes", main_key, BACKEND)
    record("same_key_different_entry_conflicts", conflict[0] == 409 and code(conflict) == 1_927_007)
    hidden = m.request(EDGE, "GET", APP + "/file?id=" + first["id"], token=other["accessToken"], app=True)
    record("cross_principal_library_file_is_hidden", hidden[0] == 404 and code(hidden) == 1_927_008)

    second = wait_file(owner, data(create_library(owner, "demo-practice-notes", "m6-library-second-" + run_id, BACKEND)))
    third = wait_file(owner, data(m.create_text(owner, "shared stage " + run_id, "m6-library-text-" + run_id, port=BACKEND)))
    record("library_and_text_share_active_file_budget", second["status"] == "READY" and third["status"] == "READY")
    over_limit = create_library(owner, "demo-weekly-plan", "m6-library-over-limit-" + run_id, BACKEND)
    record("shared_active_file_limit_is_enforced", over_limit[0] == 429 and code(over_limit) == 1_927_005)

    deterministic_owner = m.issue()
    det_key_a = "m6-library-det-a-" + run_id
    det_a = wait_file(deterministic_owner, data(create_library(deterministic_owner, "demo-weekly-plan", det_key_a, BACKEND)))
    digest_a = m.sql("SELECT content_sha256 FROM print_miniapp_demo_file WHERE id='" + det_a["id"] + "';")
    restart_backend()
    record("backend_restart_health_recovers", True)
    det_b = wait_file(deterministic_owner, data(create_library(
        deterministic_owner, "demo-weekly-plan", "m6-library-det-b-" + run_id, BACKEND)))
    digest_b = m.sql("SELECT content_sha256 FROM print_miniapp_demo_file WHERE id='" + det_b["id"] + "';")
    record("library_pdf_is_deterministic_across_restart", det_a["id"] != det_b["id"]
           and digest_a == digest_b and det_a["sizeBytes"] == det_b["sizeBytes"]
           and det_a["pageCount"] == det_b["pageCount"] == 3)

    quote = data(m.json_request(EDGE, "POST", APP + "/quotes", token=owner["accessToken"],
                                value={"fileId": first["id"], "paper": "A4", "color": "BLACK_WHITE",
                                       "copies": 2, "side": "DUPLEX"},
                                headers={"Idempotency-Key": "m6-library-quote-" + run_id}, app=True))
    record("library_file_uses_authoritative_quote", quote["status"] == "VALID"
           and quote["snapshot"]["pagesPerCopy"] == 1 and quote["snapshot"]["copies"] == "2")
    order = data(m.json_request(EDGE, "POST", APP + "/orders", token=owner["accessToken"],
                                value={"quoteId": quote["id"]},
                                headers={"Idempotency-Key": "m6-library-order-" + run_id}, app=True))
    payment = data(m.json_request(EDGE, "POST", APP + "/payments", token=owner["accessToken"],
                                  value={"orderId": order["id"], "mode": "DEMO"},
                                  headers={"Idempotency-Key": "m6-library-payment-" + run_id}, app=True))
    record("library_order_simulated_payment_succeeds", payment["state"] == "SUCCEEDED")

    admin = m.login_admin()
    task_path = m.ADMIN_BASE + "/orders/" + order["id"] + "/print-task"
    task = data(m.json_request(BACKEND, "POST", task_path, token=admin, value={},
                               headers={"Idempotency-Key": "m6-library-task-a-" + run_id}))
    same_task = data(m.json_request(BACKEND, "POST", task_path, token=admin, value={},
                                    headers={"Idempotency-Key": "m6-library-task-b-" + run_id}))
    record("library_order_has_one_stable_print_task", task["id"] == same_task["id"])
    task = m.action(admin, task["id"], "m6-library-claim-" + run_id, {"action": "CLAIM", "expectedVersion": 1})
    task = m.action(admin, task["id"], "m6-library-accept-" + run_id,
                    {"action": "ACCEPT", "expectedVersion": 2, "leaseId": task["leaseId"], "fence": task["fence"]})
    task = m.action(admin, task["id"], "m6-library-start-" + run_id,
                    {"action": "START", "expectedVersion": 3, "leaseId": task["leaseId"], "fence": task["fence"]})
    task = m.action(admin, task["id"], "m6-library-success-" + run_id,
                    {"action": "SUCCEED", "expectedVersion": 4, "leaseId": task["leaseId"],
                     "fence": task["fence"], "receiptSeq": 1})
    record("library_task_has_one_simulated_receipt", task["state"] == "SUCCEEDED"
           and task.get("receipt", {}).get("simulation") is True)
    result = data(m.request(EDGE, "GET", APP + "/print-result?orderId=" + order["id"],
                            token=owner["accessToken"], app=True))
    record("miniapp_recovers_library_print_result", result["state"] == "SUCCEEDED"
           and result.get("receipt", {}).get("simulation") is True)
    hidden_result = m.request(EDGE, "GET", APP + "/print-result?orderId=" + order["id"],
                              token=other["accessToken"], app=True)
    record("cross_principal_library_result_is_hidden", hidden_result[0] == 404)
    logout = m.json_request(BACKEND, "POST", "/admin-api/system/auth/logout", token=admin, value={})
    record("admin_session_is_logged_out", code(logout) == 0)

    return {
        "status": "PASS",
        "runId": run_id,
        "executedAt": datetime.now(timezone.utc).isoformat(),
        "deployment": {
            "backendRelease": pathlib.Path((BASE / "runtime/releases/current").resolve()).name,
            "miniappRelease": pathlib.Path("/var/www/print-system/miniapp").resolve().name,
        },
        "fixture": {"principalId": owner["principalId"], "fileId": first["id"],
                    "orderId": order["id"], "taskId": task["id"], "retained": True},
        "checks": checks,
        "boundaries": {"realPayment": "NOT_RUN", "realPrinter": "NOT_RUN", "wechatDevice": "NOT_RUN",
                       "favorite": "NOT_RUN", "realShare": "NOT_RUN", "storeScopeFilter": "NOT_RUN",
                       "externalAcceptance": "NOT_RUN"},
    }


def main() -> int:
    target = ENGINEERING / "evidence/miniapp" / (
        "m6-library-runtime-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json"
    )
    try:
        report = run()
    except Exception as exc:
        report = {"status": "FAIL", "executedAt": datetime.now(timezone.utc).isoformat(),
                  "errorType": type(exc).__name__, "failedCheck": checks[-1]["name"] if checks else "initialization",
                  "checks": checks,
                  "boundaries": {"realPayment": "NOT_RUN", "realPrinter": "NOT_RUN",
                                 "wechatDevice": "NOT_RUN", "externalAcceptance": "NOT_RUN"}}
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "checks": len(checks),
                      "report": str(target.relative_to(ENGINEERING))}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
