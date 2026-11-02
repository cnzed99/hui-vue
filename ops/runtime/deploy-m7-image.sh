#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=/home/ubuntu/print-system
ENGINEERING=$ROOT/runtime/worktrees/m5-text-engineering-20261031
BACKEND_JAR=$ROOT/runtime/worktrees/m7-image-backend-20261102/yudao-server/target/yudao-server.jar
MINIAPP_DIST=$ROOT/runtime/worktrees/m7-image-miniapp-20261102/dist/build/h5
NGINX_CANDIDATE=$ENGINEERING/ops/runtime/candidates/m7/nginx-print-system.conf
DROPIN_CANDIDATE=$ENGINEERING/ops/runtime/candidates/m7/30-miniapp-demo.conf
EXPECTED_BACKEND=f95abc20388187c5c9d35401b45534403e9d2ff2d4e45108218b7505195a176c
EXPECTED_NGINX=8849eb340509b493c0b32e14a0fc4afd8b73f345a016625a835de5d32f485f06
EXPECTED_DROPIN=89f21fec3cb874b4bed278827b6ae15ebe1d37e89d8243c1674efc1680a6d595
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
BACKUP=$ROOT/runtime/backups/m7-image-$RUN_ID
MINIAPP_TARGET=/var/www/print-system/releases/miniapp-m7-image-$RUN_ID
SUCCESS=0

validate_route_probe() {
  python3 - "$1" "$2" "$3" <<'PY'
import json, sys

http_status, content_type, body_path = sys.argv[1:]
try:
    with open(body_path, encoding="utf-8") as stream:
        body = json.load(stream)
except (OSError, UnicodeError, json.JSONDecodeError):
    raise SystemExit(1)

valid = (
    http_status == "401"
    and content_type.lower().startswith("application/json")
    and isinstance(body, dict)
    and body.get("code") == 1926003
    and body.get("data") is None
)
raise SystemExit(0 if valid else 1)
PY
}

