#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=/home/ubuntu/print-system
ENGINEERING=$ROOT/runtime/worktrees/m9-image-collage-engineering-20261102
BACKEND_JAR=$ROOT/runtime/worktrees/m9-weixin-upload-backend-20261102/yudao-server/target/yudao-server.jar
MINIAPP_DIST=$ROOT/runtime/worktrees/m9-weixin-upload-miniapp-20261102/dist/build/h5
CANDIDATE=$ENGINEERING/ops/runtime/candidates/m9-weixin
NGINX_SITE=$CANDIDATE/nginx-print-system.conf
NGINX_LIMITS=$CANDIDATE/print-miniapp-limits.conf
DROPIN_ENABLED=$CANDIDATE/60-m9-weixin-upload.conf
DROPIN_DISABLED=$CANDIDATE/60-m9-weixin-upload-disabled.conf
MINIAPP_MANIFEST=$CANDIDATE/miniapp-SHA256SUMS
EXPECTED_BACKEND=8eea9fca50dc5ce61c8cd2a0accbb03b1b2efa6e95fbb450058dc1284302b73f
EXPECTED_MINIAPP_INDEX=d37af00986584012604c688f432d1b23795f90c115d021f80f16169de49e5391
EXPECTED_NGINX_SITE=e728449cb2596ab88ec8951f52a4bee6ae315de0497c3585d14e521c2a1292e7
EXPECTED_NGINX_LIMITS=860ec67fc62ad972f4c467d1b82d4ade50c8246267d0b17a294ffb707bbd03b9
EXPECTED_DROPIN=83bf136df5333cbcdfef63c10b7002bd4c5fe652ee5301da53a72d0c55242dbe
EXPECTED_DROPIN_DISABLED=165b1cd987603fe474f8782445acbb707d69d0f5fa307763c6604b143e217c86
EXPECTED_MINIAPP_MANIFEST=e6ce91c6eb86880de915eee07107241b5ff5a8c8e773195b9f6d4b2ab0d8a212
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
BACKUP=$ROOT/runtime/backups/m9-weixin-$RUN_ID
MINIAPP_TARGET=/var/www/print-system/releases/miniapp-m9-weixin-$RUN_ID
SUCCESS=0

verify_miniapp_tree() {
  local directory=$1 allow_manifest=${2:-0}
  local manifest_name=__manifest_not_allowed__
  [[ $allow_manifest == 1 ]] && manifest_name=SHA256SUMS
  [[ -d $directory ]] || return 1
  [[ -z $(find "$directory" -type l -print -quit) ]] || return 1
  diff -u \
    <(sed -n 's/^[0-9a-f]\{64\}  \.\///p' "$MINIAPP_MANIFEST" | LC_ALL=C sort) \
    <(find "$directory" -type f ! -name "$manifest_name" -printf '%P\n' | LC_ALL=C sort) \
    >/dev/null
}

