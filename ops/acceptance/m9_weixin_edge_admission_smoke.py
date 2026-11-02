#!/usr/bin/env python3
"""Synthetic loopback edge gates. No original data, child processes, or local image files."""
import argparse
import http.client
import json
import pathlib
import secrets
import socket
import time
import m9_weixin_runtime_smoke as staged
import m9_pre_enable_regression as pre

HOST="127.0.0.1"
PORT=18080
H5=staged.APP+"/image-collages"
PART=staged.STAGED+"/11111111-1111-1111-1111-111111111111/parts/0"


def bound_connection(source):
    connection=http.client.HTTPConnection(HOST,PORT,timeout=30,source_address=(source,0))
    try:
        connection.connect()
        if connection.sock.getsockname()[0]!=source: raise RuntimeError("source_address_not_bound")
        return connection
    except BaseException:
        connection.close();raise


def request(source,method,path,*,token=None,body=b"",content_type=None,key=None,connection=None):
    headers={"Host":HOST,"Origin":"http://127.0.0.1:18080","Accept":"application/json"}
    if token: headers["Authorization"]="Bearer "+token
    if content_type: headers["Content-Type"]=content_type
    if key: headers["Idempotency-Key"]=key
    owned=connection is None
    if owned: connection=bound_connection(source)
    try:
        connection.request(method,path,body=body,headers=headers)
        response=connection.getresponse();raw=response.read()
        try: payload=json.loads(raw)
        except (ValueError,UnicodeError): payload=None
        return response.status,payload
    finally:
        if owned: connection.close()


def nine_image_burst():
    # A dedicated source IP ensures historical smoke traffic cannot spend this rate budget.
    source="127.0.0.5"
    connection=bound_connection(source)
    images=[staged.m9.png(32+i,40+i,(20*i,100,220-i)) for i in range(9)]
    key="m9-nine-burst-"+secrets.token_hex(12)
    upload=None;statuses=[];started=time.monotonic()
    def send(method,path,**kwargs):
        response=request(source,method,path,token=token,connection=connection,**kwargs)
        statuses.append(response[0])
        # No retry is allowed in the 11-request burst. HTTP or envelope failure is fatal.
        return staged.h.successful(response)
    try:
        # One serial keepalive connection avoids racing the previous TCP connection's edge cleanup.
        token=staged.h.successful(request(source,"POST",staged.APP+"/sessions",connection=connection))["accessToken"]
        view=send("POST",staged.STAGED,body=json.dumps(staged.manifest(images)).encode(),
            content_type="application/json",key=key)
        upload=view["uploadId"]
        for index,image in enumerate(images):
            body,mime=pre.multipart("file","synthetic.png","image/png",image)
            view=send("POST",staged.STAGED+"/"+upload+"/parts/"+str(index),body=body,content_type=mime)
            if view["uploadedIndexes"]!=list(range(index+1)): raise RuntimeError("nine_upload_indexes")
        view=send("POST",staged.STAGED+"/"+upload+"/complete")
        if view["state"]!="COMPLETE": raise RuntimeError("nine_complete_state")
        if len(statuses)!=11 or statuses!=[200]*11: raise RuntimeError("eleven_request_burst_failed")
        elapsed=round(time.monotonic()-started,3)
        pre.ready(token,view["result"])
        return {"requests":11,"httpStatuses":statuses,"retryCount":0,"pageCount":1,"status":"READY","elapsedSeconds":elapsed,"sourceAddress":source,"singleSerialConnection":True}
    finally:
        try:
            if upload is not None:
                # Cleanup is outside the measured burst and never replaces the final key.
                response=request(source,"DELETE",staged.STAGED+"/"+upload,token=token,connection=connection)
                if response[0] not in (200,404): raise RuntimeError("nine_session_cleanup_failed")
        finally: connection.close()


def hold(source,path):
    connection=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
    try:
        connection.bind((source,0));connection.settimeout(3);connection.connect((HOST,PORT))
        boundary="m9-edge-slow"
        headers=(f"POST {path} HTTP/1.1\r\nHost: {HOST}\r\nOrigin: http://127.0.0.1:18080\r\n"
            "Connection: close\r\nContent-Length: 131072\r\n"
            f"Content-Type: multipart/form-data; boundary={boundary}\r\n\r\n")
        connection.sendall(headers.encode()+f"--{boundary}\r\n".encode())
        pending(connection)
        return connection
    except BaseException:
        connection.close();raise


def pending(connection):
    connection.settimeout(.25)
    try:
        connection.recv(1,socket.MSG_PEEK)
    except socket.timeout: return
    raise RuntimeError("slow_request_not_admitted_or_closed")


def mixed_edge_budget():
    # Headers are complete, bodies incomplete: nginx has evaluated limit_conn and waits in buffering.
    # Different rate zones and fresh source IPs prevent a rate-limit false positive.
    sockets=[]
    try:
        sockets.append(hold("127.0.0.2",H5))
        if request("127.0.0.2","POST",PART,body=b"x",content_type="multipart/form-data; boundary=m9")[0]!=429:
            raise RuntimeError("mixed_per_ip_limit_not_shared")
        sockets.append(hold("127.0.0.3",PART))
        for route in [H5,PART]:
            if request("127.0.0.4","POST",route,body=b"x",content_type="multipart/form-data; boundary=m9")[0]!=429:
                raise RuntimeError("mixed_global_limit_not_shared")
        for connection in sockets: pending(connection)
    finally:
        for connection in sockets: connection.close()
    # Confirm closed requests release the shared slots. No auth or complete file is sent.
    # An admitted unknown staged session with a valid single part reaches the backend and returns 401.
    time.sleep(.3)
    body,mime=pre.multipart("file","synthetic.png","image/png",pre.png())
    response=request("127.0.0.4","POST",PART,body=body,content_type=mime)
    if response[0]!=401 or not isinstance(response[1],dict) or response[1].get("code")!=1926003:
        raise RuntimeError("edge_slots_not_released")
    return {"sameIpMixedUploadLimit":1,"crossIpMixedGlobalLimit":2,
        "thirdH5Http":429,"thirdPartHttp":429,"postCloseUnauthenticatedPartHttp":401,"allSocketsClosed":True}


def main():
    parser=argparse.ArgumentParser();parser.add_argument("--evidence",type=pathlib.Path,required=True)
    args=parser.parse_args()
    report={"status":"PASS","nineImageBurst":nine_image_burst(),"mixedEdgeBudget":mixed_edge_budget(),
        "boundaries":{"syntheticLoopbackHttpOnly":True,"wechatDevice":"NOT_RUN","externalAcceptance":"NOT_RUN"}}
    args.evidence.parent.mkdir(parents=True,exist_ok=True)
    args.evidence.write_text(json.dumps(report,indent=2)+"\n");print(json.dumps(report))

if __name__=="__main__":main()
