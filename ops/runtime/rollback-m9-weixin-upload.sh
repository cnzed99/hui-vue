#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=/home/ubuntu/print-system
[[ $# == 2 && $1 == --backup ]] || { echo 'Usage: rollback-m9-weixin-upload.sh --backup ABSOLUTE_BACKUP' >&2; exit 64; }
BACKUP=$(realpath -e -- "$2")
[[ $BACKUP == "$ROOT/runtime/backups/m9-weixin-"* && $(dirname "$BACKUP") == "$ROOT/runtime/backups" ]]
[[ ! -L $2 ]]
exec 9>"$ROOT/runtime/build-tmp/.build.lock"
flock -n 9 || exit 75
for name in nginx.site nginx.limits dropin.state backend.previous miniapp.previous; do
  [[ -f $BACKUP/$name && ! -L $BACKUP/$name ]]
done
PREVIOUS_BACKEND=$(cat "$BACKUP/backend.previous")
PREVIOUS_MINIAPP=$(cat "$BACKUP/miniapp.previous")
[[ $(realpath -e "$PREVIOUS_BACKEND") == "$PREVIOUS_BACKEND" && $(dirname "$PREVIOUS_BACKEND") == "$ROOT/runtime/releases" ]]
[[ $(realpath -e "$PREVIOUS_MINIAPP") == "$PREVIOUS_MINIAPP" && $(dirname "$PREVIOUS_MINIAPP") == /var/www/print-system/releases ]]
[[ -f $PREVIOUS_BACKEND/backend.jar && -f $PREVIOUS_MINIAPP/index.html ]]
if grep -Fxq present "$BACKUP/dropin.state"; then [[ -f $BACKUP/dropin.previous && ! -L $BACKUP/dropin.previous ]]; else grep -Fxq absent "$BACKUP/dropin.state"; fi
RUN_ID=explicit-rollback
SUCCESS=0
atomic_backend_link() {
  local target=$1 link=$ROOT/runtime/releases/current.rollback
  rm -f -- "$link"
  ln -s "$(basename -- "$target")" "$link"
  mv -Tf -- "$link" "$ROOT/runtime/releases/current"
}

atomic_miniapp_link() {
  local target=$1 link=/var/www/print-system/miniapp.rollback
  sudo -n rm -f -- "$link"
  sudo -n ln -s "$target" "$link"
  sudo -n mv -Tf -- "$link" /var/www/print-system/miniapp
}

wait_backend() {
  for _ in $(seq 1 90); do
    if curl --fail --silent --max-time 3 http://127.0.0.1:48080/admin-api/system/auth/get-permission-info \
      | python3 -c 'import json,sys; raise SystemExit(0 if json.load(sys.stdin).get("code")==401 else 1)' 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

rollback() {
  local rc=0 failed=0
  trap - EXIT
  if (( SUCCESS == 0 )); then
    true
    set +e
    # Stop the new worker before restoring links. Cleanup holds the Java-compatible storage lock.
    if sudo -n systemctl stop print-backend.service; then
      if ! python3 "$ROOT/runtime/worktrees/m9-image-collage-engineering-20261102/ops/runtime/m9_weixin_cleanup_upload_scratch.py"; then
        failed=1
        printf 'M9_WEIXIN_ROLLBACK_SCRATCH_CLEANUP_FAILED\n' >&2
      fi
    else
      failed=1
      printf 'M9_WEIXIN_ROLLBACK_BACKEND_STOP_FAILED\n' >&2
    fi
    sudo -n rm -f -- /etc/nginx/sites-available/print-system.next \
      /etc/nginx/sites-enabled/print-system.next \
      /etc/nginx/conf.d/print-miniapp-limits.conf.next \
      /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf.next || failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.site" /etc/nginx/sites-available/print-system || failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.site" /etc/nginx/sites-enabled/print-system || failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.limits" /etc/nginx/conf.d/print-miniapp-limits.conf || failed=1
    if grep -Fxq present "$BACKUP/dropin.state"; then
      sudo -n install -o root -g root -m 0644 "$BACKUP/dropin.previous" /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf || failed=1
    else
      sudo -n rm -f -- /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf || failed=1
    fi
    atomic_backend_link "$PREVIOUS_BACKEND" || failed=1
    atomic_miniapp_link "$PREVIOUS_MINIAPP" || failed=1
    sudo -n systemctl daemon-reload || failed=1
    sudo -n systemctl restart print-backend.service || failed=1
    wait_backend || failed=1
    sudo -n nginx -t || failed=1
    sudo -n systemctl reload nginx || failed=1
    systemctl is-active --quiet mysql || failed=1
    systemctl is-active --quiet redis-server || failed=1
    systemctl is-active --quiet nginx || failed=1
    systemctl is-active --quiet print-backend.service || failed=1
    if (( failed == 0 )); then
      printf 'M9_WEIXIN_DEPLOY_ROLLED_BACK run_id=%s original_rc=%s\n' "$RUN_ID" "$rc" >&2
    else
      printf 'M9_WEIXIN_DEPLOY_ROLLBACK_FAILED run_id=%s backup=%s\n' "$RUN_ID" "$BACKUP" >&2
      rc=70
    fi
  fi
  exit "$rc"
}
rollback
