#!/usr/bin/env python3
"""Synthetic edge-only staged smoke; preserves keys on explicit edge 429 retry, logs no token/UUID/hash."""
import argparse
import hashlib
import json
import pathlib
import secrets
import subprocess
import sys
import m9_image_collage_runtime_smoke as m9
import m9_pre_enable_regression as pre
h = m9.helper
APP = m9.APP_BASE
STAGED = APP + "/image-collage-upload-sessions"
VERSION = "IMAGE_COLLAGE_A4_PORTRAIT_GRID_V1"

def manifest(images):
    return {"renderVersion": VERSION, "files": [{"index": i, "sizeBytes": len(b),
        "sha256": hashlib.sha256(b).hexdigest()} for i, b in enumerate(images)]}

def create(token, key, images):
    return m9.upload_request("POST", STAGED, token=token, content_type="application/json",
        body=json.dumps(manifest(images), separators=(",", ":")).encode(), headers={"Idempotency-Key":key})

def part(token, upload, index, image):
    body, mime = pre.multipart("file", "fixture.png", "image/png", image)
    return m9.upload_request("POST", STAGED + "/" + upload + "/parts/" + str(index), token=token,
        body=body, content_type=mime)

def complete(token, upload):
    return m9.upload_request("POST", STAGED + "/" + upload + "/complete", token=token)

def cancel(token, upload):
    return m9.upload_request("DELETE", STAGED + "/" + upload, token=token)

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--evidence", required=True, type=pathlib.Path)
    args=parser.parse_args(); args.evidence.parent.mkdir(parents=True,exist_ok=True)
    # Reuse the existing H5 test including READY, quote, order, simulated payment, task and receipt.
    subprocess.run([sys.executable, str(pathlib.Path(m9.__file__)), "--evidence",
        str(args.evidence.with_name(args.evidence.stem + "-h5.json"))], check=True, stdout=subprocess.DEVNULL)
    token=h.successful(h.request("POST",APP+"/sessions"))["accessToken"]
    other=h.successful(h.request("POST",APP+"/sessions"))["accessToken"]
    run=secrets.token_hex(12); key="m9-weixin-"+run
    images=[m9.png(120,80,(240,110,20)),m9.png(80,120,(30,90,250))]
    upload=None
    try:
        view=h.successful(create(token,key,images)); upload=view["uploadId"]
        assert view["state"]=="OPEN" and view["uploadedIndexes"]==[] and view["fileCount"]==2
        replay=h.successful(create(token,key,images)); assert replay["uploadId"]==upload
        m9.expect_error(create(token,key,list(reversed(images))),409,1935004)
        m9.expect_error(complete(other,upload),404,1935003)
        body,mime=pre.multipart("file","fixture.png","image/png",images[0])
        m9.expect_error(part(other,upload,0,images[0]),404,1935003)
        m9.expect_error(cancel(other,upload),404,1935003)
        m9.expect_error(complete(token,upload),409,1935005)
        view=h.successful(part(token,upload,0,images[0])); assert view["uploadedIndexes"]==[0]
        view=h.successful(part(token,upload,0,images[0])); assert view["uploadedIndexes"]==[0]
        m9.expect_error(part(token,upload,0,images[1]),409,1935004)
        h.successful(part(token,upload,1,images[1]))
        view=h.successful(complete(token,upload)); assert view["state"]=="COMPLETE"
        result=pre.ready(token,view["result"])
        replay=h.successful(complete(token,upload)); assert replay["result"]["id"]==result["id"]
        replay=h.successful(create(token,key,images)); assert replay["result"]["id"]==result["id"]
        body,mime=m9.multipart(images)
        same=h.successful(m9.upload_request("POST",APP+"/image-collages",token=token,body=body,
            content_type=mime,headers={"Idempotency-Key":key})); assert same["id"]==result["id"]
        body,mime=m9.multipart(list(reversed(images)))
        m9.expect_error(m9.upload_request("POST",APP+"/image-collages",token=token,body=body,
            content_type=mime,headers={"Idempotency-Key":key}),409,1927007)
        # Drop completed memory session and exercise database final-key replay through a new staging session.
        h.successful(cancel(token,upload)); upload=None
        view=h.successful(create(token,key,images)); upload=view["uploadId"]
        for i,image in enumerate(images): h.successful(part(token,upload,i,image))
        replay=h.successful(complete(token,upload)); assert replay["result"]["id"]==result["id"]
        h.successful(cancel(token,upload)); upload=None
        view=h.successful(create(token,key,list(reversed(images)))); upload=view["uploadId"]
        for i,image in enumerate(reversed(images)): h.successful(part(token,upload,i,image))
        m9.expect_error(complete(token,upload),409,1927007)
        m9.expect_error(h.request("POST",APP+"/sessions",headers={"Origin":"",
            "Referer":"https://servicewechat.com/wx0000000000000000/devtools/page-frame.html"}),403,1926008)
        m9.expect_error(h.request("POST",STAGED,token=token,body=json.dumps(manifest(images)).encode(),
            content_type="application/json",headers={"Origin":"", "Idempotency-Key":key,
            "Referer":"https://servicewechat.com/wx0000000000000000/devtools/page-frame.html"}),403,1926008)
        for origin in ("", "https://invalid.example"):
            m9.expect_error(h.request("POST",APP+"/sessions",headers={"Origin":origin}),403,1926008)
        report={"status":"PASS","h5ExistingLoop":"PASS","createReplay":True,"partReplay":True,
            "manifestOrderConflict":True,"crossPrincipalHidden":True,"incompleteRejected":True,
            "completeReplay":True,"sameKeyCrossTransportSameFile":True,"databaseReplaySameFile":True,
            "differentOrderDatabaseConflict":True,"nativeDisabled":True,
            "boundaries":{"wechatDevtools":"NOT_RUN","wechatDevice":"NOT_RUN","externalAcceptance":"NOT_RUN",
                "realPayment":"NOT_RUN","realPrinter":"NOT_RUN"}}
        args.evidence.write_text(json.dumps(report,indent=2)+"\n")
        print(json.dumps(report))
    finally:
        if upload is not None:
            response=cancel(token,upload)
            if response[0]!=200 and response[0]!=404: raise h.VerificationFailure("staged_cleanup_failed")

if __name__ == "__main__": main()
