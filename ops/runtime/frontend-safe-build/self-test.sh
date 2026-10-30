#!/bin/bash
set -Eeuo pipefail
BASE=/home/ubuntu/print-system/ops/runtime/frontend-safe-build
source "$BASE/common.sh"
require_root
probe_contract_tests() {
  redis_listener_ready active 0 PONG
  redis_listener_ready active 1 'NOAUTH Authentication required.'
  redis_listener_ready active 0 '(error) NOAUTH Authentication required.'
  if redis_listener_ready active 1 'Could not connect to Redis at 127.0.0.1:6379: Connection refused'; then return 1; fi
  if redis_listener_ready inactive 0 PONG; then return 1; fi
  if redis_listener_ready active 124 'NOAUTH Authentication required.'; then return 1; fi
  if redis_listener_ready active 1 'WRONGPASS invalid username-password pair'; then return 1; fi
  [[ $(nginx_health_url) == http://127.0.0.1:18080/login ]]
  nginx_response_ready 0 200
  nginx_response_ready 0 302
  if nginx_response_ready 0 404; then return 1; fi
  if nginx_response_ready 7 200; then return 1; fi
  printf 'self_test=probe-contracts result=PASS no_service_or_network_calls=yes\n'
}
if [[ ${1:-} == --probe-contracts-only && $# == 1 ]]; then probe_contract_tests; exit; fi
[[ $# == 0 ]] || exit 64
probe_contract_tests
# No test uses --real. All child simulations branch before Node/Vite; recovery
# calls start/stop only for the unique child unit, never the four business units.
before=$(status_snapshot)
exec 8>>"$LOCK"
flock -n 8 || { echo 'self-test requires idle shared build lock' >&2; exit 75; }
reject_code=0
output=$(/bin/bash "$BASE/launch.sh" --self-test success 2>&1) || reject_code=$?
[[ $reject_code == 75 ]]
STATE=$(printf '%s\n' "$output" | sed -n 's/^state=//p')
validate_state "$STATE"
[[ -f $STATE/lock.failed && ! -e $STATE/controller.started && ! -e $SCRATCH && ! -e $STATE/services.before ]]
[[ $(status_snapshot) == "$before" ]]
printf 'self_test=lock-reject result=PASS run_id=%s\n' "$RUN_ID" | tee "$STATE/self-test.result"
flock -u 8
exec 8>&-
for scenario in success fail timeout recovery-deadline; do
  before=$(status_snapshot)
  output=$(/bin/bash "$BASE/launch.sh" --self-test "$scenario")
  printf '%s\n' "$output"
  STATE=$(printf '%s\n' "$output" | sed -n 's/^state=//p')
  validate_state "$STATE"
  complete=0
  observed_recovery_lock=0
  for ((attempt=0; attempt<1200; attempt++)); do
    if [[ -f $STATE/recovery.entered && ! -f $STATE/recovery.result ]]; then
      keeper_holds_lock
      observed_recovery_lock=1
    fi
    if [[ -f $STATE/recovery.result ]]; then complete=1; break; fi
    sleep 0.1
  done
  [[ $complete == 1 && $observed_recovery_lock == 1 && -f $STATE/recover.called && $(cat "$STATE/child.clear") == 1 ]]
  if [[ $scenario == recovery-deadline ]]; then
    grep -Fxq 'recovery_exit=1' "$STATE/recovery.result"
    grep -Fxq 'deadline_exceeded=1' "$STATE/recovery.result"
    grep -Fxq 'scratch_removed=yes' "$STATE/recovery.result"
  else grep -Fxq 'recovery_exit=0' "$STATE/recovery.result"; fi
  [[ ! -e $SCRATCH && ! -L $SCRATCH ]]
  [[ $(status_snapshot) == "$before" ]]
  case $scenario in
    fail) grep -Fxq 'ExecMainStatus=42' "$STATE/child.final" ;;
    timeout) grep -Fxq 'Result=timeout' "$STATE/child.final" ;;
    success|recovery-deadline)
      grep -Fxq 'Result=success' "$STATE/child.final"
      grep -Fxq 'ExecMainStatus=0' "$STATE/child.final"
      grep -Fxq 'ActiveState=active' "$STATE/child.final"
      grep -Fxq 'SubState=exited' "$STATE/child.final"
      grep -Fxq 'RemainAfterExit=yes' "$STATE/child.final"
      [[ -f $STATE/evidence.complete && -s $STATE/index.sha256 && -s $STATE/artifacts.manifest ]]
      cmp -s "$STATE/limits.before" "$STATE/limits.after"
      ;;
  esac
  # The keeper may release ONLY after the controller (including ExecStopPost)
  # has completely stopped. Verify its lock until then, not just before result.
  for ((attempt=0; attempt<100; attempt++)); do
    if state=$(systemctl show "$CONTROLLER" -p ActiveState --value); then
      if [[ $state == active || $state == activating || $state == deactivating ]]; then
        keeper_holds_lock
        sleep 0.1
        continue
      fi
    fi
    break
  done
  released=0
  for ((attempt=0; attempt<100; attempt++)); do
    if [[ -f $STATE/lock.released ]]; then released=1; break; fi
    sleep 0.1
  done
  [[ $released == 1 ]]
  # Require the shared lock itself to be available again after recovery.
  flock -n "$LOCK" /bin/true
  printf 'self_test=%s result=PASS run_id=%s\n' "$scenario" "$RUN_ID" | tee "$STATE/self-test.result"
done
