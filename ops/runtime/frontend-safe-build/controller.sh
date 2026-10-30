#!/bin/bash
set -Eeuo pipefail
BASE=/home/ubuntu/print-system/ops/runtime/frontend-safe-build
source "$BASE/common.sh"
require_root
if [[ ${1:-} == --lock-keeper ]]; then
  validate_state "$2"
  exec 9>>"$LOCK"
  if ! flock -n 9; then printf 'shared_build_lock_busy\n' > "$STATE/lock.failed"; exit 75; fi
  printf '%s\n' "$$" > "$STATE/lock.keeper.pid"
  printf '%s\n' "$(date -u +%FT%TZ)" > "$STATE/lock.acquired"
  # Bounded dispatch wait: a controller that starts later must recheck ownership
  # and refuse all work if this keeper has already exited.
  for ((attempt=0; attempt<300; attempt++)); do
    [[ ! -f $STATE/controller.started && ! -f $STATE/controller.launch-failed ]] || break
    sleep 0.1
  done
  if [[ -f $STATE/controller.started ]]; then
    while :; do
      if state=$(systemctl show "$CONTROLLER" -p ActiveState --value); then
        [[ $state == active || $state == activating || $state == deactivating ]] || break
      else break; fi
      sleep 0.2
    done
  fi
  printf '%s\n' "$(date -u +%FT%TZ)" > "$STATE/lock.released"
  exit 0
fi
validate_state "$1"
printf '%s\n' "$(date -u +%FT%TZ)" > "$STATE/controller.started"
keeper_holds_lock || { echo 'shared build lock keeper absent' >&2; exit 75; }
exec >>"$STATE/controller.log" 2>&1
trap 'exit 143' TERM INT
trap 'rc=$?; if [[ ! -f $STATE/build.result ]]; then printf "controller_exit=%s\n" "$rc" > "$STATE/build.result"; fi' EXIT
printf 'controller_cgroup=%s\n' "$(cat /proc/self/cgroup)"
[[ $(df -PB1 "$BASE" | awk 'NR==2 {print $4}') -ge 10737418240 ]]
printf 'disk_preflight_bytes=%s\n' "$(df -PB1 "$BASE" | awk 'NR==2 {print $4}')"
[[ ! -e $SCRATCH && ! -L $SCRATCH ]]
install -d -m 0755 -o ubuntu -g ubuntu -- "$SCRATCH"
[[ $(realpath -e "$SCRATCH") == "$BASE/scratch/$RUN_ID" ]]
runuser -u ubuntu -- /usr/bin/touch "$SCRATCH/write-preflight"
rm -- "$SCRATCH/write-preflight"
status_snapshot > "$STATE/services.before.tmp"
mv -- "$STATE/services.before.tmp" "$STATE/services.before"
awk '$2=="active" { print $1 }' "$STATE/services.before" > "$STATE/original-active"
if [[ $MODE == real ]]; then
  [[ -x $NODE && -f $FRONTEND/node_modules/vite/bin/vite.js ]]
  # Persist recovery inputs before stopping the first service.
  printf 'services_stop_begin=%s\n' "$(date -u +%FT%TZ)"
  for service in print-backend nginx redis-server mysql; do
    if grep -Fxq "$service" "$STATE/original-active"; then systemctl stop "$service"; fi
  done
fi
limit=360
[[ $MODE != self-test-timeout ]] || limit=8
systemd-run --unit="$CHILD" --property=Type=exec --property=User=ubuntu \
  --property=RemainAfterExit=yes \
  --property=MemoryAccounting=yes --property=MemoryHigh=2560M --property=MemoryMax=2816M \
  --property=MemorySwapMax=512M --property=CPUQuota=100% --property=Nice=10 \
  --property="RuntimeMaxSec=$limit" --property=TimeoutStopSec=20 \
  --property=KillMode=control-group --property=OOMPolicy=stop \
  --property="WorkingDirectory=$FRONTEND" \
  --property="StandardOutput=append:$STATE/build.log" \
  --property="StandardError=append:$STATE/build.log" \
  /usr/bin/env -i HOME=/home/ubuntu USER=ubuntu LOGNAME=ubuntu LANG=C.UTF-8 \
  PATH="${NODE%/node}:/usr/bin:/bin" /bin/bash "$BASE/build-child.sh" "$MODE" "$SCRATCH"
systemctl show "$CHILD" -p ControlGroup -p MemoryHigh -p MemoryMax -p MemorySwapMax \
  -p CPUQuotaPerSecUSec -p Nice -p RuntimeMaxUSec -p TimeoutStopUSec -p KillMode -p OOMPolicy > "$STATE/child.properties"
CG=$(systemctl show "$CHILD" -p ControlGroup --value)
CG=${CG:-/system.slice/$CHILD}
printf '%s\n' "$CG" > "$STATE/child.cgroup"
[[ $CG == /system.slice/print-frontend-safe-child-* ]]
[[ $(cat /proc/self/cgroup) != "0::$CG" ]]
systemctl show "$CHILD" -p MemoryHigh -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec \
  -p Nice -p RuntimeMaxUSec -p TimeoutStopUSec -p KillMode -p OOMPolicy > "$STATE/limits.before"
