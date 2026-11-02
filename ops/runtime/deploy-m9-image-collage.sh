#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=/home/ubuntu/print-system
ENGINEERING=$ROOT/runtime/worktrees/m9-image-collage-engineering-20261102
BACKEND_JAR=$ROOT/runtime/worktrees/m9-image-collage-backend-20261102/yudao-server/target/yudao-server.jar
MINIAPP_DIST=$ROOT/runtime/worktrees/m9-image-collage-miniapp-20261102/dist/build/h5
CANDIDATE=$ENGINEERING/ops/runtime/candidates/m9
NGINX_SITE=$CANDIDATE/nginx-print-system.conf
NGINX_LIMITS=$CANDIDATE/print-miniapp-limits.conf
DROPIN_ENABLED=$CANDIDATE/50-m9-image-collage.conf
DROPIN_DISABLED=$CANDIDATE/50-m9-image-collage-disabled.conf
MINIAPP_MANIFEST=$CANDIDATE/miniapp-SHA256SUMS
EXPECTED_BACKEND=1884012860d0de3ae8bad21461843bac5e3c77dc9c35690614ab7584f0e65e6b
EXPECTED_MINIAPP_INDEX=ca4d334162e3ce2c135465919fcfc46fdb14beb115e976ca5f1790e303cf3bbc
EXPECTED_NGINX_SITE=16120f633299d048c4fc0a7ab93f2e87f639bb3c13c04ccb5736830fa46872e5
EXPECTED_NGINX_LIMITS=34f58a6be63394f578449d1657692e673b9bced3f3176a74a104cac97d1634b9
EXPECTED_DROPIN=5fb33764ced7f379596e641d993bf45f6641ca55c23fe8d6c229f4cd83f70aa6
EXPECTED_DROPIN_DISABLED=d26016694e4ea84ebf61a1d991294c1dc0cbe71a73c90e9dcdefd1f1083097b1
EXPECTED_MINIAPP_MANIFEST=337168d69274b81aed56d7170e2d1b7eb197f716c821e77db96569cae85bac0f
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
BACKUP=$ROOT/runtime/backups/m9-image-collage-$RUN_ID
MINIAPP_TARGET=/var/www/print-system/releases/miniapp-m9-image-collage-$RUN_ID
SUCCESS=0

