#!/usr/bin/env python3
"""Isolated cloud fixtures and shell stubs; never stops services or touches real demo storage."""
import importlib.util
import json
import os
import pathlib
import subprocess
import tempfile
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("cleanup",ROOT/"ops/runtime/m9_weixin_cleanup_upload_scratch.py")
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
SESSION="11111111-1111-1111-1111-111111111111"

def fixture(base):
    root=base/"storage";root.mkdir(mode=0o700)
    (root/".worker.lock").touch(mode=0o600)
    for name in ["objects","incoming","parser-scratch"]:
        p=root/name;p.mkdir(mode=0o700);(p/"keep").write_text("preserve")
    target=root/m.NAMESPACE;target.mkdir(mode=0o700)
    session=target/SESSION;session.mkdir(mode=0o700)
    part=session/"part-0";part.touch(mode=0o600);part.write_bytes(b"fixture")
    return root,part

def expect_failure(call):
    try: call()
    except Exception: return
    raise AssertionError("expected_cleanup_failure")

def shell_case(path,cleanup_rc,stop_rc,base):
    source=path.read_text();start=source.index("rollback() {")
    end=source.find("\ntrap rollback EXIT",start)
    if end<0: end=source.rfind("\nrollback")
    function=source[start:end]
    stub="""set -Eeuo pipefail
ROOT=/unused
RUN_ID=fixture
SUCCESS=0
BACKUP=BACKUP_VALUE
PREVIOUS_BACKEND=/unused/backend
PREVIOUS_MINIAPP=/unused/miniapp
sudo() {
  if [[ $1 == -n && $2 == systemctl && $3 == stop ]]; then echo STOP >&2; return STOP_RC; fi
  if [[ $1 == -n && $2 == systemctl && $3 == restart ]]; then echo RESTART >&2; fi
  return 0
}
python3() { echo CLEANUP >&2; return CLEANUP_RC; }
atomic_backend_link() { echo LINK >&2; }
atomic_miniapp_link() { return 0; }
wait_backend() { return 0; }
systemctl() { return 0; }
""".replace("BACKUP_VALUE",str(base)).replace("STOP_RC",str(stop_rc)).replace("CLEANUP_RC",str(cleanup_rc))
    (base/"dropin.state").write_text("absent\n")
    result=subprocess.run(["bash"],input=stub+function+"\nrollback\n",text=True,capture_output=True)
    expected=70 if cleanup_rc or stop_rc else (1 if path.name.startswith("deploy") else 0)
    assert result.returncode==expected,(path.name,result.returncode,result.stderr)
    log=result.stderr
    assert log.index("STOP")<log.index("LINK")<log.index("RESTART")
    if not stop_rc: assert log.index("STOP")<log.index("CLEANUP")<log.index("LINK")
    else: assert "CLEANUP" not in log
    if expected==70:
        assert "ROLLBACK_FAILED" in log and "DEPLOY_ROLLED_BACK" not in log
    else: assert "DEPLOY_ROLLED_BACK" in log

def main():
    checks=[]
    with tempfile.TemporaryDirectory(prefix="m9-weixin-cleanup-check-",dir="/home/ubuntu/print-system/runtime/build-tmp") as tmp:
        base=pathlib.Path(tmp)
        for scenario in ["success","stopped_failure","lock_contention","symlink","root_symlink","namespace_symlink","hardlink","permissions","io_failure"]:
            case=base/scenario;case.mkdir();root,part=fixture(case)
            stopped=lambda:None
            if scenario=="success":
                m.cleanup(root,stopped);assert not (root/m.NAMESPACE).exists()
                for name in ["objects","incoming","parser-scratch"]: assert (root/name/"keep").read_text()=="preserve"
                m.cleanup(root,stopped) # already absent is safe after lock/stopped gates
            elif scenario=="stopped_failure":
                def blocked(): raise RuntimeError("not_stopped")
                expect_failure(lambda:m.cleanup(root,blocked));assert part.exists()
            elif scenario=="lock_contention":
                child=subprocess.Popen(["python3","-c","import fcntl,sys; f=open(sys.argv[1],'r+'); fcntl.lockf(f,fcntl.LOCK_EX); print('locked',flush=True); sys.stdin.read()",str(root/".worker.lock")],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
                try:
                    assert child.stdout.readline().strip()=="locked"
                    expect_failure(lambda:m.cleanup(root,stopped));assert part.exists()
                finally: child.communicate("")
            elif scenario=="symlink":
                outside=case/"outside";outside.write_text("preserve")
                part.unlink();part.symlink_to(outside)
                expect_failure(lambda:m.cleanup(root,stopped));assert outside.read_text()=="preserve" and part.is_symlink()
            elif scenario=="root_symlink":
                alias=case/"alias";alias.symlink_to(root,target_is_directory=True)
                expect_failure(lambda:m.cleanup(alias,stopped));assert part.exists()
            elif scenario=="namespace_symlink":
                target=root/m.NAMESPACE;renamed=case/"outside-namespace";target.rename(renamed)
                target.symlink_to(renamed,target_is_directory=True)
                expect_failure(lambda:m.cleanup(root,stopped));assert (renamed/SESSION/"part-0").exists()
            elif scenario=="hardlink":
                outside=case/"outside-part";os.link(part,outside)
                expect_failure(lambda:m.cleanup(root,stopped));assert part.exists() and outside.read_bytes()==b"fixture"
            elif scenario=="permissions":
                part.chmod(0o644);expect_failure(lambda:m.cleanup(root,stopped));assert part.exists()
            else:
                original=pathlib.Path.unlink
                def denied(path,*a,**k):
                    if path==part: raise OSError("fixture_unlink_failed")
                    return original(path,*a,**k)
                with patch.object(pathlib.Path,"unlink",denied): expect_failure(lambda:m.cleanup(root,stopped))
                assert part.exists()
            checks.append(scenario)
        for name in ["deploy-m9-weixin-upload.sh","rollback-m9-weixin-upload.sh"]:
            for cleanup_rc,stop_rc in [(0,0),(70,0),(0,1)]:
                shell_case(ROOT/"ops/runtime"/name,cleanup_rc,stop_rc,base)
        checks.append("both_shell_rollback_order_and_failure_propagation")
    print(json.dumps({"status":"PASS_ISOLATED_FIXTURES","checks":checks,"productionStorage":"NOT_TOUCHED","serviceOperations":"NOT_RUN"}))

if __name__=="__main__":main()
