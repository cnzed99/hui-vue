#!/usr/bin/env python3
"""No network: verify explicit bind, serial response reads, gate order and fatal 429 cleanup."""
import json
import pathlib
from unittest.mock import patch
import m9_weixin_edge_admission_smoke as e

class Connection:
    def __init__(self,*args,**kwargs):
        self.source=kwargs["source_address"];self.closed=False;self.events=[];self.response_read=True
        self.sock=self
    def connect(self):self.events.append("connect")
    def getsockname(self):return self.source
    def close(self):self.closed=True
    def request(self,*args,**kwargs):
        assert self.response_read
        self.response_read=False;self.events.append("request")
    def getresponse(self):
        connection=self
        class Response:
            status=200
            def read(self):
                connection.response_read=True;connection.events.append("read")
                return b'{"code":0,"data":true}'
        return Response()

def main():
    with patch.object(e.http.client,"HTTPConnection",Connection):
        connection=e.bound_connection("127.0.0.5")
        assert connection.source==("127.0.0.5",0)
        for _ in range(2):e.request("127.0.0.5","POST","/fixture",connection=connection)
        assert connection.events==["connect","request","read","request","read"] and not connection.closed
        connection.close()
    calls=[];connection=Connection(source_address=("127.0.0.5",0))
    def response(source,method,path,**kwargs):
        assert source=="127.0.0.5" and kwargs.get("connection") is connection
        calls.append((method,path))
        if path.endswith("/sessions"):data={"accessToken":"fixture"}
        elif method=="DELETE":data=True
        elif path.endswith("/complete"):data={"state":"COMPLETE","result":{"id":"fixture","status":"READY","pageCount":1}}
        elif "/parts/" in path:data={"uploadedIndexes":list(range(int(path.rsplit("/",1)[1])+1))}
        else:data={"uploadId":"fixture"}
        return 200,{"code":0,"data":data}
    with patch.object(e,"bound_connection",lambda source:connection),patch.object(e,"request",response),patch.object(e.pre,"ready",lambda *a:None):
        result=e.nine_image_burst()
        assert result["requests"]==11 and result["retryCount"]==0 and len(calls)==13 and connection.closed
    calls.clear();connection.closed=False
    def reject(source,method,path,**kwargs):
        if "/parts/" in path:
            calls.append((method,path));return 429,None
        return response(source,method,path,**kwargs)
    with patch.object(e,"bound_connection",lambda source:connection),patch.object(e,"request",reject):
        try:e.nine_image_burst()
        except e.staged.h.VerificationFailure:pass
        else:raise AssertionError("429_must_be_fatal")
        assert sum("/parts/" in path for _,path in calls)==1
        assert calls[-1][0]=="DELETE" and connection.closed
    deploy=(pathlib.Path(__file__).resolve().parents[2]/"ops/runtime/deploy-m9-weixin-upload.sh").read_text()
    assert deploy.index('python3 "$ENGINEERING/ops/acceptance/m9_weixin_edge_admission_smoke.py"')<deploy.index('python3 "$ENGINEERING/ops/acceptance/m9_weixin_runtime_smoke.py"')
    print(json.dumps({"status":"PASS_ISOLATED_MOCK","sourceAddress":"127.0.0.5","oneSerialConnection":True,
        "responseFullyReadBeforeNextRequest":True,"elevenRequestsNoRetry":True,"http429FatalSingleAttempt":True,
        "cleanupAndCloseOnFailure":True,"edgeGateBeforeRuntime":True,"runtimeHttp":"NOT_RUN"}))

if __name__=="__main__":main()
