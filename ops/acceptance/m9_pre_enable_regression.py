#!/usr/bin/env python3
"""Before M9 enablement, prove the deployed candidate preserves existing PDF and single-image upload."""

from __future__ import annotations

import importlib.util
import pathlib
import secrets
import struct
import time
import zlib

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("m7_helper", HERE / "m7_image_runtime_smoke.py")
helper = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(helper)
APP = "/app-api/print/miniapp/demo"


def pdf() -> bytes:
    stream = b"BT /F1 18 Tf 72 720 Td (M9 pre-enable PDF regression) Tj ET\n"
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"endstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"); offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(output)); output.extend(f"{index} 0 obj\n".encode() + value + b"\nendobj\n")
    xref = len(output); output.extend(f"xref\n0 {len(objects)+1}\n".encode() + b"0000000000 65535 f \n")
    for offset in offsets[1:]: output.extend(f"{offset:010d} 00000 n \n".encode())
    output.extend(f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(output)


def png() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    raw = (b"\x00" + bytes((247, 145, 48)) * 8) * 8
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def multipart(name: str, filename: str, content_type: str, content: bytes) -> tuple[bytes, str]:
    boundary = "----M9Pre" + secrets.token_hex(8)
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {content_type}\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return body, "multipart/form-data; boundary=" + boundary


def ready(token: str, created: dict) -> dict:
    current = created
    for _ in range(80):
        if current.get("status") in {"READY", "FAILED", "DELETED"}: break
        time.sleep(.25)
        current = helper.successful(helper.request("GET", APP + "/file?id=" + current["id"], token=token))
    if current.get("status") != "READY" or current.get("pageCount") != 1:
        raise helper.VerificationFailure("existing_upload_regression")
    return current


def request_with_rate_retry(method: str, path: str, **kwargs):
    """Retry only the shared edge limiter while preserving the exact request arguments."""
    for attempt in range(4):
        response = helper.request(method, path, **kwargs)
        if response[0] != 429:
            return response
        if attempt == 3:
            raise helper.VerificationFailure("edge_rate_limit_exhausted")
        time.sleep(7)
    raise AssertionError("unreachable")


def main() -> int:
    session = helper.successful(helper.request("POST", APP + "/sessions")); token = session["accessToken"]
    run = secrets.token_hex(8)
    pdf_body, pdf_type = multipart("file", "pre-enable.pdf", "application/pdf", pdf())
    pdf_created = helper.successful(request_with_rate_retry("POST", APP + "/files", token=token, body=pdf_body,
        content_type=pdf_type, headers={"Idempotency-Key": "m9-pre-pdf-" + run}))
    ready(token, pdf_created)
    image_body, image_type = multipart("file", "pre-enable.png", "image/png", png())
    image_created = helper.successful(request_with_rate_retry("POST", APP + "/image-files", token=token, body=image_body,
        content_type=image_type, headers={"Idempotency-Key": "m9-pre-image-" + run}))
    ready(token, image_created)
    disabled = request_with_rate_retry("POST", APP + "/image-collages", token=token,
        body=b"", content_type="multipart/form-data; boundary=disabled",
        headers={"Idempotency-Key": "m9-disabled-" + run})
    if disabled[0] != 404 or not isinstance(disabled[1], dict) or disabled[1].get("code") != 1_934_001:
        raise helper.VerificationFailure("m9_not_disabled_during_regression")
    print('{"status":"PASS","pdf":"READY","image":"READY","m9":"DISABLED"}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
