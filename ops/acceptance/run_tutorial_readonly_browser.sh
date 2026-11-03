#!/bin/bash
set -Eeuo pipefail
BASE=/home/ubuntu/print-system/runtime/build-tmp
PROFILE="/tmp/print-tutorial-smoke-$(date -u +%Y%m%dT%H%M%SZ)-$$"
NODE=/home/ubuntu/print-system/runtime/node/node-v22.23.4-linux-x64/bin/node
SYSROOT=/home/ubuntu/print-system/runtime/browser-tools/sysroot/usr/lib/x86_64-linux-gnu
STATUS=0
resolved=$(realpath -m -- "$PROFILE")
[[ "$resolved" == /tmp/print-tutorial-smoke-* && ! -e "$PROFILE" && ! -L "$PROFILE" ]]
export LD_LIBRARY_PATH="$SYSROOT"
export SMOKE_PROFILE="$PROFILE"
timeout --signal=TERM --kill-after=5s 60s setsid "$NODE" "$BASE/tutorial-readonly-smoke.cjs" || STATUS=$?
mapfile -t pids < <(pgrep -f -- "$PROFILE" || true)
for pid in "${pids[@]}"; do
  pgid=$(ps -o pgid= -p "$pid" | tr -d ' ' || true)
  [[ -n "$pgid" ]] && kill -TERM -- "-$pgid" 2>/dev/null || true
done
sleep 2
mapfile -t pids < <(pgrep -f -- "$PROFILE" || true)
for pid in "${pids[@]}"; do
  pgid=$(ps -o pgid= -p "$pid" | tr -d ' ' || true)
  [[ -n "$pgid" ]] && kill -KILL -- "-$pgid" 2>/dev/null || true
done
mapfile -t remaining < <(pgrep -f -- "$PROFILE" || true)
if (( ${#remaining[@]} > 0 )); then STATUS=1; else [[ ! -L "$PROFILE" ]] && rm -rf -- "$PROFILE"; fi
printf 'smoke_status=%s\nprocesses_remaining=%s\n' "$STATUS" "${#remaining[@]}"
exit "$STATUS"