[[ ${1:-} == --apply && $# == 1 ]] || { echo 'Usage: deploy-m9-weixin-upload.sh --apply (requires final review and deployment authorization)' >&2; exit 64; }
exec 9>"$ROOT/runtime/build-tmp/.build.lock"
flock -n 9 || { echo 'M9_WEIXIN_DEPLOY_LOCKED' >&2; exit 75; }
[[ $(sha256sum "$BACKEND_JAR" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
[[ $(sha256sum "$MINIAPP_DIST/index.html" | cut -d' ' -f1) == "$EXPECTED_MINIAPP_INDEX" ]]
[[ $(sha256sum "$NGINX_SITE" | cut -d' ' -f1) == "$EXPECTED_NGINX_SITE" ]]
[[ $(sha256sum "$NGINX_LIMITS" | cut -d' ' -f1) == "$EXPECTED_NGINX_LIMITS" ]]
[[ $(sha256sum "$DROPIN_ENABLED" | cut -d' ' -f1) == "$EXPECTED_DROPIN" ]]
[[ $(sha256sum "$DROPIN_DISABLED" | cut -d' ' -f1) == "$EXPECTED_DROPIN_DISABLED" ]]
[[ $(sha256sum "$MINIAPP_MANIFEST" | cut -d' ' -f1) == "$EXPECTED_MINIAPP_MANIFEST" ]]
verify_miniapp_tree "$MINIAPP_DIST"
(cd "$MINIAPP_DIST" && sha256sum -c "$MINIAPP_MANIFEST") >/dev/null
[[ $(df -PB1 "$ROOT" | awk 'NR==2 {print $4}') -ge 10737418240 ]]
[[ $(stat -c '%a:%U:%G' "$ROOT/runtime/miniapp-demo-multipart") == 700:ubuntu:ubuntu ]]
[[ $(stat -c '%a:%U:%G' /var/lib/nginx/print-miniapp-collage-body) == 700:www-data:www-data ]]

PREVIOUS_BACKEND=$(readlink -f "$ROOT/runtime/releases/current")
PREVIOUS_MINIAPP=$(readlink -f /var/www/print-system/miniapp)
[[ $PREVIOUS_BACKEND == "$ROOT/runtime/releases/"* ]]
[[ $PREVIOUS_MINIAPP == /var/www/print-system/releases/* ]]
install -d -m 0700 -- "$BACKUP"
sudo -n cp --preserve=mode,ownership,timestamps /etc/nginx/sites-available/print-system "$BACKUP/nginx.site"
sudo -n cp --preserve=mode,ownership,timestamps /etc/nginx/conf.d/print-miniapp-limits.conf "$BACKUP/nginx.limits"
if [[ -e /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf ]]; then
  sudo -n cp --preserve=mode,ownership,timestamps /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf "$BACKUP/dropin.previous"
  printf 'present\n' >"$BACKUP/dropin.state"
else
  printf 'absent\n' >"$BACKUP/dropin.state"
fi
printf '%s\n' "$PREVIOUS_BACKEND" >"$BACKUP/backend.previous"
printf '%s\n' "$PREVIOUS_MINIAPP" >"$BACKUP/miniapp.previous"

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
  local rc=$? failed=0
  trap - EXIT
  if (( SUCCESS == 0 )); then
    (( rc != 0 )) || rc=1
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
trap rollback EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

sudo -n rm -f -- /etc/nginx/sites-available/print-system.next \
  /etc/nginx/sites-enabled/print-system.next \
  /etc/nginx/conf.d/print-miniapp-limits.conf.next \
  /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf.next
sudo -n install -o root -g root -m 0644 "$NGINX_SITE" /etc/nginx/sites-available/print-system
sudo -n install -o root -g root -m 0644 "$NGINX_SITE" /etc/nginx/sites-enabled/print-system
sudo -n install -o root -g root -m 0644 "$NGINX_LIMITS" /etc/nginx/conf.d/print-miniapp-limits.conf
sudo -n install -o root -g root -m 0644 "$DROPIN_DISABLED" \
  /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf
sudo -n systemctl daemon-reload
sudo -n nginx -t

release=$ROOT/runtime/releases/${EXPECTED_BACKEND:0:16}
[[ ! -L $release ]]
install -d -m 0755 "$release"
if [[ -e $release/backend.jar ]]; then
  [[ $(sha256sum "$release/backend.jar" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
else
  install -m 0644 "$BACKEND_JAR" "$release/backend.jar.next"
  [[ $(sha256sum "$release/backend.jar.next" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
  mv -f -- "$release/backend.jar.next" "$release/backend.jar"
fi
atomic_backend_link "$release"
sudo -n systemctl restart print-backend.service
wait_backend

[[ ! -e $MINIAPP_TARGET && $(dirname "$MINIAPP_TARGET") == /var/www/print-system/releases ]]
sudo -n install -d -o ubuntu -g ubuntu -m 0755 -- "$MINIAPP_TARGET"
cp -a "$MINIAPP_DIST/." "$MINIAPP_TARGET/"
[[ $(sha256sum "$MINIAPP_TARGET/index.html" | cut -d' ' -f1) == "$EXPECTED_MINIAPP_INDEX" ]]
(cd "$MINIAPP_TARGET" && sha256sum -c "$MINIAPP_MANIFEST") >/dev/null
install -m 0644 "$MINIAPP_MANIFEST" "$MINIAPP_TARGET/SHA256SUMS"
verify_miniapp_tree "$MINIAPP_TARGET" 1
atomic_miniapp_link "$MINIAPP_TARGET"

sudo -n systemctl reload nginx
curl --fail --silent --max-time 10 http://127.0.0.1:18080/miniapp/ >/dev/null
python3 "$ENGINEERING/ops/acceptance/m9_weixin_pre_enable_regression.py" >"$BACKUP/pre-enable-regression.log" 2>&1

sudo -n install -o root -g root -m 0644 "$DROPIN_ENABLED" \
  /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf
sudo -n systemctl daemon-reload
sudo -n systemctl restart print-backend.service
wait_backend

python3 "$ENGINEERING/ops/acceptance/m9_weixin_runtime_smoke.py" --evidence "$BACKUP/runtime-smoke.json" >"$BACKUP/runtime-smoke.log" 2>&1
[[ $(sha256sum /etc/nginx/sites-available/print-system | cut -d' ' -f1) == "$EXPECTED_NGINX_SITE" ]]
[[ $(sha256sum /etc/nginx/conf.d/print-miniapp-limits.conf | cut -d' ' -f1) == "$EXPECTED_NGINX_LIMITS" ]]
[[ $(sha256sum /etc/systemd/system/print-backend.service.d/60-m9-weixin-upload.conf | cut -d' ' -f1) == "$EXPECTED_DROPIN" ]]
[[ $(readlink -f "$ROOT/runtime/releases/current/backend.jar" | xargs sha256sum | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
[[ $(readlink -f /var/www/print-system/miniapp/index.html | xargs sha256sum | cut -d' ' -f1) == "$EXPECTED_MINIAPP_INDEX" ]]
systemctl is-active --quiet mysql
systemctl is-active --quiet redis-server
systemctl is-active --quiet nginx
systemctl is-active --quiet print-backend.service

SUCCESS=1
trap - EXIT
printf 'M9_WEIXIN_DEPLOY_PASS run_id=%s backend=%s miniapp=%s backup=%s\n' \
  "$RUN_ID" "$(readlink -f "$ROOT/runtime/releases/current")" "$(readlink -f /var/www/print-system/miniapp)" "$BACKUP"
