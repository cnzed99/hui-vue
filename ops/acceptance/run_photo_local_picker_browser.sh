#!/bin/bash
set -Eeuo pipefail

BASE=/home/ubuntu/print-system/runtime/build-tmp
SCRIPT="$BASE/photo-local-picker-smoke.cjs"
PROFILE="/tmp/print-photo-picker-smoke-$(date -u +%Y%m%dT%H%M%SZ)-$$"
FAILURE_DIR="$BASE/photo-local-picker-smoke-failure"
NODE=/home/ubuntu/print-system/runtime/node/node-v22.23.4-linux-x64/bin/node
SYSROOT=/home/ubuntu/print-system/runtime/browser-tools/sysroot/usr/lib/x86_64-linux-gnu
STATUS=0

cleanup_processes() {
  mapfile -t pids < <(pgrep -f -- "$PROFILE" || true)
  for pid in "${pids[@]}"; do
    [[ -n "$pid" ]] || continue
    pgid=$(ps -o pgid= -p "$pid" | tr -d ' ' || true)
    [[ -n "$pgid" ]] && kill -TERM -- "-$pgid" 2>/dev/null || true
  done
  sleep 2
  mapfile -t pids < <(pgrep -f -- "$PROFILE" || true)
  for pid in "${pids[@]}"; do
    [[ -n "$pid" ]] || continue
    pgid=$(ps -o pgid= -p "$pid" | tr -d ' ' || true)
    [[ -n "$pgid" ]] && kill -KILL -- "-$pgid" 2>/dev/null || true
  done
}

resolved=$(realpath -m -- "$PROFILE")
[[ "$resolved" == /tmp/print-photo-picker-smoke-* ]]
[[ ! -e "$PROFILE" && ! -L "$PROFILE" ]]
failure_resolved=$(realpath -m -- "$FAILURE_DIR")
[[ "$failure_resolved" == /home/ubuntu/print-system/runtime/build-tmp/photo-local-picker-smoke-failure ]]
[[ ! -L "$FAILURE_DIR" ]]
rm -rf -- "$FAILURE_DIR"

export LD_LIBRARY_PATH="$SYSROOT"
export SMOKE_PROFILE="$PROFILE"
export SMOKE_FAILURE_DIR="$FAILURE_DIR"
timeout --signal=TERM --kill-after=5s 60s setsid "$NODE" "$SCRIPT" || STATUS=$?
cleanup_processes

mapfile -t remaining_pids < <(pgrep -f -- "$PROFILE" || true)
remaining_count=${#remaining_pids[@]}
if (( remaining_count > 0 )); then
  echo 'PROFILE_PROCESS_LEAK' >&2
  STATUS=1
fi
if (( remaining_count == 0 )) && [[ -e "$PROFILE" ]]; then
  [[ ! -L "$PROFILE" ]]
  rm -rf -- "$PROFILE"
fi
if (( remaining_count == 0 )); then
  [[ ! -e "$PROFILE" ]] || STATUS=1
fi

printf 'smoke_status=%s\nprofile=%s\nprocesses_remaining=%s\n' "$STATUS" "$PROFILE" "$remaining_count"
exit "$STATUS"
