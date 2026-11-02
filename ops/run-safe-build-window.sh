#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=/home/ubuntu/print-system
LOCK="$ROOT/runtime/build-tmp/.build.lock"
MIN_FREE_BYTES=10737418240
SERVICES=(print-backend.service nginx redis-server mysql)
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

usage() {
  echo 'usage: run-safe-build-window.sh <label> -- <command> [args...]' >&2
  exit 64
}

[[ $# -ge 3 && $2 == -- ]] || usage
LABEL=$1
shift 2
[[ $LABEL =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || usage
[[ $# -gt 0 ]] || usage
COMMAND=("$@")

mkdir -p -- "$ROOT/runtime/build-tmp" "$ROOT/evidence/disk"
touch -- "$LOCK"
[[ ! -L $LOCK && $(realpath -e -- "$LOCK") == "$LOCK" ]]
exec 9>>"$LOCK"
if ! flock -n 9; then
  echo 'BUILD_ALREADY_RUNNING_STOP'
  exit 75
fi

FREE_BEFORE=$(df -PB1 "$ROOT" | awk 'NR==2 {print $4}')
if (( FREE_BEFORE < MIN_FREE_BYTES )); then
  echo 'LOW_DISK_STOP'
  exit 76
fi

SCRATCH=$(mktemp -d "$ROOT/runtime/build-tmp/${LABEL}-XXXXXXXX")
[[ ! -L $SCRATCH ]]
SCRATCH_REAL=$(realpath -e -- "$SCRATCH")
[[ $(dirname -- "$SCRATCH_REAL") == "$ROOT/runtime/build-tmp" ]]
ORIGINAL_ACTIVE="$SCRATCH_REAL/services.active"
for service in "${SERVICES[@]}"; do
  if systemctl is-active --quiet "$service"; then printf '%s\n' "$service" >> "$ORIGINAL_ACTIVE"; fi
done
touch -- "$ORIGINAL_ACTIVE"

BUILD_RC=1
RECOVERY_RC=0
HEALTHY=0
SCRATCH_REMOVED=0
BUILD_PID=''
BUILD_PGID=''

was_active() { grep -Fxq -- "$1" "$ORIGINAL_ACTIVE"; }

wait_active() {
  local service=$1
  for _ in $(seq 1 45); do
    systemctl is-active --quiet "$service" && return 0
    sleep 1
  done
  return 1
}

wait_mysql() {
  for _ in $(seq 1 40); do
    systemctl is-active --quiet mysql && mysqladmin ping --silent >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

wait_redis() {
  local response
  for _ in $(seq 1 40); do
    if systemctl is-active --quiet redis-server; then
      response=$(timeout 3 redis-cli ping 2>&1 || true)
      [[ $response == PONG || $response == *'NOAUTH Authentication required.'* ]] && return 0
    fi
    sleep 1
  done
  return 1
}

start_original() {
  local service=$1
  was_active "$service" || return 0
  if ! sudo -n systemctl start "$service"; then RECOVERY_RC=1; return 1; fi
  if ! wait_active "$service"; then RECOVERY_RC=1; return 1; fi
}

recover() {
  set +e
  if was_active mysql; then
    sudo -n systemctl start mysql || RECOVERY_RC=1
    wait_mysql || RECOVERY_RC=1
  fi
  if was_active redis-server; then
    sudo -n systemctl start redis-server || RECOVERY_RC=1
    wait_redis || RECOVERY_RC=1
  fi
  start_original nginx || true
  if was_active nginx; then
    nginx_ok=0
    for _ in $(seq 1 15); do
      code=$(curl --silent --show-error --max-time 5 -o /dev/null --write-out '%{http_code}' http://127.0.0.1:18080/login 2>/dev/null || true)
      [[ $code =~ ^[23][0-9][0-9]$ ]] && { nginx_ok=1; break; }
      sleep 1
    done
    (( nginx_ok == 1 )) || RECOVERY_RC=1
  fi
  start_original print-backend.service || true
  if was_active print-backend.service; then
    for _ in $(seq 1 90); do
      if curl --fail --silent --show-error --max-time 3 \
          http://127.0.0.1:48080/admin-api/system/auth/get-permission-info >/dev/null 2>&1; then
        HEALTHY=1
        break
      fi
      sleep 1
    done
    (( HEALTHY == 1 )) || RECOVERY_RC=1
  else
    HEALTHY=1
  fi
}

cleanup() {
  [[ ! -L $SCRATCH_REAL ]] || return 1
  [[ $(dirname -- "$SCRATCH_REAL") == "$ROOT/runtime/build-tmp" ]] || return 1
  [[ $(basename -- "$SCRATCH_REAL") == "$LABEL-"???????? ]] || return 1
  rm -rf --one-file-system -- "$SCRATCH_REAL" || return 1
  [[ ! -e $SCRATCH_REAL ]] || return 1
  SCRATCH_REMOVED=1
}

terminate_build() {
  [[ -n $BUILD_PID && -n $BUILD_PGID ]] || return 0
  local pid=$BUILD_PID
  local pgid=$BUILD_PGID
  if kill -0 -- "-$pgid" 2>/dev/null; then
    kill -TERM -- "-$pgid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 -- "-$pgid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 -- "-$pgid" 2>/dev/null; then
      kill -KILL -- "-$pgid" 2>/dev/null || true
      for _ in $(seq 1 5); do
        kill -0 -- "-$pgid" 2>/dev/null || break
        sleep 1
      done
    fi
  fi
  wait "$pid" 2>/dev/null || true
  if kill -0 -- "-$pgid" 2>/dev/null; then return 1; fi
  BUILD_PID=''
  BUILD_PGID=''
}

finish() {
  local caught=$?
  trap - EXIT INT TERM
  (( BUILD_RC == 1 && caught != 0 )) && BUILD_RC=$caught
  terminate_build || RECOVERY_RC=1
  recover
  cleanup || RECOVERY_RC=1
  local free_after
  free_after=$(df -PB1 "$ROOT" | awk 'NR==2 {print $4}')
  (( free_after >= MIN_FREE_BYTES )) || RECOVERY_RC=1
  if ! python3 - "$ROOT/evidence/disk/${LABEL}-latest.json" "$LABEL" "$FREE_BEFORE" "$free_after" "$BUILD_RC" "$RECOVERY_RC" "$HEALTHY" "$SCRATCH_REMOVED" <<'PY'
import json, pathlib, sys, datetime
path, label, before, after, build, recovery, healthy, scratch_removed = sys.argv[1:]
record = {
    'label': label,
    'finishedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'freeBeforeBytes': int(before),
    'freeAfterBytes': int(after),
    'minimumFreeBytes': 10 * 1024 ** 3,
    'buildExitCode': int(build),
    'recoveryExitCode': int(recovery),
    'backendHttpHealthy': healthy == '1',
    'scratchRemoved': scratch_removed == '1',
}
pathlib.Path(path).write_text(json.dumps(record, indent=2) + '\n')
PY
  then
    RECOVERY_RC=1
  fi
  printf 'SAFE_BUILD_WINDOW build=%s recovery=%s services=' "$BUILD_RC" "$RECOVERY_RC"
  systemctl is-active mysql redis-server nginx print-backend.service | tr '\n' ','
  printf ' backend_http=%s free_after=%s\n' "$HEALTHY" "$free_after"
  if (( BUILD_RC != 0 )); then exit "$BUILD_RC"; fi
  if (( RECOVERY_RC != 0 )); then exit 70; fi
  exit 0
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for service in "${SERVICES[@]}"; do
  if was_active "$service"; then sudo -n systemctl stop "$service"; fi
done

export TMPDIR="$SCRATCH_REAL" TMP="$SCRATCH_REAL" TEMP="$SCRATCH_REAL"
set +e
/usr/bin/setsid /usr/bin/env -i HOME=/home/ubuntu USER=ubuntu LOGNAME=ubuntu LANG=C.UTF-8 \
  PATH="$PATH" TMPDIR="$SCRATCH_REAL" TMP="$SCRATCH_REAL" TEMP="$SCRATCH_REAL" \
  "${COMMAND[@]}" &
BUILD_PID=$!
BUILD_PGID=$BUILD_PID
wait "$BUILD_PID"
BUILD_RC=$?
terminate_build || RECOVERY_RC=1
set -e
exit "$BUILD_RC"
