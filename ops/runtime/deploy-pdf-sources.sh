#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=/home/ubuntu/print-system
ENGINEERING=$ROOT/runtime/worktrees/pdf-source-routing-engineering-20261102
MINI=$ROOT/runtime/worktrees/pdf-source-routing-miniapp-20261102
BACK=$ROOT/runtime/worktrees/pdf-catalog-backend-20261102
[[ $# == 1 && $1 =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] || exit 64
RUN_ID=$1
OUT=$ROOT/evidence/deploy/pdf-sources-$RUN_ID
BACKUP=$ROOT/runtime/backups/pdf-sources-$RUN_ID
[[ -f $OUT/build-manifest.json ]]
exec 9>>"$ROOT/runtime/build-tmp/.build.lock"
flock -n 9 || { echo BUILD_OR_DEPLOY_LOCKED; exit 75; }
[[ $(git -C "$MINI" rev-parse HEAD) == 27abcbe945ceb567a315f961d8bb50bd3bb2dc87 ]]
[[ $(git -C "$BACK" rev-parse HEAD) == 7efd316fb154c65fff089da7a5919284fb1f8cd6 ]]
git -C "$MINI" diff --quiet; git -C "$BACK" diff --quiet
[[ $(df -PB1 "$ROOT" | awk 'NR==2{print $4}') -ge 10737418240 ]]
EXPECTED_BACKEND=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["backendJar"]["sha256"])' "$OUT/build-manifest.json")
EXPECTED_MINIAPP=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["h5Index"]["sha256"])' "$OUT/build-manifest.json")
JAR=$BACK/yudao-server/target/yudao-server.jar
DIST=$MINI/dist/build/h5
[[ $(sha256sum "$JAR" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
[[ $(sha256sum "$DIST/index.html" | cut -d' ' -f1) == "$EXPECTED_MINIAPP" ]]
[[ -z $(find "$DIST" -type l -print -quit) ]]
PREVIOUS_BACKEND=$(readlink -f "$ROOT/runtime/releases/current")
PREVIOUS_MINIAPP=$(readlink -f /var/www/print-system/miniapp)
[[ $PREVIOUS_BACKEND == "$ROOT/runtime/releases/"* && $PREVIOUS_MINIAPP == /var/www/print-system/releases/* ]]
NGINX_AVAILABLE=$(sha256sum /etc/nginx/sites-available/print-system | cut -d' ' -f1)
NGINX_ENABLED=$(sha256sum /etc/nginx/sites-enabled/print-system | cut -d' ' -f1)
ADMIN_INDEX=$(sha256sum /var/www/print-system/index.html | cut -d' ' -f1)
install -d -m 0700 "$BACKUP"
printf '%s\n' "$PREVIOUS_BACKEND" >"$BACKUP/backend.previous"
printf '%s\n' "$PREVIOUS_MINIAPP" >"$BACKUP/miniapp.previous"
SUCCESS=0
backend_link() {
  local target=$1 temporary=$ROOT/runtime/releases/current.pdf-sources-next
  [[ ! -e $temporary && ! -L $temporary ]]
  ln -s "$(basename "$target")" "$temporary"
  mv -Tf "$temporary" "$ROOT/runtime/releases/current"
}
miniapp_link() {
  local target=$1 temporary=/var/www/print-system/miniapp.pdf-sources-next
  [[ ! -e $temporary && ! -L $temporary ]]
  sudo -n ln -s "$target" "$temporary"
  sudo -n mv -Tf "$temporary" /var/www/print-system/miniapp
}
wait_backend() {
  local status
  for _ in $(seq 1 90); do
    status=$(curl -sS --max-time 3 -o "$OUT/health.json" -w '%{http_code}' http://127.0.0.1:48080/admin-api/system/auth/get-permission-info 2>/dev/null || true)
    if [[ $status == 200 ]] && python3 -c 'import json,sys;raise SystemExit(0 if json.load(open(sys.argv[1])).get("code")==401 else 1)' "$OUT/health.json" 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}
rollback() {
  local rc=$? failed=0
  trap - EXIT INT TERM
  if (( SUCCESS == 0 )); then
    (( rc != 0 )) || rc=1
    set +e
    backend_link "$PREVIOUS_BACKEND" || failed=1
    miniapp_link "$PREVIOUS_MINIAPP" || failed=1
    sudo -n systemctl restart print-backend.service || failed=1
    wait_backend || failed=1
    for service in mysql redis-server nginx print-backend.service; do systemctl is-active --quiet "$service" || failed=1; done
    printf 'PDF_SOURCES_ROLLBACK failed=%s run=%s\n' "$failed" "$RUN_ID"
    (( failed == 0 )) || rc=70
  fi
  exit "$rc"
}
trap rollback EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
RELEASE=$ROOT/runtime/releases/${EXPECTED_BACKEND:0:16}
[[ ! -L $RELEASE ]]
install -d -m 0755 "$RELEASE"
if [[ -e $RELEASE/backend.jar ]]; then
  [[ $(sha256sum "$RELEASE/backend.jar" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
else
  install -m 0644 "$JAR" "$RELEASE/backend.jar.next"
  [[ $(sha256sum "$RELEASE/backend.jar.next" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
  mv "$RELEASE/backend.jar.next" "$RELEASE/backend.jar"
fi
TARGET=/var/www/print-system/releases/miniapp-pdf-sources-$RUN_ID
[[ ! -e $TARGET && $(dirname "$TARGET") == /var/www/print-system/releases ]]
sudo -n install -d -o ubuntu -g ubuntu -m 0755 "$TARGET"
cp -a "$DIST/." "$TARGET/"
(cd "$DIST" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) >"$OUT/miniapp-SHA256SUMS"
(cd "$TARGET" && sha256sum -c "$OUT/miniapp-SHA256SUMS") >"$OUT/miniapp-tree-check.log"
install -m 0644 "$OUT/miniapp-SHA256SUMS" "$TARGET/SHA256SUMS"
backend_link "$RELEASE"
miniapp_link "$TARGET"
sudo -n systemctl restart print-backend.service
wait_backend
curl -fsS --max-time 10 http://127.0.0.1:18080/miniapp/ >/dev/null
python3 "$ENGINEERING/ops/acceptance/pdf_sources_runtime_smoke.py" --evidence "$OUT/runtime-smoke.json" >"$OUT/runtime-smoke.log" 2>&1
PATH="$ROOT/runtime/node/current/bin:$ROOT/runtime/npm/bin:/usr/bin:/bin" node "$ENGINEERING/ops/acceptance/pdf_sources_browser.mjs" "$OUT" >"$OUT/browser.log" 2>&1
[[ $(sha256sum /etc/nginx/sites-available/print-system | cut -d' ' -f1) == "$NGINX_AVAILABLE" ]]
[[ $(sha256sum /etc/nginx/sites-enabled/print-system | cut -d' ' -f1) == "$NGINX_ENABLED" ]]
[[ $(sha256sum /var/www/print-system/index.html | cut -d' ' -f1) == "$ADMIN_INDEX" ]]
[[ $(sha256sum "$ROOT/runtime/releases/current/backend.jar" | cut -d' ' -f1) == "$EXPECTED_BACKEND" ]]
[[ $(sha256sum /var/www/print-system/miniapp/index.html | cut -d' ' -f1) == "$EXPECTED_MINIAPP" ]]
for service in mysql redis-server nginx print-backend.service; do systemctl is-active --quiet "$service"; done
python3 - "$OUT/deployment.json" "$RUN_ID" "$PREVIOUS_BACKEND" "$PREVIOUS_MINIAPP" "$RELEASE" "$TARGET" "$EXPECTED_BACKEND" "$EXPECTED_MINIAPP" "$NGINX_AVAILABLE" "$NGINX_ENABLED" "$ADMIN_INDEX" <<'PY'
import datetime,json,pathlib,shutil,sys
p,run,old_back,old_mini,back,mini,jar,index,available,enabled,admin=sys.argv[1:]
x={'schemaVersion':1,'status':'PASS','runId':run,'finishedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'backendCommit':'7efd316fb154c65fff089da7a5919284fb1f8cd6','miniappCommit':'27abcbe945ceb567a315f961d8bb50bd3bb2dc87',
 'previousBackend':old_back,'previousMiniapp':old_mini,'backendCurrent':back,'miniappCurrent':mini,'backendJarSha256':jar,'miniappIndexSha256':index,
 'nginxAvailableSha256':available,'nginxEnabledSha256':enabled,'adminIndexSha256':admin,'nginxAndAdminUnchanged':True,
 'servicesActive':4,'backendHttpStatus':200,'backendBusinessCode':401,'freeBytes':shutil.disk_usage('/home/ubuntu/print-system').free,
 'independentReview':{'role':'sol_reviewer','model':'gpt-6.1-sol','effort':'high','result':'PASS','source':'COORDINATOR_REPORTED_REVIEW_RESULT'},
 'runtimeSmoke':'runtime-smoke.json','browserSmoke':'browser-report.json','wechatDevice':'NOT_RUN','realPayment':'NOT_RUN','realPrinter':'NOT_RUN'}
pathlib.Path(p).write_text(json.dumps(x,indent=2)+'\n')
PY
SUCCESS=1
trap - EXIT INT TERM
printf 'PDF_SOURCES_DEPLOY_PASS run=%s backend=%s miniapp=%s\n' "$RUN_ID" "$RELEASE" "$TARGET"