grep -Fxq 'MemoryHigh=2684354560' "$STATE/limits.before"
grep -Fxq 'MemoryMax=2952790016' "$STATE/limits.before"
grep -Fxq 'MemorySwapMax=536870912' "$STATE/limits.before"
grep -Fxq 'CPUQuotaPerSecUSec=1s' "$STATE/limits.before"
grep -Fxq 'Nice=10' "$STATE/limits.before"
grep -Fxq 'TimeoutStopUSec=20s' "$STATE/limits.before"
grep -Fxq 'KillMode=control-group' "$STATE/limits.before"
grep -Fxq 'OOMPolicy=stop' "$STATE/limits.before"
if [[ $MODE == self-test-timeout ]]; then
  grep -Fxq 'RuntimeMaxUSec=8s' "$STATE/limits.before"
else grep -Fxq 'RuntimeMaxUSec=6min' "$STATE/limits.before"; fi
printf 'time_utc memory_current memory_swap_current mem_available_kib swap_free_kib events\n' > "$STATE/memory.tsv"
while :; do
  active=$(systemctl show "$CHILD" -p ActiveState --value)
  sub=$(systemctl show "$CHILD" -p SubState --value)
  [[ $active != active || $sub != exited ]] || break
  [[ $active == active || $active == activating || $active == deactivating ]] || break
  if ! keeper_holds_lock; then
    printf 'shared_build_lock_lost\n' > "$STATE/abort.reason"
    systemctl stop "$CHILD"
    break
  fi
  mem=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
  swap=$(awk '/^SwapFree:/ {print $2}' /proc/meminfo)
  if [[ -d /sys/fs/cgroup$CG ]]; then
    # A completed cgroup may disappear between any two reads. Missing samples
    # are recorded, never mistaken for OOM-free evidence or a shell fatal error.
    if current=$(cat "/sys/fs/cgroup$CG/memory.current" 2>/dev/null) && \
       swap_current=$(cat "/sys/fs/cgroup$CG/memory.swap.current" 2>/dev/null) && \
       events=$(cat "/sys/fs/cgroup$CG/memory.events" 2>/dev/null); then
      printf '%s %s %s %s %s %s\n' "$(date -u +%FT%TZ)" "$current" "$swap_current" "$mem" "$swap" "$(printf '%s' "$events" | tr '\n' ',')" >> "$STATE/memory.tsv"
      printf '%s\n' "$events" > "$STATE/memory.events.last"
    else printf '%s cgroup_sample_unavailable %s %s\n' "$(date -u +%FT%TZ)" "$mem" "$swap" >> "$STATE/memory.tsv"; fi
  fi
  if (( mem < 262144 || swap < 524288 )); then
    printf 'host_low_memory mem_available_kib=%s swap_free_kib=%s\n' "$mem" "$swap" > "$STATE/abort.reason"
    systemctl stop "$CHILD"
    break
  fi
  sleep 5
done
systemctl show "$CHILD" -p Result -p ExecMainCode -p ExecMainStatus -p ActiveState -p SubState -p MemoryPeak -p RemainAfterExit > "$STATE/child.final"
systemctl show "$CHILD" -p MemoryHigh -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec \
  -p Nice -p RuntimeMaxUSec -p TimeoutStopUSec -p KillMode -p OOMPolicy > "$STATE/limits.after"
result=$(systemctl show "$CHILD" -p Result --value)
code=$(systemctl show "$CHILD" -p ExecMainStatus --value)
if [[ $result == success && $code == 0 && ! -e $STATE/abort.reason && -s $SCRATCH/dist/index.html ]] && cmp -s "$STATE/limits.before" "$STATE/limits.after"; then
  # Simulated success exits too quickly for a sample; it is explicitly NOT
  # real-build OOM evidence. Real execution still requires sampled OOM counters.
  if [[ $MODE == self-test-success || $MODE == self-test-recovery-deadline ]] || { [[ -f $STATE/memory.events.last ]] && awk '$1=="oom" || $1=="oom_kill" || $1=="oom_group_kill" { if ($2 != 0) bad=1 } END { exit bad }' "$STATE/memory.events.last"; }; then
    if [[ $MODE == real ]]; then
      printf 'build_exit=0\nartifact_index=yes\noom=no\nlimits_changed=no\n' > "$STATE/build.result"
    else printf 'build_exit=0\nartifact_index=yes\nsimulation=yes\noom=not_applicable\nlimits_changed=no\n' > "$STATE/build.result"; fi
    find "$SCRATCH/dist" -type f -printf '%P %s\n' > "$STATE/artifacts.manifest"
    sha256sum "$SCRATCH/dist/index.html" > "$STATE/index.sha256"
    printf 'complete\n' > "$STATE/evidence.complete"
    exit 0
  fi
fi
printf 'build_exit=%s\nunit_result=%s\n' "$code" "$result" > "$STATE/build.result"
exit 1
