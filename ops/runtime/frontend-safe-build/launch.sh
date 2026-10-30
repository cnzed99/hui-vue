#!/bin/bash
set -Eeuo pipefail
BASE=/home/ubuntu/print-system/ops/runtime/frontend-safe-build
source "$BASE/common.sh"
require_root
case ${1:-} in
  --real) MODE=real; [[ $# == 1 ]] || exit 64 ;;
  --self-test) [[ $# == 2 && ( $2 == fail || $2 == timeout || $2 == success || $2 == recovery-deadline ) ]] || exit 64; MODE="self-test-$2" ;;
  *) echo 'Explicit --real or --self-test success|fail|timeout|recovery-deadline required; default refuses execution.' >&2; exit 64 ;;
esac
# A fixed controller unit serializes the complete lifecycle INCLUDING ExecStopPost.
# The shared legacy flock is held across controller and recovery by the keeper.
[[ $(realpath -e -- "$BASE") == "$BASE" ]]
[[ $(stat -c %u "$BASE") == 0 && $(stat -c %a "$BASE") == 755 ]]
for script in common.sh controller.sh recover.sh build-child.sh config-check.mjs; do
  [[ -f $BASE/$script && ! -L $BASE/$script && $(stat -c %u "$BASE/$script") == 0 ]]
  [[ -z $(find "$BASE/$script" -perm /022 -print) ]]
done
install -d -m 0755 -o root -g root "$BASE/runs" "$BASE/scratch"
for directory in runs scratch; do
  [[ ! -L $BASE/$directory && $(realpath -e "$BASE/$directory") == "$BASE/$directory" ]]
  [[ $(stat -c %u "$BASE/$directory") == 0 && -z $(find "$BASE/$directory" -maxdepth 0 -perm /022 -print) ]]
done
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
STATE="$BASE/runs/$RUN_ID"
mkdir -m 0755 -- "$STATE"
printf '%s\n' "$MODE" > "$STATE/mode"
printf '%s\n' "$RUN_ID" > "$STATE/run-id"
printf '%s\n' "ssh_probe_required=$([[ $MODE == real ]] && echo yes || echo no)" > "$STATE/probe-requirement"
validate_state "$STATE"
[[ -f $LOCK && ! -L $LOCK && $(realpath -e "$LOCK") == "$LOCK" ]]
# The keeper owns the SAME flock as ops/run-build-batch.py. It is in another
# cgroup and survives controller termination until ExecStopPost has finished.
systemd-run --unit="$LOCK_UNIT" --collect --no-block \
  --property=Type=exec --property=User=root --property=KillMode=control-group \
  --property=TimeoutStopSec=20 --property=OOMPolicy=continue \
  --property="StandardOutput=append:$STATE/lock.log" \
  --property="StandardError=append:$STATE/lock.log" \
  /bin/bash "$BASE/controller.sh" --lock-keeper "$STATE"
ready=0
for ((attempt=0; attempt<100; attempt++)); do
  if [[ -f $STATE/lock.failed ]]; then printf 'shared_build_lock_rejected\nstate=%s\n' "$STATE" >&2; exit 75; fi
  if keeper_holds_lock; then ready=1; break; fi
  sleep 0.1
done
[[ $ready == 1 ]] || { echo 'shared build lock acquisition timed out' >&2; exit 75; }
printf '%s\n' "$(date -u +%FT%TZ)" > "$STATE/controller.dispatch"
if ! systemd-run --unit="$CONTROLLER" --collect --no-block \
  --property=Type=exec --property=User=root --property=KillMode=control-group \
  --property=TimeoutStartSec=infinity --property=TimeoutStopSec=600 \
  --property=RuntimeMaxSec=480 --property=OOMPolicy=continue \
  --property="ExecStopPost=/bin/bash $BASE/recover.sh $STATE" \
  --property="StandardOutput=append:$STATE/controller.log" \
  --property="StandardError=append:$STATE/controller.log" \
  /bin/bash "$BASE/controller.sh" "$STATE"; then
  printf 'launch_failed\n' > "$STATE/launch.result"
  printf 'yes\n' > "$STATE/controller.launch-failed"
  exit 1
fi
printf 'run_id=%s\nstate=%s\ncontroller=%s\n' "$RUN_ID" "$STATE" "$CONTROLLER"
