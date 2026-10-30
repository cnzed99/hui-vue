#!/bin/bash
set -Eeuo pipefail
BASE=/home/ubuntu/print-system/ops/runtime/frontend-safe-build
source "$BASE/common.sh"
require_root
validate_state "$1"
exec >>"$STATE/recovery.log" 2>&1
if [[ -f $STATE/recovery.result ]]; then mv -- "$STATE/recovery.result" "$STATE/recovery.result.previous-$(date -u +%s%N)"; fi
trap 'rc=$?; if [[ ! -f $STATE/recovery.result ]]; then printf "recovery_exit=%s\nunexpected_exit=yes\n" "$rc" > "$STATE/recovery.result"; fi' EXIT
# Normal ExecStopPost uses the still-live keeper. Manual recovery must acquire the
# same legacy-build flock itself; never create a parallel recovery window.
if keeper_holds_lock; then echo 'shared_lock=keeper_held'
else
  exec 9>>"$LOCK"
  flock -n 9 || { echo 'recovery shared lock unavailable' >&2; exit 75; }
  echo 'shared_lock=manual_recovery_held'
fi
printf 'recover_called=%s service_result=%s exit_code=%s exit_status=%s\n' "$(date -u +%FT%TZ)" "${SERVICE_RESULT:-manual}" "${EXIT_CODE:-unknown}" "${EXIT_STATUS:-unknown}"
printf 'yes\n' > "$STATE/recover.called"
RECOVERY_START=$SECONDS
RECOVERY_DEADLINE=$((SECONDS + 420))
deadline_exceeded=0
fail=0
child_clear=0
run_stage() {
  local cap=$1 remaining
  shift
  remaining=$((RECOVERY_DEADLINE - SECONDS))
  if (( remaining <= 0 )); then deadline_exceeded=1; echo 'recovery_work_deadline=exceeded'; return 124; fi
  (( cap <= remaining )) || cap=$remaining
  timeout --kill-after=5 "$cap" "$@"
}
load_state=
if load_state=$(run_stage 5 systemctl show "$CHILD" -p LoadState --value); then
  echo "child_load_state=$load_state"
elif [[ $load_state != not-found ]]; then echo 'child_load_state_query=failed'; exit 1; fi
if [[ $load_state == not-found ]]; then child_clear=1
else
  cg=$(run_stage 5 systemctl show "$CHILD" -p ControlGroup --value)
  if [[ -z $cg && -f $STATE/child.cgroup ]]; then cg=$(cat "$STATE/child.cgroup"); fi
  # Capture before stopping: successful RemainAfterExit keeps unit evidence alive.
  if [[ ! -f $STATE/child.final ]]; then
    if run_stage 5 systemctl show "$CHILD" -p Result -p ExecMainCode -p ExecMainStatus -p ActiveState -p SubState -p RemainAfterExit > "$STATE/child.final.recovery"; then echo 'fallback_child_evidence=ok'
    else echo 'fallback_child_evidence=failed'; fail=1; fi
  fi
  if run_stage 30 systemctl stop "$CHILD"; then echo 'child_stop=ok'; else echo 'child_stop=failed'; fail=1; fi
  if [[ -n $cg ]] && cg_empty "$cg"; then child_clear=1
  elif [[ -z $cg ]] && [[ $(run_stage 5 systemctl show "$CHILD" -p MainPID --value) == 0 ]]; then child_clear=1; fi
fi
printf 'child_clear=%s\n' "$child_clear"
printf '%s\n' "$child_clear" > "$STATE/child.clear"
if (( ! child_clear )); then
  echo 'Recovery refuses service starts/cleanup while child processes may survive.'
  printf 'recovery_exit=1\nchild_clear=0\n' > "$STATE/recovery.result"
  exit 1
fi
if [[ $MODE != real ]]; then
  # Give the external self-test observer a real recovery window to prove that
  # the shared legacy lock remains held; no business service action occurs.
  printf 'yes\n' > "$STATE/recovery.entered"
  if run_stage 3 /bin/sleep 2; then echo 'simulated_recovery_observation_window=ok'; else fail=1; fi
fi
wait_ready() {
  local service=$1 until=$((SECONDS + 40)) reply redis_rc
  while (( SECONDS < until && SECONDS < RECOVERY_DEADLINE )); do
    if [[ $service == mysql ]]; then
      if run_stage 2 mysqladmin --no-defaults --protocol=socket ping --silent >/dev/null 2>&1; then return 0; fi
    elif [[ $service == redis-server ]]; then
      if run_stage 3 systemctl is-active --quiet redis-server; then
        redis_rc=0
        # Clean environment excludes REDISCLI_AUTH; never read/pass a password.
        reply=$(run_stage 2 /usr/bin/env -i PATH=/usr/bin:/bin LANG=C redis-cli --raw -e -h 127.0.0.1 -p 6379 ping 2>&1) || redis_rc=$?
        if redis_listener_ready active "$redis_rc" "$reply"; then
          if [[ $reply == PONG ]]; then echo 'redis_listener_readiness=PONG'; else echo 'redis_listener_readiness=NOAUTH auth_gate_responded=yes'; fi
          return 0
        fi
      fi
    fi
    sleep 2
  done
  (( SECONDS < RECOVERY_DEADLINE )) || deadline_exceeded=1
  return 1
}
if [[ $MODE == self-test-recovery-deadline ]]; then
  # Simulate the global deadline without any business service or Vite command.
  RECOVERY_DEADLINE=$SECONDS
  if run_stage 1 /bin/sleep 2; then echo 'simulated_deadline=unexpected_success'; fail=1
  else echo 'simulated_deadline=observed'; fail=1; deadline_exceeded=1; fi
