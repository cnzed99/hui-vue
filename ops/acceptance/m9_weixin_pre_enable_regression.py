#!/usr/bin/env python3
"""Candidate running with staged/native disabled: preserve existing upload and M9 H5 paths."""
import json
import secrets
import m9_pre_enable_regression as pre
import m9_image_collage_runtime_smoke as m9
APP = pre.APP

def main():
    helper = pre.helper
    token = helper.successful(helper.request("POST", APP + "/sessions"))["accessToken"]
    run = secrets.token_hex(12)
    for route, field, filename, kind, content in [
        ("/files", "file", "regression.pdf", "application/pdf", pre.pdf()),
        ("/image-files", "file", "regression.png", "image/png", pre.png())]:
        body, mime = pre.multipart(field, filename, kind, content)
        created = helper.successful(pre.request_with_rate_retry("POST", APP + route, token=token,
            body=body, content_type=mime, headers={"Idempotency-Key": run + route}))
        pre.ready(token, created)
    body, mime = m9.multipart([pre.png()])
    created = helper.successful(m9.upload_request("POST", APP + "/image-collages", token=token,
        body=body, content_type=mime, headers={"Idempotency-Key": "m9-weixin-pre-" + run}))
    pre.ready(token, created)
    response = helper.request("POST", APP + "/image-collage-upload-sessions", token=token,
        body=b"{}", content_type="application/json", headers={"Idempotency-Key": "m9-staged-disabled-" + run})
    m9.expect_error(response, 404, 1935001)
    m9.expect_error(helper.request("POST", APP + "/sessions", headers={"Origin": "",
        "Referer": "https://servicewechat.com/wx0000000000000000/devtools/page-frame.html"}), 403, 1926008)
    print(json.dumps({"status":"PASS", "pdf":"READY", "singleImage":"READY", "h5Collage":"READY",
        "staged":"DISABLED", "native":"DISABLED"}))

if __name__ == "__main__": main()
