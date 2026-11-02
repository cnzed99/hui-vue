#!/usr/bin/env python3
"""Read-only engineering candidate checks; no service, DB or HTTP operations."""
import ast
import hashlib
import json
import pathlib
import subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]
CANDIDATE=ROOT/"ops/runtime/candidates/m9-weixin"
BASE=pathlib.Path("/home/ubuntu/print-system/runtime/worktrees")

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def check_tree(tree,manifest):
    rows={line.split("  ",1)[1].removeprefix("./"):line.split("  ",1)[0] for line in manifest.read_text().splitlines()}
    files={str(p.relative_to(tree)):p for p in tree.rglob("*") if p.is_file()}
    assert set(files)==set(rows)
    assert not any(p.is_symlink() for p in tree.rglob("*"))
    for name,path in files.items(): assert sha(path)==rows[name]

def main():
    for line in (CANDIDATE/"SHA256SUMS").read_text().splitlines():
        digest,name=line.split("  ",1); assert sha(CANDIDATE/name)==digest
    m=json.loads((CANDIDATE/"manifest.json").read_text())
    backend=BASE/"m9-weixin-upload-backend-20261102"
    mini=BASE/"m9-weixin-upload-miniapp-20261102"
    for tree,key in [(backend,"backendCommit"),(mini,"miniappCommit")]:
        assert subprocess.check_output(["git","-C",str(tree),"rev-parse","HEAD"],text=True).strip()==m[key]
    assert sha(backend/"yudao-server/target/yudao-server.jar")==m["backendSha256"]
    assert sha(mini/"dist/build/h5/index.html")==m["h5IndexSha256"]
    assert sha(mini/"dist/build/mp-weixin/app.json")==m["mpAppJsonSha256"]
    check_tree(mini/"dist/build/h5",CANDIDATE/"miniapp-SHA256SUMS")
    check_tree(mini/"dist/build/mp-weixin",CANDIDATE/"mp-weixin-SHA256SUMS")
    site=(CANDIDATE/"nginx-print-system.conf").read_text()
    assert "location ^~ /app-api/" not in site
    assert "location /app-api/ { return 404; }" in site
    blocks=site.split("    location ")[1:]
    staged=[b for b in blocks if "image-collage-upload-sessions" in b.split("\n",1)[0]]
    assert len(staged)==4
    for b in staged:
        assert "access_log off;" in b
        assert "print_miniapp_collage_staged_rate burst=12 nodelay" in b
        assert "proxy_set_header Origin $http_origin;" in b
        assert "proxy_set_header Referer $http_referer;" in b
        assert "client_max_body_size " + ("3m;" if "/parts/" in b.split("\n",1)[0] else "16k;") in b
    h5=next(b for b in blocks if "location" not in b.split("\n",1)[0] and "= /app-api/print/miniapp/demo/image-collages {" in b.split("\n",1)[0])
    part=next(b for b in staged if "/parts/" in b.split("\n",1)[0])
    for b in [h5,part]:
        assert "limit_conn print_miniapp_collage_global 2;" in b
        assert "limit_conn print_miniapp_collage_ip 1;" in b
        assert "client_body_temp_path /var/lib/nginx/print-miniapp-collage-body 1 2;" in b
    assert "client_max_body_size 13m;" in h5
    for name in ["60-m9-weixin-upload.conf","60-m9-weixin-upload-disabled.conf"]:
        text=(CANDIDATE/name).read_text()
        assert 'PRINT_MINIAPP_DEMO_WECHAT_NATIVE_ENABLED=false' in text
        assert 'PRINT_MINIAPP_DEMO_WECHAT_APP_ID=' in text
    assert 'STAGED_UPLOAD_ENABLED=true' in (CANDIDATE/"60-m9-weixin-upload.conf").read_text()
    assert 'STAGED_UPLOAD_ENABLED=false' in (CANDIDATE/"60-m9-weixin-upload-disabled.conf").read_text()
    for name in ["deploy-m9-weixin-upload.sh","rollback-m9-weixin-upload.sh"]:
        subprocess.run(["bash","-n",str(ROOT/"ops/runtime"/name)],check=True)
    for name in ["deploy-m9-weixin-upload.sh", "rollback-m9-weixin-upload.sh"]:
        source=(ROOT/"ops/runtime"/name).read_text()
        rollback=source[source.index("rollback() {"):]
        assert rollback.index("systemctl stop print-backend.service") < rollback.index("m9_weixin_cleanup_upload_scratch.py") < rollback.index('atomic_backend_link "$PREVIOUS_BACKEND"') < rollback.index("systemctl restart print-backend.service")
        assert 'if ! python3' in rollback and 'M9_WEIXIN_ROLLBACK_SCRATCH_CLEANUP_FAILED' in rollback
        assert 'failed=1' in rollback and 'rc=70' in rollback
    cleanup=ROOT/"ops/runtime/m9_weixin_cleanup_upload_scratch.py"
    ast.parse(cleanup.read_text(),filename=str(cleanup))
    assert 'fcntl.lockf(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)' in cleanup.read_text()
    for path in pathlib.Path(__file__).parent.glob("m9_weixin_*.py"): ast.parse(path.read_text(),filename=str(path))
    print(json.dumps({"status":"PASS_SOURCE_ONLY","artifacts":"EXACT_HASH_MATCH","h5AndMpTrees":"EXACT_MANIFEST_MATCH",
        "nginxBoundaries":"SOURCE_CHECKED","shellSyntax":"PASS","pythonSyntax":"PASS","runtime":"NOT_RUN"}))

if __name__=="__main__": main()