fi
if [[ $MODE == real && -f $STATE/original-active ]]; then
  dependencies_ready=1
  mysql_ready=0
  redis_ready=0
  for service in "${SERVICES[@]}"; do
    if ! grep -Fxq "$service" "$STATE/original-active"; then echo "service=$service originally_inactive=skip"; continue; fi
    if (( SECONDS >= RECOVERY_DEADLINE )); then echo "service=$service start=blocked_deadline"; deadline_exceeded=1; fail=1; continue; fi
    if [[ $service == print-backend && $dependencies_ready != 1 ]]; then
      echo 'service=print-backend start=blocked_dependencies'; fail=1; continue
    fi
    if [[ $service == print-backend ]]; then
      # Even an originally-inactive dependency is never started by recovery;
      # still require both live readiness checks before starting backend.
      if (( ! mysql_ready )); then if wait_ready mysql; then mysql_ready=1; else dependencies_ready=0; fail=1; fi; fi
      if (( ! redis_ready )); then if wait_ready redis-server; then redis_ready=1; else dependencies_ready=0; fail=1; fi; fi
      if (( ! dependencies_ready )); then echo 'service=print-backend start=blocked_readiness'; continue; fi
    fi
    if run_stage 45 systemctl start "$service"; then
      echo "service=$service start=ok"
      if run_stage 3 systemctl is-active --quiet "$service"; then echo "service=$service active=ok"; else echo "service=$service active=failed"; fail=1; fi
    else echo "service=$service start=failed"; fail=1; fi
    if [[ $service == mysql || $service == redis-server ]]; then
      if wait_ready "$service"; then
        echo "service=$service ready=ok"
        if [[ $service == mysql ]]; then mysql_ready=1; else redis_ready=1; fi
      else echo "service=$service ready=failed"; dependencies_ready=0; fail=1; fi
    fi
  done
  if grep -Fxq nginx "$STATE/original-active"; then
    nginx_rc=0
    nginx_code=$(run_stage 10 curl --silent --show-error --max-time 8 -o /dev/null --write-out '%{http_code}' "$(nginx_health_url)") || nginx_rc=$?
    if nginx_response_ready "$nginx_rc" "$nginx_code"; then echo "nginx_http=ok status=$nginx_code endpoint=$(nginx_health_url)"; else echo "nginx_http=failed curl_exit=$nginx_rc status=$nginx_code"; fail=1; fi
  fi
  if grep -Fxq print-backend "$STATE/original-active"; then
    backend_ok=0
    backend_until=$((SECONDS + 60))
    while (( SECONDS < backend_until && SECONDS < RECOVERY_DEADLINE )); do
      if run_stage 5 curl --fail --silent --show-error --max-time 3 http://127.0.0.1:48080/admin-api/system/auth/get-permission-info > "$STATE/backend-health.json.tmp"; then
        if run_stage 3 python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if isinstance(d,dict) and isinstance(d.get("code"),int) and d["code"] in (0,401) else 1)' "$STATE/backend-health.json.tmp"; then backend_ok=1; break; fi
      fi
      sleep 2
    done
    rm -f -- "$STATE/backend-health.json.tmp"
    if (( backend_ok )); then echo 'backend_http=ok'; else echo 'backend_http=failed'; fail=1; fi
  fi
fi
# Work stops at 420 seconds. The 600-second systemd stop budget leaves 180
# seconds for these bounded final checks/cleanup and durable result write.
if status_snapshot > "$STATE/services.after"; then echo 'service_snapshot=ok'; else echo 'service_snapshot=failed'; fail=1; fi
if [[ $MODE != real && -f $STATE/services.before ]]; then
  if cmp -s "$STATE/services.before" "$STATE/services.after"; then echo 'self_test_services_unchanged=yes'; else echo 'self_test_services_unchanged=no'; fail=1; fi
fi
if [[ -e $SCRATCH || -L $SCRATCH ]]; then
  if [[ ! -L $SCRATCH && $(realpath -e "$SCRATCH") == "$BASE/scratch/$RUN_ID" ]]; then
    if timeout --kill-after=5 45 rm -rf --one-file-system -- "$SCRATCH"; then echo 'scratch_cleanup=ok'; else echo 'scratch_cleanup=failed'; fail=1; fi
  else echo 'scratch_cleanup=unsafe_path_refused'; fail=1; fi
fi
[[ ! -e $SCRATCH && ! -L $SCRATCH ]] || fail=1
free=0
if disk_output=$(timeout --kill-after=1 5 df -PB1 "$BASE"); then free=$(printf '%s\n' "$disk_output" | awk 'NR==2 {print $4}'); else echo 'disk_after_query=failed'; fail=1; fi
printf 'disk_after_bytes=%s\n' "$free"
(( free >= 10737418240 )) || fail=1
(( SECONDS < RECOVERY_DEADLINE )) || deadline_exceeded=1
(( ! deadline_exceeded )) || fail=1
printf 'recovery_exit=%s\nchild_clear=%s\nscratch_removed=%s\ndisk_after_bytes=%s\ndeadline_exceeded=%s\nrecovery_elapsed_seconds=%s\n' "$fail" "$child_clear" "$([[ ! -e $SCRATCH ]] && echo yes || echo no)" "$free" "$deadline_exceeded" "$((SECONDS - RECOVERY_START))" > "$STATE/recovery.result"
exit "$fail"
