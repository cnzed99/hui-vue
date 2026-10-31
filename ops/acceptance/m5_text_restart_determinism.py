#!/usr/bin/env python3
"""Verify text PDF determinism and idempotency across a real backend restart without logging text."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import pathlib
import re
import secrets
import subprocess
import time
from datetime import datetime, timezone


BASE = pathlib.Path("/home/ubuntu/print-system")
ENGINEERING = BASE / "runtime/worktrees/m5-text-engineering-20261031"
SMOKE = ENGINEERING / "ops/acceptance/m5_text_runtime_smoke.py"
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def load_smoke():
    spec = importlib.util.spec_from_file_location("m5_text_runtime_smoke", SMOKE)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def wait_health(module, timeout_seconds: int = 90) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            response = module.request(module.BACKEND_PORT, "GET", "/actuator/health")
            if response[0] == 200:
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def database_fingerprint(module, principal_id: str, file_id: str) -> tuple[str, str, str]:
    if not UUID.fullmatch(principal_id) or not UUID.fullmatch(file_id):
        raise RuntimeError("invalid_fixture_identifier")
    row = module.sql(
        "SELECT content_sha256,page_count,size_bytes FROM print_miniapp_demo_file "
        f"WHERE tenant_id=1 AND store_id=1 AND principal_id='{principal_id}' AND id='{file_id}';"
    )
    parts = row.split("\t")
    if len(parts) != 3 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]):
        raise RuntimeError("invalid_database_fingerprint")
    return parts[0], parts[1], parts[2]


def main() -> int:
    module = load_smoke()
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
    report_path = ENGINEERING / "evidence/miniapp" / f"m5-text-restart-determinism-{run_id}.json"
    checks: list[dict] = []
    stage = "initialization"
    try:
        session = module.issue()
        content = "重启确定性验证 " + run_id + "\nDeterministic PDF 123"
        content_digest = hashlib.sha256(content.encode()).hexdigest()
        first_key = "m5-restart-before-" + run_id
        second_key = "m5-restart-after-" + run_id

        stage = "first_render"
        first = module.wait_file(session, module.data(module.create_text(
            session, content, first_key, port=module.BACKEND_PORT)))
        first_fingerprint = database_fingerprint(module, session["principalId"], first["id"])
        checks.append({"name": "first_render_ready", "passed": first["status"] == "READY"})

        stage = "backend_restart"
        restart = subprocess.run(["sudo", "-n", "systemctl", "restart", "print-backend"], capture_output=True)
        if restart.returncode != 0 or not wait_health(module):
            raise RuntimeError("backend_restart_failed")
        checks.append({"name": "backend_restart_healthy", "passed": True})

        stage = "idempotent_replay"
        replay = module.wait_file(session, module.data(module.create_text(
            session, content, first_key, port=module.BACKEND_PORT)))
        checks.append({"name": "pre_restart_key_replays_same_file", "passed": replay["id"] == first["id"]})

        stage = "second_render"
        second = module.wait_file(session, module.data(module.create_text(
            session, content, second_key, port=module.BACKEND_PORT)))
        second_fingerprint = database_fingerprint(module, session["principalId"], second["id"])
        checks.append({"name": "second_render_ready", "passed": second["status"] == "READY"})
        checks.append({"name": "cross_restart_pdf_sha256_identical",
                       "passed": first_fingerprint[0] == second_fingerprint[0]})
        checks.append({"name": "cross_restart_page_count_identical",
                       "passed": first_fingerprint[1] == second_fingerprint[1]})
        checks.append({"name": "cross_restart_size_identical",
                       "passed": first_fingerprint[2] == second_fingerprint[2]})
        if not all(item["passed"] for item in checks):
            raise RuntimeError("determinism_check_failed")

        report = {
            "status": "PASS",
            "executedAt": datetime.now(timezone.utc).isoformat(),
            "backendRelease": pathlib.Path(BASE / "runtime/releases/current").resolve().name,
            "fixture": {
                "principalId": session["principalId"],
                "firstFileId": first["id"],
                "secondFileId": second["id"],
                "contentSha256": content_digest,
                "pdfSha256": first_fingerprint[0],
            },
            "checks": checks,
            "boundaries": {"realPrinter": "NOT_RUN", "externalAcceptance": "NOT_RUN"},
        }
    except Exception as exc:
        report = {
            "status": "FAIL",
            "executedAt": datetime.now(timezone.utc).isoformat(),
            "failedStage": stage,
            "errorType": type(exc).__name__,
            "checks": checks,
            "boundaries": {"realPrinter": "NOT_RUN", "externalAcceptance": "NOT_RUN"},
        }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "report": str(report_path.relative_to(ENGINEERING)),
                      "checks": len(checks)}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
