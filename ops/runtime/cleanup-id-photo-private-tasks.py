#!/usr/bin/env python3
"""Clean abandoned private id-photo tasks while holding the file-worker lock."""

from __future__ import annotations

import argparse
import fcntl
import os
import stat
import sys
from pathlib import Path

LOCK_BUSY = 75
UNSAFE_TARGET = 65
OWNED_DIRECTORIES = ("id-photo-multipart", "id-photo-scratch")


class UnsafeTarget(RuntimeError):
    pass


def require_directory(path: Path) -> Path:
    metadata = path.lstat()
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise UnsafeTarget("storage root must be a real directory")
    return path.resolve(strict=True)


def validate_tree(path: Path) -> None:
    metadata = path.lstat()
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise UnsafeTarget("task target must be a real directory")
    with os.scandir(path) as entries:
        for entry in entries:
            if entry.is_symlink():
                raise UnsafeTarget("symlink found in task target")
            if entry.is_dir(follow_symlinks=False):
                validate_tree(Path(entry.path))
            elif not entry.is_file(follow_symlinks=False):
                raise UnsafeTarget("unsupported filesystem entry in task target")


def delete_tree(path: Path) -> None:
    with os.scandir(path) as entries:
        children = list(entries)
    for entry in children:
        child = Path(entry.path)
        if entry.is_symlink():
            raise UnsafeTarget("symlink appeared during cleanup")
        if entry.is_dir(follow_symlinks=False):
            delete_tree(child)
        elif entry.is_file(follow_symlinks=False):
            child.unlink()
        else:
            raise UnsafeTarget("unsupported filesystem entry appeared during cleanup")
    path.rmdir()


def clean(storage_root: Path) -> int:
    root = require_directory(storage_root)
    lock_path = root / ".worker.lock"
    flags = os.O_RDWR | os.O_CREAT
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    lock_fd = os.open(lock_path, flags, 0o600)
    removed = 0
    try:
        with os.fdopen(lock_fd, "r+b", closefd=True) as lock_file:
            try:
                fcntl.lockf(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print("ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_LOCK_BUSY", file=sys.stderr)
                return LOCK_BUSY

            candidates: list[Path] = []
            for directory_name in OWNED_DIRECTORIES:
                directory = root / directory_name
                if not directory.exists():
                    continue
                metadata = directory.lstat()
                if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
                    raise UnsafeTarget("owned directory is not a real directory")
                if directory.resolve(strict=True).parent != root:
                    raise UnsafeTarget("owned directory escaped the storage root")
                for child in directory.iterdir():
                    if child.name.startswith("task-") and len(child.name) > len("task-"):
                        validate_tree(child)
                        candidates.append(child)

            for candidate in candidates:
                delete_tree(candidate)
                removed += 1
    except UnsafeTarget:
        print("ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_UNSAFE", file=sys.stderr)
        return UNSAFE_TARGET

    print(f"ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_PASS removed={removed}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("storage_root", type=Path)
    args = parser.parse_args()
    try:
        return clean(args.storage_root)
    except (FileNotFoundError, NotADirectoryError, PermissionError, OSError) as failure:
        print(f"ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_ERROR type={type(failure).__name__}", file=sys.stderr)
        return UNSAFE_TARGET


if __name__ == "__main__":
    raise SystemExit(main())
