#!/bin/bash
set -Eeuo pipefail
BASE=/home/ubuntu/print-system/ops/runtime/frontend-safe-build
PROJECT=/home/ubuntu/print-system
FRONTEND=$PROJECT/src/frontend
NODE=$PROJECT/runtime/node/node-v22.23.4-linux-x64/bin/node
CONTROLLER=print-frontend-safe-controller.service
LOCK=$PROJECT/runtime/build-tmp/.build.lock
SERVICES=(mysql redis-server nginx print-backend)
# Pure response parsers: no service, network or credential access.
redis_listener_ready() {
  local active=$1 rc=$2 reply=${3//$'\r'/}
  [[ $active == active ]] || return 1
  if [[ $reply == PONG && $rc == 0 ]]; then return 0; fi
  # redis-cli --raw -e reports a server error with exit 1. An explicit NOAUTH
  # proves the Redis listener/protocol/auth gate responded, not business login.
  [[ ( $rc == 0 || $rc == 1 ) && ( $reply == 'NOAUTH Authentication required.' || $reply == '(error) NOAUTH Authentication required.' ) ]]
}
nginx_health_url() { printf '%s\n' 'http://127.0.0.1:18080/login'; }
nginx_response_ready() { [[ $1 == 0 && $2 =~ ^[23][0-9]{2}$ ]]; }
require_root() { [[ $EUID == 0 ]] || { echo 'root required' >&2; exit 77; }; }
validate_state() {
  STATE=$(realpath -e -- "$1")
  RUN_ID=${STATE##*/}
  [[ $RUN_ID =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{16}$ ]]
  [[ $STATE == "$BASE/runs/$RUN_ID" && ! -L $1 ]]
  [[ $(stat -c %u "$STATE") == 0 && -z $(find "$STATE" -maxdepth 0 -perm /022 -print) ]]
  MODE=$(cat "$STATE/mode")
  [[ $MODE == real || $MODE == self-test-fail || $MODE == self-test-timeout || $MODE == self-test-success || $MODE == self-test-recovery-deadline ]]
  CHILD="print-frontend-safe-child-$RUN_ID.service"
  LOCK_UNIT="print-frontend-safe-lock-$RUN_ID.service"
  SCRATCH="$BASE/scratch/$RUN_ID"
  [[ $(realpath -m -- "$SCRATCH") == "$BASE/scratch/$RUN_ID" ]]
}
keeper_holds_lock() {
  local pid
  [[ -f $STATE/lock.acquired && ! -f $STATE/lock.released ]] || return 1
  pid=$(timeout 3 systemctl show "$LOCK_UNIT" -p MainPID --value) || return 1
  [[ $pid != 0 && $pid == "$(cat "$STATE/lock.keeper.pid")" ]] || return 1
  timeout 3 systemctl is-active --quiet "$LOCK_UNIT" || return 1
  # This separate open must conflict with the keeper's flock.
  if flock -n "$LOCK" /bin/true; then return 1; fi
}
status_snapshot() {
  local service active sub
  for service in "${SERVICES[@]}"; do
    active=$(timeout --kill-after=1 3 systemctl show "$service" -p ActiveState --value) || return 1
    sub=$(timeout --kill-after=1 3 systemctl show "$service" -p SubState --value) || return 1
    printf '%s %s %s\n' "$service" "$active" "$sub"
  done
}
cg_empty() {
  local cg=$1 f
  [[ -n $cg && $cg == /system.slice/print-frontend-safe-child-* ]] || return 1
  if [[ ! -d /sys/fs/cgroup$cg ]]; then return 0; fi
  while IFS= read -r f; do
    [[ -z $(cat "$f") ]] || return 1
  done < <(find "/sys/fs/cgroup$cg" -name cgroup.procs -type f)
}
