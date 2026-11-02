#!/usr/bin/env bash
set -euo pipefail

# Destructive zero-write rollback. Does not stop or start services; operators first enter maintenance.
[[ $# = 2 && $1 = --execute && $2 =~ ^[a-zA-Z0-9_]+$ ]] || { echo 'usage: rollback-m8-no-writes-controlled.sh --execute database' >&2; exit 64; }
db_name=$2
script_dir="$(cd -- "$(dirname -- "$0")" && pwd -P)"
lock=/home/ubuntu/print-system/runtime/build-tmp/.build.lock
exec 9>>"$lock"
flock -n 9 || { echo 'BUILD_ALREADY_RUNNING_STOP' >&2; exit 75; }
for service in print-backend.service nginx; do
  state="$(systemctl is-active "$service" || true)"
  [[ $state = inactive ]] || { echo "M8_NOT_DRAINED service=$service state=$state" >&2; exit 76; }
done
# Reject candidate or manually started Java servers as well as the systemd service.
if pgrep -f 'java.*(yudao-server|print-system)' >/dev/null; then
  echo 'M8_NOT_DRAINED java process present' >&2
  exit 76
fi
bash "$script_dir/assert-m8-db-drained.sh" "$db_name"
{ printf 'SET @m8_backend_drained=1;\n'; cat -- "$script_dir/print-miniapp-demo-cancellation-refund-rollback-no-writes.sql"; } | sudo mysql --batch --raw "$db_name"
echo 'M8_NO_WRITES_ROLLBACK_DONE services remain stopped; switch to M7 before restoring traffic'