if [[ ${1:-} == --validate-route-probe ]]; then
  [[ $# == 4 ]] || exit 64
  validate_route_probe "$2" "$3" "$4"
  exit
fi
[[ $# == 0 ]] || exit 64

exec 9>"$ROOT/runtime/build-tmp/.build.lock"
if ! flock -n 9; then
  printf 'M7_IMAGE_DEPLOY_LOCKED run_id=%s\n' "$RUN_ID" >&2
  exit 75
fi

[[ $(sha256sum "$BACKEND_JAR" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
[[ $(sha256sum "$NGINX_CANDIDATE" | cut -d' ' -f1) == "$EXPECTED_NGINX" ]]
[[ $(sha256sum "$DROPIN_CANDIDATE" | cut -d' ' -f1) == "$EXPECTED_DROPIN" ]]
[[ -f $MINIAPP_DIST/index.html && ! -L $MINIAPP_DIST ]]
[[ $(df -PB1 "$ROOT" | awk 'NR==2 {print $4}') -ge 10737418240 ]]
MINIAPP_INDEX_SHA=$(sha256sum "$MINIAPP_DIST/index.html" | cut -d' ' -f1)

PREVIOUS_BACKEND=$(readlink -f "$ROOT/runtime/releases/current")
PREVIOUS_MINIAPP=$(readlink -f /var/www/print-system/miniapp)
[[ $PREVIOUS_BACKEND == "$ROOT/runtime/releases/"* ]]
[[ $PREVIOUS_MINIAPP == /var/www/print-system/releases/* ]]
install -d -m 0700 -- "$BACKUP"
sudo -n cp --preserve=mode,ownership,timestamps /etc/nginx/sites-available/print-system "$BACKUP/nginx.available"
sudo -n cp --preserve=mode,ownership,timestamps /etc/nginx/sites-enabled/print-system "$BACKUP/nginx.enabled"
sudo -n cp --preserve=mode,ownership,timestamps /etc/systemd/system/print-backend.service.d/30-miniapp-demo.conf "$BACKUP/30-miniapp-demo.conf"
printf '%s\n' "$PREVIOUS_BACKEND" > "$BACKUP/backend.previous"
printf '%s\n' "$PREVIOUS_MINIAPP" > "$BACKUP/miniapp.previous"
BACKUP_NGINX_AVAILABLE_SHA=$(sha256sum "$BACKUP/nginx.available" | cut -d' ' -f1)
BACKUP_NGINX_ENABLED_SHA=$(sha256sum "$BACKUP/nginx.enabled" | cut -d' ' -f1)
BACKUP_DROPIN_SHA=$(sha256sum "$BACKUP/30-miniapp-demo.conf" | cut -d' ' -f1)

atomic_backend_link() {
  local target=$1
  local link=$ROOT/runtime/releases/current.rollback
  rm -f -- "$link"
  ln -s "$(basename -- "$target")" "$link"
  mv -Tf -- "$link" "$ROOT/runtime/releases/current"
}

atomic_miniapp_link() {
  local target=$1
  local link=/var/www/print-system/miniapp.rollback
  sudo -n rm -f -- "$link"
  sudo -n ln -s "$target" "$link"
  sudo -n mv -Tf -- "$link" /var/www/print-system/miniapp
}

rollback() {
  local rc=$?
  local rollback_failed=0
  local health_ok=0
  local health_body=$BACKUP/rollback-health.json
  trap - EXIT
  if (( SUCCESS == 0 )); then
    (( rc != 0 )) || rc=1
    set +e
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.available" /etc/nginx/sites-available/print-system || rollback_failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/nginx.enabled" /etc/nginx/sites-enabled/print-system || rollback_failed=1
    sudo -n install -o root -g root -m 0644 "$BACKUP/30-miniapp-demo.conf" /etc/systemd/system/print-backend.service.d/30-miniapp-demo.conf || rollback_failed=1
    atomic_backend_link "$PREVIOUS_BACKEND" || rollback_failed=1
    atomic_miniapp_link "$PREVIOUS_MINIAPP" || rollback_failed=1
    sudo -n systemctl daemon-reload || rollback_failed=1
    sudo -n nginx -t || rollback_failed=1
    sudo -n systemctl reload nginx || rollback_failed=1
    sudo -n systemctl restart print-backend.service || rollback_failed=1
    for _ in $(seq 1 90); do
      if curl --fail --silent --max-time 3 -o "$health_body" \
          http://127.0.0.1:48080/admin-api/system/auth/get-permission-info \
          && python3 - "$health_body" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    body = json.load(stream)
raise SystemExit(0 if body.get("code") == 401 else 1)
PY
      then
        health_ok=1
        break
      fi
      sleep 1
    done
    (( health_ok == 1 )) || rollback_failed=1
    [[ $(sha256sum /etc/nginx/sites-available/print-system | cut -d' ' -f1) == "$BACKUP_NGINX_AVAILABLE_SHA" ]] || rollback_failed=1
    [[ $(sha256sum /etc/nginx/sites-enabled/print-system | cut -d' ' -f1) == "$BACKUP_NGINX_ENABLED_SHA" ]] || rollback_failed=1
    [[ $(sha256sum /etc/systemd/system/print-backend.service.d/30-miniapp-demo.conf | cut -d' ' -f1) == "$BACKUP_DROPIN_SHA" ]] || rollback_failed=1
    [[ $(readlink -f "$ROOT/runtime/releases/current") == "$PREVIOUS_BACKEND" ]] || rollback_failed=1
    [[ $(readlink -f /var/www/print-system/miniapp) == "$PREVIOUS_MINIAPP" ]] || rollback_failed=1
    systemctl is-active --quiet mysql || rollback_failed=1
    systemctl is-active --quiet redis-server || rollback_failed=1
    systemctl is-active --quiet nginx || rollback_failed=1
    systemctl is-active --quiet print-backend.service || rollback_failed=1
    if (( rollback_failed == 0 )); then
      printf 'M7_IMAGE_DEPLOY_ROLLED_BACK run_id=%s original_rc=%s\n' "$RUN_ID" "$rc" >&2
    else
      printf 'M7_IMAGE_DEPLOY_ROLLBACK_FAILED run_id=%s original_rc=%s backup=%s\n' "$RUN_ID" "$rc" "$BACKUP" >&2
      rc=70
    fi
  fi
  exit "$rc"
}
trap rollback EXIT

sudo -n install -o root -g root -m 0644 "$NGINX_CANDIDATE" /etc/nginx/sites-available/print-system.next
sudo -n install -o root -g root -m 0644 "$NGINX_CANDIDATE" /etc/nginx/sites-enabled/print-system.next
sudo -n install -o root -g root -m 0644 "$DROPIN_CANDIDATE" /etc/systemd/system/print-backend.service.d/30-miniapp-demo.conf.next
sudo -n mv -f /etc/nginx/sites-available/print-system.next /etc/nginx/sites-available/print-system
sudo -n mv -f /etc/nginx/sites-enabled/print-system.next /etc/nginx/sites-enabled/print-system
sudo -n mv -f /etc/systemd/system/print-backend.service.d/30-miniapp-demo.conf.next \
  /etc/systemd/system/print-backend.service.d/30-miniapp-demo.conf
sudo -n systemctl daemon-reload
sudo -n nginx -t
sudo -n systemctl reload nginx

target_jar=$ROOT/src/backend/yudao-server/target/yudao-server.jar
install -m 0644 "$BACKEND_JAR" "$target_jar.next"
[[ $(sha256sum "$target_jar.next" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
mv -f -- "$target_jar.next" "$target_jar"
python3 "$ROOT/ops/runtime/publish-backend.py"

[[ ! -e $MINIAPP_TARGET && $(dirname "$MINIAPP_TARGET") == /var/www/print-system/releases ]]
sudo -n install -d -o ubuntu -g ubuntu -m 0755 -- "$MINIAPP_TARGET"
cp -a "$MINIAPP_DIST/." "$MINIAPP_TARGET/"
[[ -f $MINIAPP_TARGET/index.html && ! -L $MINIAPP_TARGET/index.html ]]
[[ $(sha256sum "$MINIAPP_TARGET/index.html" | cut -d' ' -f1) == "$MINIAPP_INDEX_SHA" ]]
find "$MINIAPP_TARGET" -type f -print0 | sort -z | xargs -0 sha256sum > "$MINIAPP_TARGET/SHA256SUMS"
atomic_miniapp_link "$MINIAPP_TARGET"

curl --fail --silent --show-error --max-time 10 http://127.0.0.1:18080/miniapp/ >/dev/null
probe_ok=0
for attempt in 1 2 3; do
  probe_headers=$BACKUP/route-probe-$attempt.headers
  probe_body=$BACKUP/route-probe-$attempt.json
  probe_http=$(curl --silent --show-error --max-time 10 -D "$probe_headers" -o "$probe_body" -w '%{http_code}' \
    -H 'Origin: http://127.0.0.1:18080' \
    -F 'file=@/dev/null;filename=probe.jpg;type=image/jpeg' \
    http://127.0.0.1:18080/app-api/print/miniapp/demo/image-files)
  probe_content_type=$(awk 'BEGIN{IGNORECASE=1} /^Content-Type:/ {gsub("\\r", ""); sub(/^[^:]+:[[:space:]]*/, ""); print; exit}' "$probe_headers")
  probe_code=$(python3 - "$probe_body" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as stream:
        body = json.load(stream)
    print(body.get("code", ""))
except Exception:
    print("")
PY
)
  printf 'M7_IMAGE_ROUTE_PROBE attempt=%s http=%s content_type=%s code=%s\n' \
    "$attempt" "$probe_http" "$probe_content_type" "$probe_code"
  if validate_route_probe "$probe_http" "$probe_content_type" "$probe_body"; then
    probe_ok=1
    break
  fi
  [[ $probe_http == 429 ]] || break
  sleep 2
done
(( probe_ok == 1 ))
[[ $(sha256sum /etc/nginx/sites-available/print-system | cut -d' ' -f1) == "$EXPECTED_NGINX" ]]
[[ $(sha256sum /etc/nginx/sites-enabled/print-system | cut -d' ' -f1) == "$EXPECTED_NGINX" ]]
[[ $(sha256sum /etc/systemd/system/print-backend.service.d/30-miniapp-demo.conf | cut -d' ' -f1) == "$EXPECTED_DROPIN" ]]
systemctl is-active --quiet mysql
systemctl is-active --quiet redis-server
systemctl is-active --quiet nginx
systemctl is-active --quiet print-backend.service

SUCCESS=1
trap - EXIT
printf 'M7_IMAGE_DEPLOY_PASS run_id=%s backend=%s miniapp=%s backup=%s\n' \
  "$RUN_ID" "$(readlink -f "$ROOT/runtime/releases/current")" "$(readlink -f /var/www/print-system/miniapp)" "$BACKUP"
