#!/usr/bin/env python3
"""Rollback-only cleanup. CLI has no path override and never stops/restarts a service."""
import fcntl
import os
import pathlib
import pwd
import re
import stat
import subprocess
import sys

STORAGE_ROOT=pathlib.Path("/home/ubuntu/print-system/runtime/miniapp-demo-files")
NAMESPACE="image-collage-upload-scratch"
UUID=re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
PART=re.compile(r"(?:part-[0-8]|incoming-[0-9]+\.part)")

def require_stopped():
    result=subprocess.run(["systemctl","show","print-backend.service","--property=ActiveState,SubState,MainPID,ControlPID,ControlGroup"],
        check=True,capture_output=True,text=True)
    properties=dict(line.split("=",1) for line in result.stdout.splitlines() if "=" in line)
    if any(properties.get(k)!=v for k,v in {"ActiveState":"inactive","SubState":"dead","MainPID":"0","ControlPID":"0"}.items()):
        raise RuntimeError("backend_not_stopped")
    group=properties.get("ControlGroup", "")
    if group:
        if group!="/system.slice/print-backend.service": raise RuntimeError("unexpected_service_cgroup")
        procs=pathlib.Path("/sys/fs/cgroup")/group.lstrip("/")/"cgroup.procs"
        if procs.exists() and procs.read_text().strip(): raise RuntimeError("service_cgroup_not_empty")

def checked(path,mode,directory):
    info=path.lstat()
    expected=pwd.getpwnam("ubuntu")
    if (info.st_uid,info.st_gid)!=(expected.pw_uid,expected.pw_gid): raise RuntimeError("unexpected_owner")
    if stat.S_IMODE(info.st_mode)!=mode: raise RuntimeError("unexpected_permissions")
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)): raise RuntimeError("unexpected_type")
    if not directory and info.st_nlink!=1: raise RuntimeError("unexpected_hardlink")
    return info

def cleanup(storage_root,stopped=require_stopped):
    # Production main supplies only the fixed STORAGE_ROOT. Tests use private fixture roots.
    root=pathlib.Path(storage_root)
    if not root.is_absolute() or root.resolve(strict=True)!=root: raise RuntimeError("unsafe_storage_root")
    checked(root,0o700,True)
    stopped()
    lock=root/".worker.lock"
    before=checked(lock,0o600,False)
    descriptor=os.open(lock,os.O_WRONLY|os.O_NOFOLLOW)
    try:
        actual=os.fstat(descriptor)
        if (actual.st_dev,actual.st_ino)!=(before.st_dev,before.st_ino): raise RuntimeError("lock_replaced")
        # Java FileChannel.tryLock uses POSIX record locks, not the separate flock lock domain.
        fcntl.lockf(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
        stopped()
        target=root/NAMESPACE
        if not target.exists() and not target.is_symlink(): return
        if target.resolve(strict=True)!=root/NAMESPACE or target.parent!=root: raise RuntimeError("unsafe_namespace")
        checked(target,0o700,True)
        inventory=[]
        # Validate the complete tree before deleting anything; reject unknown levels or names.
        for session in target.iterdir():
            if not UUID.fullmatch(session.name): raise RuntimeError("unexpected_session_name")
            checked(session,0o700,True)
            files=[]
            for part in session.iterdir():
                if not PART.fullmatch(part.name): raise RuntimeError("unexpected_part_name")
                checked(part,0o600,False)
                files.append(part)
            inventory.append((session,files))
        for session,files in inventory:
            for part in files:
                checked(part,0o600,False)
                part.unlink()
            checked(session,0o700,True)
            session.rmdir()
        checked(target,0o700,True)
        target.rmdir()
    finally:
        os.close(descriptor)

def main():
    if len(sys.argv)!=1: raise RuntimeError("no_arguments_allowed")
    cleanup(STORAGE_ROOT)
    print("M9_WEIXIN_STAGED_SCRATCH_CLEANUP_PASS")

if __name__=="__main__":
    try: main()
    except Exception:
        print("M9_WEIXIN_STAGED_SCRATCH_CLEANUP_FAILED",file=sys.stderr)
        raise SystemExit(70)