exec 9>"$ROOT/runtime/build-tmp/.build.lock"
flock -n 9 || { echo 'M9_DEPLOY_LOCKED' >&2; exit 75; }
[[ $(sha256sum "$BACKEND_JAR" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
[[ $(sha256sum "$MINIAPP_DIST/index.html" | cut -d' ' -f1) == "$EXPECTED_MINIAPP_INDEX" ]]
[[ $(sha256sum "$NGINX_SITE" | cut -d' ' -f1) == "$EXPECTED_NGINX_SITE" ]]
[[ $(sha256sum "$NGINX_LIMITS" | cut -d' ' -f1) == "$EXPECTED_NGINX_LIMITS" ]]
[[ $(sha256sum "$DROPIN_ENABLED" | cut -d' ' -f1) == "$EXPECTED_DROPIN" ]]
[[ $(sha256sum "$DROPIN_DISABLED" | cut -d' ' -f1) == "$EXPECTED_DROPIN_DISABLED" ]]
[[ $(sha256sum "$MINIAPP_MANIFEST" | cut -d' ' -f1) == "$EXPECTED_MINIAPP_MANIFEST" ]]
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
if [[ -e /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf ]]; then
  sudo -n cp --preserve=mode,ownership,timestamps /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf "$BACKUP/dropin.previous"
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
    sudo -n rm -f -- /etc/nginx/sites-available/print-system.next \
      /etc/nginx/sites-enabled/print-system.next \
      /etc/nginx/conf.d/print-miniapp-limits.conf.next \
      /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf.next || failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.site" /etc/nginx/sites-available/print-system || failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.site" /etc/nginx/sites-enabled/print-system || failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.limits" /etc/nginx/conf.d/print-miniapp-limits.conf || failed=1
    if grep -Fxq present "$BACKUP/dropin.state"; then
      sudo -n install -o root -g root -m 0644 "$BACKUP/dropin.previous" /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf || failed=1
    else
      sudo -n rm -f -- /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf || failed=1
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
      printf 'M9_DEPLOY_ROLLED_BACK run_id=%s original_rc=%s\n' "$RUN_ID" "$rc" >&2
    else
      printf 'M9_DEPLOY_ROLLBACK_FAILED run_id=%s backup=%s\n' "$RUN_ID" "$BACKUP" >&2
      rc=70
    fi
  fi
  exit "$rc"
}
trap rollback EXIT

sudo -n rm -f -- /etc/nginx/sites-available/print-system.next \
  /etc/nginx/sites-enabled/print-system.next \
  /etc/nginx/conf.d/print-miniapp-limits.conf.next \
  /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf.next
sudo -n install -o root -g root -m 0644 "$NGINX_SITE" /etc/nginx/sites-available/print-system
sudo -n install -o root -g root -m 0644 "$NGINX_SITE" /etc/nginx/sites-enabled/print-system
sudo -n install -o root -g root -m 0644 "$NGINX_LIMITS" /etc/nginx/conf.d/print-miniapp-limits.conf
sudo -n install -o root -g root -m 0644 "$DROPIN_DISABLED" \
  /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf
sudo -n systemctl daemon-reload
sudo -n nginx -t

target_jar=$ROOT/src/backend/yudao-server/target/yudao-server.jar
install -m 0644 "$BACKEND_JAR" "$target_jar.next"
[[ $(sha256sum "$target_jar.next" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
mv -f -- "$target_jar.next" "$target_jar"
python3 "$ROOT/ops/runtime/publish-backend.py"

[[ ! -e $MINIAPP_TARGET && $(dirname "$MINIAPP_TARGET") == /var/www/print-system/releases ]]
sudo -n install -d -o ubuntu -g ubuntu -m 0755 -- "$MINIAPP_TARGET"
cp -a "$MINIAPP_DIST/." "$MINIAPP_TARGET/"
[[ $(sha256sum "$MINIAPP_TARGET/index.html" | cut -d' ' -f1) == "$EXPECTED_MINIAPP_INDEX" ]]
(cd "$MINIAPP_TARGET" && sha256sum -c "$MINIAPP_MANIFEST") >/dev/null
install -m 0644 "$MINIAPP_MANIFEST" "$MINIAPP_TARGET/SHA256SUMS"
atomic_miniapp_link "$MINIAPP_TARGET"

sudo -n systemctl reload nginx
curl --fail --silent --max-time 10 http://127.0.0.1:18080/miniapp/ >/dev/null
python3 "$ENGINEERING/ops/acceptance/m9_pre_enable_regression.py" >"$BACKUP/pre-enable-regression.log" 2>&1

sudo -n install -o root -g root -m 0644 "$DROPIN_ENABLED" \
  /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf
sudo -n systemctl daemon-reload
sudo -n systemctl restart print-backend.service
wait_backend

probe_headers=$BACKUP/route-probe.headers
probe_body=$BACKUP/route-probe.json
probe_http=429
for attempt in 1 2 3 4; do
  probe_http=$(curl --silent --show-error --max-time 10 -D "$probe_headers" -o "$probe_body" -w '%{http_code}' \
    -H 'Origin: http://127.0.0.1:18080' \
    -H 'Idempotency-Key: m9-route-probe' \
    -F 'files=@/dev/null;filename=probe.png;type=image/png' \
    http://127.0.0.1:18080/app-api/print/miniapp/demo/image-collages)
  [[ $probe_http != 429 ]] && break
  (( attempt < 4 )) && sleep 7
done
[[ $probe_http == 401 ]]
python3 - "$probe_body" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as stream:
    body = json.load(stream)
raise SystemExit(0 if body.get('code') == 1926003 and body.get('data') is None else 1)
PY
[[ $(sha256sum /etc/nginx/sites-available/print-system | cut -d' ' -f1) == "$EXPECTED_NGINX_SITE" ]]
[[ $(sha256sum /etc/nginx/conf.d/print-miniapp-limits.conf | cut -d' ' -f1) == "$EXPECTED_NGINX_LIMITS" ]]
[[ $(sha256sum /etc/systemd/system/print-backend.service.d/50-m9-image-collage.conf | cut -d' ' -f1) == "$EXPECTED_DROPIN" ]]
[[ $(readlink -f "$ROOT/runtime/releases/current/backend.jar" | xargs sha256sum | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
[[ $(readlink -f /var/www/print-system/miniapp/index.html | xargs sha256sum | cut -d' ' -f1) == "$EXPECTED_MINIAPP_INDEX" ]]
systemctl is-active --quiet mysql
systemctl is-active --quiet redis-server
systemctl is-active --quiet nginx
systemctl is-active --quiet print-backend.service

SUCCESS=1
trap - EXIT
printf 'M9_DEPLOY_PASS run_id=%s backend=%s miniapp=%s backup=%s\n' \
  "$RUN_ID" "$(readlink -f "$ROOT/runtime/releases/current")" "$(readlink -f /var/www/print-system/miniapp)" "$BACKUP"
