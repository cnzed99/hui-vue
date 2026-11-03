#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

repo=$(git rev-parse --show-toplevel)
project=/home/ubuntu/print-system
candidate=${1:-$repo/yudao-server/target/yudao-server.jar}
old_jar=$(readlink -f "$project/runtime/releases/current/backend.jar")
pdf_fixture=$project/evidence/deploy/pdf-sources-20261102T153807Z-afbb79a0/safe-fixture.pdf
mysql_port=${MYSQL_PORT:-45913}
app_port=${APP_PORT:-48183}
scratch=$(mktemp -d /tmp/print-id-photo-http-XXXXXXXX)
[[ $(realpath -e -- "$scratch") == "$scratch" && ! -L $scratch && $scratch == /tmp/print-id-photo-http-???????? ]]
app_pid=''
app_log=''
mysql_pid=''
child_pids=()

process_alive() {
  local pid=$1 state
  kill -0 "$pid" 2>/dev/null || return 1
  state=$(ps -o stat= -p "$pid" 2>/dev/null | tr -d ' ')
  [[ -n $state && $state != Z* ]]
}

on_error() {
  local rc=$? line=$1
  echo "ID_PHOTO_HTTP_PROBE_FAILED line=$line rc=$rc" >&2
  if [[ -n ${app_log:-} && -f ${app_log:-} ]]; then
    install -m 0600 "$app_log" /tmp/id-photo-http-last-failure.log
    grep -nE 'Caused by:|ERROR|Exception|IllegalStateException' "$app_log" | tail -80 >&2 || true
    tail -120 "$app_log" >&2
  fi
  exit "$rc"
}
trap 'on_error $LINENO' ERR

cleanup() {
  local pid
  for pid in "${child_pids[@]}"; do
    if [[ -n $pid ]] && process_alive "$pid"; then kill "$pid" 2>/dev/null || true; fi
  done
  for pid in "${child_pids[@]}"; do
    if [[ -n $pid ]]; then wait "$pid" 2>/dev/null || true; fi
  done
  if [[ -n $mysql_pid ]] && kill -0 "$mysql_pid" 2>/dev/null; then
    kill "$mysql_pid" 2>/dev/null || true
    wait "$mysql_pid" 2>/dev/null || true
  fi
  rm -rf --one-file-system -- "$scratch"
}
trap cleanup EXIT

for required in "$candidate" "$old_jar" "$pdf_fixture" "$project/runtime/secrets/runtime.env"; do
  [[ -f $required ]]
done
[[ $(sha256sum "$candidate" | awk '{print $1}') != $(sha256sum "$old_jar" | awk '{print $1}') ]]

mkdir -- "$scratch/mysql" "$scratch/classes" "$scratch/storage" "$scratch/multipart" "$scratch/logs"
mysqld --no-defaults --initialize-insecure --datadir="$scratch/mysql" --log-error="$scratch/mysql-init.log"
mysqld --no-defaults --datadir="$scratch/mysql" --socket="$scratch/mysql.sock" \
  --pid-file="$scratch/mysql.pid" --bind-address=127.0.0.1 --port="$mysql_port" --mysqlx=0 \
  --innodb-buffer-pool-size=32M --performance-schema=OFF --skip-log-bin --log-error="$scratch/mysql.log" &
mysql_pid=$!
socket=(--no-defaults --protocol=socket --socket="$scratch/mysql.sock" -uroot)
for _ in $(seq 1 30); do
  kill -0 "$mysql_pid"
  if mysqladmin "${socket[@]}" ping --silent; then break; fi
  sleep 1
done
[[ $(mysql "${socket[@]}" -Nse 'SELECT @@datadir') == "$scratch/mysql/" ]]

mysql "${socket[@]}" <<'SQL'
CREATE DATABASE id_photo_http;
USE id_photo_http;
CREATE TABLE system_tenant (id BIGINT PRIMARY KEY, status INT NOT NULL, deleted BIT NOT NULL, expire_time DATETIME NOT NULL);
CREATE TABLE print_store (tenant_id BIGINT NOT NULL, id BIGINT NOT NULL, status INT NOT NULL, deleted BIT NOT NULL,
  PRIMARY KEY(tenant_id,id));
CREATE TABLE print_price_profile (
  tenant_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  profile_key VARCHAR(64) NOT NULL, version VARCHAR(64) NOT NULL, mode VARCHAR(16) NOT NULL,
  currency VARCHAR(8) NOT NULL, simplex_price_per_sheet VARCHAR(64) NOT NULL,
  duplex_price_per_sheet VARCHAR(64) NOT NULL, tier_json JSON NULL,
  PRIMARY KEY(tenant_id,store_id,id)
);
INSERT INTO system_tenant VALUES (1,0,b'0',CURRENT_TIMESTAMP + INTERVAL 1 DAY);
INSERT INTO print_store VALUES (1,1,0,b'0');
INSERT INTO print_price_profile VALUES
  (1,1,'11111111-1111-1111-1111-111111111111','http-fixture','v1','DEMO','CNY','0.10','0.20',NULL);
SQL
# Framework security/error handlers touch these tables before/after @PermitAll controllers.
# Copy schema only from the local deployment; no rows or business data leave the live database.
sudo -n mysqldump --no-data --skip-triggers --compact print_demo \
  system_oauth2_access_token system_oauth2_refresh_token system_oauth2_client \
  infra_api_access_log infra_api_error_log \
  | mysql "${socket[@]}" id_photo_http
mysql "${socket[@]}" id_photo_http < "$project/ops/db/print-miniapp-demo-session-v1.sql"
mysql "${socket[@]}" id_photo_http < "$project/ops/db/print-miniapp-demo-files-v1.sql"
mysql "${socket[@]}" id_photo_http < "$repo/ops/db/print-miniapp-demo-archive-provenance.sql"
mysql "${socket[@]}" id_photo_http < "$project/runtime/candidates/m3-quote/print-miniapp-demo-quotes-v1.sql"

# Simulate an interrupted migration: only the additive columns and CHECK have committed.
sed '/^DELIMITER \/\//,$d' "$repo/ops/db/print-miniapp-demo-id-photo-source.sql" \
  | mysql "${socket[@]}" id_photo_http
[[ $(mysql "${socket[@]}" -Nse "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='id_photo_http' AND TABLE_NAME='print_miniapp_demo_file' AND COLUMN_NAME IN ('source_kind','source_variant')") == 2 ]]
[[ $(mysql "${socket[@]}" -Nse "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='id_photo_http' AND TRIGGER_NAME='trg_print_demo_quote_document_only'") == 0 ]]

set -a
source "$project/runtime/secrets/runtime.env"
set +a

start_app() {
  local jar=$1 port=$2 min_free=$3 render_version=$4 log=$5 storage=${6:-$scratch/storage}
  mkdir -p -- "$storage" "$scratch/multipart-$port" "$scratch/runtime-$port"
  : >"$log"
  env \
    PRINT_RUNTIME_DIR="$scratch/runtime-$port" \
    PRINT_MINIAPP_DEMO_ENABLED=true \
    PRINT_MINIAPP_DEMO_ISSUANCE_ENABLED=true \
    PRINT_MINIAPP_DEMO_TENANT_ID=1 \
    PRINT_MINIAPP_DEMO_STORE_ID=1 \
    PRINT_MINIAPP_DEMO_ALLOWED_ORIGIN="http://127.0.0.1:$port" \
    PRINT_MINIAPP_DEMO_EDGE_HOST="127.0.0.1:$port" \
    PRINT_MINIAPP_DEMO_TTL_SECONDS=3600 \
    PRINT_MINIAPP_DEMO_MAX_ACTIVE_SESSIONS=40 \
    PRINT_MINIAPP_DEMO_MAX_ISSUE_PER_MINUTE=40 \
    PRINT_MINIAPP_DEMO_FILES_ENABLED=true \
    PRINT_MINIAPP_DEMO_FILES_STORAGE_ROOT="$storage" \
    PRINT_MINIAPP_DEMO_FILES_RUNTIME_JAR="$jar" \
    PRINT_MINIAPP_DEMO_FILES_MAX_BYTES=2097152 \
    PRINT_MINIAPP_DEMO_FILES_MAX_PAGES=20 \
    PRINT_MINIAPP_DEMO_FILES_RETENTION_SECONDS=3600 \
    PRINT_MINIAPP_DEMO_FILES_QUEUE_CAPACITY=20 \
    PRINT_MINIAPP_DEMO_FILES_MAX_ACTIVE_FILES_PER_PRINCIPAL=10 \
    PRINT_MINIAPP_DEMO_FILES_MAX_PROCESSING_ATTEMPTS=3 \
    PRINT_MINIAPP_DEMO_FILES_PARSER_TIMEOUT_SECONDS=10 \
    PRINT_MINIAPP_DEMO_FILES_MAX_CONCURRENT_UPLOADS=2 \
    PRINT_MINIAPP_DEMO_FILES_MIN_FREE_BYTES="$min_free" \
    PRINT_MINIAPP_DEMO_ID_PHOTO_ENABLED=true \
    PRINT_MINIAPP_DEMO_ID_PHOTO_RENDER_VERSION="$render_version" \
    PRINT_MINIAPP_DEMO_ID_PHOTO_MAX_INPUT_BYTES=2097152 \
    PRINT_MINIAPP_DEMO_ID_PHOTO_MAX_MULTIPART_BYTES=2150400 \
    PRINT_MINIAPP_DEMO_ID_PHOTO_MAX_WIDTH=2400 \
    PRINT_MINIAPP_DEMO_ID_PHOTO_MAX_HEIGHT=3394 \
    PRINT_MINIAPP_DEMO_ID_PHOTO_MAX_PIXELS=8145600 \
    PRINT_MINIAPP_DEMO_ID_PHOTO_MAX_PDF_BYTES=2097152 \
    PRINT_MINIAPP_DEMO_ID_PHOTO_MAX_CONCURRENT_REQUESTS=1 \
    PRINT_MINIAPP_DEMO_ID_PHOTO_RENDER_TIMEOUT_SECONDS=15 \
    PRINT_MINIAPP_DEMO_QUOTES_ENABLED=true \
    PRINT_MINIAPP_DEMO_QUOTES_TTL_SECONDS=600 \
    PRINT_MINIAPP_DEMO_QUOTES_PRICE_PROFILE_ID=11111111-1111-1111-1111-111111111111 \
    PRINT_MINIAPP_DEMO_QUOTES_MAX_ACTIVE_QUOTES_PER_PRINCIPAL=20 \
    SPRING_SERVLET_MULTIPART_ENABLED=true \
    SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE=3MB \
    SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE=4MB \
    SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD=0B \
    SPRING_SERVLET_MULTIPART_LOCATION="$scratch/multipart-$port" \
    java -Xms96m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC -jar "$jar" \
      --spring.profiles.active=cloud \
      --spring.config.additional-location="file:$project/ops/runtime/application-cloud.yaml" \
      --server.port="$port" \
      --spring.datasource.dynamic.datasource.master.url="jdbc:mysql://127.0.0.1:$mysql_port/id_photo_http?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true" \
      --spring.datasource.dynamic.datasource.master.username=root \
      --spring.datasource.dynamic.datasource.master.password= \
      --spring.data.redis.database=15 \
      --logging.file.name="$scratch/logs/backend-$port.log" >"$log" 2>&1 &
  app_pid=$!
  app_log=$log
  child_pids+=("$app_pid")
}

wait_up() {
  local port=$1
  local deadline=$((SECONDS + 120)) successes=0 code
  local catalog="$scratch/catalog-$port.json"
  while (( SECONDS < deadline )); do
    if ! process_alive "$app_pid"; then tail -120 "$app_log" >&2; return 1; fi
    if grep -Fq 'APPLICATION FAILED TO START' "$app_log"; then tail -120 "$app_log" >&2; return 1; fi
    if grep -Fq 'Started YudaoServerApplication in ' "$app_log"; then
      code=$(curl --connect-timeout 1 --max-time 3 -sS -o "$catalog" -w '%{http_code}' \
        "http://127.0.0.1:$port/app-api/print/miniapp/catalog" || true)
      if [[ $code == 200 ]] && jq -e '.code == 0 and (.data.catalogVersion | type == "string") and (.data.methods | type == "array")' "$catalog" >/dev/null; then
        successes=$((successes + 1))
        if (( successes >= 2 )); then process_alive "$app_pid"; return 0; fi
      else
        successes=0
      fi
    fi
    sleep 1
  done
  tail -120 "$app_log" >&2
  return 1
}

stop_app() {
  if [[ -n $app_pid ]] && process_alive "$app_pid"; then
    kill "$app_pid"
    for _ in $(seq 1 120); do process_alive "$app_pid" || break; sleep 0.25; done
    process_alive "$app_pid" && { echo 'APP_STOP_TIMEOUT' >&2; return 1; }
  fi
  [[ -n $app_pid ]] && wait "$app_pid" 2>/dev/null || true
  app_pid=''
  app_log=''
}

issue_session() {
  local port=$1 output=$2
  curl -fsS -X POST -H "Origin: http://127.0.0.1:$port" -H "Host: 127.0.0.1:$port" \
    "http://127.0.0.1:$port/app-api/print/miniapp/demo/sessions" >"$output"
  jq -e '.code == 0 and (.data.accessToken | length == 43)' "$output" >/dev/null
}

wait_file_ready() {
  local port=$1 token=$2 id=$3 output=$4
  for _ in $(seq 1 40); do
    curl -fsS -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$port" \
      -H "Host: 127.0.0.1:$port" \
      "http://127.0.0.1:$port/app-api/print/miniapp/demo/file?id=$id" >"$output"
    [[ $(jq -r '.data.status' "$output") == READY ]] && return 0
    [[ $(jq -r '.data.status' "$output") == FAILED ]] && return 1
    sleep 0.25
  done
  return 1
}

# Old JAR must remain usable after the additive ALTER committed but before the trigger exists.
start_app "$old_jar" "$app_port" 10737418240 disabled-on-old-jar "$scratch/logs/stdout-$app_port.log"
wait_up "$app_port"
issue_session "$app_port" "$scratch/old-session.json"
token=$(jq -r '.data.accessToken' "$scratch/old-session.json")
ordinary=$(curl -fsS -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" \
  -H "Host: 127.0.0.1:$app_port" -H 'Idempotency-Key: old-jar-document' \
  -F "file=@$pdf_fixture;type=application/pdf" \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/files")
ordinary_id=$(jq -er '.data.id' <<<"$ordinary")
wait_file_ready "$app_port" "$token" "$ordinary_id" "$scratch/old-file.json"
old_quote=$(curl -fsS -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" \
  -H "Host: 127.0.0.1:$app_port" -H 'Idempotency-Key: old-jar-quote' -H 'Content-Type: application/json' \
  --data "{\"fileId\":\"$ordinary_id\",\"paper\":\"A4\",\"color\":\"BLACK_WHITE\",\"copies\":1,\"side\":\"SIMPLEX\"}" \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/quotes")
jq -e '.code == 0 and .data.status == "VALID"' <<<"$old_quote" >/dev/null
old_quote_id=$(jq -er '.data.id' <<<"$old_quote")
stop_app
echo 'ID_PHOTO_HTTP_PARTIAL_MIGRATION_OLD_JAR_PASS'

# Complete the exact migration by adding the quote protection trigger.
awk 'BEGIN{p=0} /^DELIMITER \/\//{p=1} p{print}' "$repo/ops/db/print-miniapp-demo-id-photo-source.sql" \
  | mysql "${socket[@]}" id_photo_http
[[ $(mysql "${socket[@]}" -Nse "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='id_photo_http' AND TRIGGER_NAME='trg_print_demo_quote_document_only'") == 1 ]]

# A bad new-JAR configuration must fail closed; the old JAR must then recover on the preserved schema.
start_app "$candidate" "$app_port" 10737418240 invalid-render-version "$scratch/logs/stdout-$app_port.log"
for _ in $(seq 1 60); do
  process_alive "$app_pid" || break
  sleep 0.5
done
if process_alive "$app_pid"; then echo 'INVALID_NEW_JAR_STARTED' >&2; exit 1; fi
wait "$app_pid" 2>/dev/null || true
grep -Fq 'Enabled id photo renderer requires the approved fixed limits' "$scratch/logs/stdout-$app_port.log"
app_pid=''

start_app "$old_jar" "$app_port" 10737418240 disabled-on-old-jar "$scratch/logs/stdout-$app_port.log"
wait_up "$app_port"
wait_file_ready "$app_port" "$token" "$ordinary_id" "$scratch/rollback-file.json"
quotes_before=$(mysql "${socket[@]}" id_photo_http -Nse 'SELECT COUNT(*) FROM print_miniapp_demo_quote')
rollback_quote=$(curl -fsS -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" \
  -H "Host: 127.0.0.1:$app_port" -H 'Idempotency-Key: old-jar-quote-after-trigger' -H 'Content-Type: application/json' \
  --data "{\"fileId\":\"$ordinary_id\",\"paper\":\"A4\",\"color\":\"BLACK_WHITE\",\"copies\":1,\"side\":\"SIMPLEX\"}" \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/quotes")
jq -e '.code == 0 and .data.status == "VALID"' <<<"$rollback_quote" >/dev/null
rollback_quote_id=$(jq -er '.data.id' <<<"$rollback_quote")
[[ $rollback_quote_id != "$old_quote_id" ]]
[[ $(mysql "${socket[@]}" id_photo_http -Nse 'SELECT COUNT(*) FROM print_miniapp_demo_quote') == $((quotes_before + 1)) ]]
stop_app
echo 'ID_PHOTO_HTTP_NEW_JAR_FAILURE_OLD_JAR_ROLLBACK_PASS'

# Generate a large, opaque, single-frame RGB PNG using only Python stdlib.
python3 - "$scratch/id-photo.png" <<'PY'
import binascii, struct, sys, zlib
path = sys.argv[1]
w, h = 2400, 3394
row = bytes((245, 245, 245)) * w
raw = b''.join(b'\x00' + row for _ in range(h))
def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data) & 0xffffffff)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
open(path, 'wb').write(png)
PY
[[ $(stat -c %s "$scratch/id-photo.png") -lt 2097152 ]]

cat >"$scratch/prebody_request.py" <<'PY'
import socket, sys
port, token, key, expected = sys.argv[1:]
port = int(port)
boundary = '----codex-prebody-boundary'
headers = (f'POST /app-api/print/miniapp/demo/id-photo-files HTTP/1.1\r\n'
           f'Host: 127.0.0.1:{port}\r\nOrigin: http://127.0.0.1:{port}\r\n'
           f'Authorization: Bearer {token}\r\nIdempotency-Key: {key}\r\n'
           f'Content-Type: multipart/form-data; boundary={boundary}\r\n'
           f'Content-Length: 100000\r\nConnection: close\r\n\r\n').encode()
s = socket.create_connection(('127.0.0.1', port), timeout=3)
s.settimeout(3)
s.sendall(headers)
response = s.recv(4096)
status = response.split(b'\r\n', 1)[0]
if f' {expected} '.encode() not in status:
    raise SystemExit(f'unexpected pre-body response: {status!r}')
PY

start_app "$candidate" "$app_port" 10737418240 id-photo-6in-v2 "$scratch/logs/stdout-$app_port.log"
wait_up "$app_port"
issue_session "$app_port" "$scratch/new-session.json"
token=$(jq -r '.data.accessToken' "$scratch/new-session.json")

# The rollback cleaner must contend with the real Java FileChannel lock, not a
# separate flock namespace, and must leave tasks untouched while Java owns it.
mkdir -m 0700 -- "$scratch/storage/id-photo-multipart/task-rollback-orphan" \
  "$scratch/storage/id-photo-scratch/task-rollback-orphan"
printf original >"$scratch/storage/id-photo-multipart/task-rollback-orphan/input.bin"
printf rendered >"$scratch/storage/id-photo-scratch/task-rollback-orphan/output.pdf"
if python3 "$repo/ops/runtime/cleanup-id-photo-private-tasks.py" "$scratch/storage" \
  >"$scratch/rollback-cleanup-locked.out" 2>"$scratch/rollback-cleanup-locked.err"; then
  cleanup_locked_rc=0
else
  cleanup_locked_rc=$?
fi
[[ $cleanup_locked_rc == 75 ]]
grep -Fxq ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_LOCK_BUSY "$scratch/rollback-cleanup-locked.err"
[[ -f $scratch/storage/id-photo-multipart/task-rollback-orphan/input.bin ]]
[[ -f $scratch/storage/id-photo-scratch/task-rollback-orphan/output.pdf ]]

# Invalid session and bad envelope fail without creating a multipart task.
before_tasks=$(find "$scratch/storage/id-photo-multipart" -mindepth 1 -maxdepth 1 -type d -name 'task-*' | wc -l)
python3 "$scratch/prebody_request.py" "$app_port" AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA invalid-session 401
bad_code=$(curl -sS -o "$scratch/bad-envelope.json" -w '%{http_code}' \
  -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" -H "Host: 127.0.0.1:$app_port" \
  -H 'Idempotency-Key: bad-envelope' -H 'Content-Type: application/json' --data '{}' \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/id-photo-files")
[[ $bad_code == 400 ]]
after_tasks=$(find "$scratch/storage/id-photo-multipart" -mindepth 1 -maxdepth 1 -type d -name 'task-*' | wc -l)
[[ $before_tasks == "$after_tasks" ]]

# Successful custom multipart parsing, persistence, idempotency, app quote rejection and DB trigger rejection.
create=$(curl -fsS -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" \
  -H "Host: 127.0.0.1:$app_port" -H 'Idempotency-Key: id-photo-success' \
  -F "file=@$scratch/id-photo.png;type=image/png" -F 'specification=ONE_INCH' \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/id-photo-files")
id_photo_id=$(jq -er '.data.id' <<<"$create")
replay_code=''
for _ in $(seq 1 20); do
  replay_code=$(curl -sS -o "$scratch/replay.json" -w '%{http_code}' \
    -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" \
    -H "Host: 127.0.0.1:$app_port" -H 'Idempotency-Key: id-photo-success' \
    -F "file=@$scratch/id-photo.png;type=image/png" -F 'specification=ONE_INCH' \
    "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/id-photo-files")
  [[ $replay_code == 200 ]] && break
  [[ $replay_code == 429 ]]
  sleep 0.25
done
[[ $replay_code == 200 ]]
replay=$(<"$scratch/replay.json")
[[ $(jq -er '.data.id' <<<"$replay") == "$id_photo_id" ]]
wait_file_ready "$app_port" "$token" "$id_photo_id" "$scratch/id-photo-file.json"
read -r kind variant <<<"$(mysql "${socket[@]}" id_photo_http -Nse "SELECT source_kind,source_variant FROM print_miniapp_demo_file WHERE id='$id_photo_id'")"
[[ $kind == ID_PHOTO && $variant == ONE_INCH ]]

quote_code=$(curl -sS -o "$scratch/id-photo-quote.json" -w '%{http_code}' \
  -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" -H "Host: 127.0.0.1:$app_port" \
  -H 'Idempotency-Key: id-photo-forbidden-quote' -H 'Content-Type: application/json' \
  --data "{\"fileId\":\"$id_photo_id\",\"paper\":\"A4\",\"color\":\"BLACK_WHITE\",\"copies\":1,\"side\":\"SIMPLEX\"}" \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/quotes")
[[ $quote_code == 409 && $(jq -r '.code' "$scratch/id-photo-quote.json") == 1928012 ]]
[[ $(mysql "${socket[@]}" id_photo_http -Nse "SELECT COUNT(*) FROM print_miniapp_demo_quote WHERE file_id='$id_photo_id'") == 0 ]]

if mysql "${socket[@]}" id_photo_http -e "
  INSERT INTO print_miniapp_demo_quote
    (id,tenant_id,store_id,principal_id,file_id,price_profile_id,idempotency_sha256,input_sha256,snapshot_json,created_at_ms,expires_at_ms)
  SELECT 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',tenant_id,store_id,principal_id,id,
    '11111111-1111-1111-1111-111111111111',REPEAT('a',64),REPEAT('b',64),'{}',1000,2000
  FROM print_miniapp_demo_file WHERE id='$id_photo_id';" >"$scratch/trigger.out" 2>"$scratch/trigger.err"; then
  echo 'ID_PHOTO_DB_TRIGGER_FAILED_OPEN' >&2
  exit 1
fi
grep -Fq 'Quote file type or owner is not allowed' "$scratch/trigger.err"

# Cross-owner lookup stays 404.
issue_session "$app_port" "$scratch/foreign-session.json"
foreign_token=$(jq -r '.data.accessToken' "$scratch/foreign-session.json")
foreign_code=$(curl -sS -o "$scratch/foreign.json" -w '%{http_code}' \
  -H "Authorization: Bearer $foreign_token" -H "Origin: http://127.0.0.1:$app_port" \
  -H "Host: 127.0.0.1:$app_port" \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/file?id=$id_photo_id")
[[ $foreign_code == 404 ]]

# Once an ID_PHOTO row exists, the old JAR still cannot quote it because the DB trigger fails closed.
stop_app
mkdir -m 0700 -- "$scratch/storage/id-photo-multipart/task-unsafe"
ln -s -- /etc/passwd "$scratch/storage/id-photo-multipart/task-unsafe/link"
if python3 "$repo/ops/runtime/cleanup-id-photo-private-tasks.py" "$scratch/storage" \
  >"$scratch/rollback-cleanup-unsafe.out" 2>"$scratch/rollback-cleanup-unsafe.err"; then
  cleanup_unsafe_rc=0
else
  cleanup_unsafe_rc=$?
fi
[[ $cleanup_unsafe_rc == 65 ]]
grep -Fxq ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_UNSAFE "$scratch/rollback-cleanup-unsafe.err"
[[ -L $scratch/storage/id-photo-multipart/task-unsafe/link ]]
[[ -f $scratch/storage/id-photo-multipart/task-rollback-orphan/input.bin ]]
[[ -f $scratch/storage/id-photo-scratch/task-rollback-orphan/output.pdf ]]
rm -- "$scratch/storage/id-photo-multipart/task-unsafe/link"
rmdir -- "$scratch/storage/id-photo-multipart/task-unsafe"
ordinary_storage_key=$(mysql "${socket[@]}" id_photo_http -Nse "SELECT storage_key FROM print_miniapp_demo_file WHERE id='$ordinary_id'")
ordinary_object="$scratch/storage/objects/$ordinary_storage_key.pdf"
ordinary_hash_before=$(sha256sum "$ordinary_object" | awk '{print $1}')
file_rows_before_cleanup=$(mysql "${socket[@]}" id_photo_http -Nse 'SELECT COUNT(*) FROM print_miniapp_demo_file')
quote_rows_before_cleanup=$(mysql "${socket[@]}" id_photo_http -Nse 'SELECT COUNT(*) FROM print_miniapp_demo_quote')
python3 "$repo/ops/runtime/cleanup-id-photo-private-tasks.py" "$scratch/storage" \
  | tee "$scratch/rollback-cleanup-unlocked.out"
grep -Fxq 'ID_PHOTO_PRIVATE_ROLLBACK_CLEANUP_PASS removed=2' "$scratch/rollback-cleanup-unlocked.out"
[[ ! -e $scratch/storage/id-photo-multipart/task-rollback-orphan ]]
[[ ! -e $scratch/storage/id-photo-scratch/task-rollback-orphan ]]
[[ $(sha256sum "$ordinary_object" | awk '{print $1}') == "$ordinary_hash_before" ]]
[[ $(mysql "${socket[@]}" id_photo_http -Nse 'SELECT COUNT(*) FROM print_miniapp_demo_file') == "$file_rows_before_cleanup" ]]
[[ $(mysql "${socket[@]}" id_photo_http -Nse 'SELECT COUNT(*) FROM print_miniapp_demo_quote') == "$quote_rows_before_cleanup" ]]
start_app "$old_jar" "$app_port" 10737418240 disabled-on-old-jar "$scratch/logs/stdout-$app_port.log"
wait_up "$app_port"
id_photo_quotes_before=$(mysql "${socket[@]}" id_photo_http -Nse "SELECT COUNT(*) FROM print_miniapp_demo_quote WHERE file_id='$id_photo_id'")
old_id_photo_quote_code=$(curl -sS -o "$scratch/old-id-photo-quote.json" -w '%{http_code}' \
  -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" -H "Host: 127.0.0.1:$app_port" \
  -H 'Idempotency-Key: old-jar-id-photo-forbidden' -H 'Content-Type: application/json' \
  --data "{\"fileId\":\"$id_photo_id\",\"paper\":\"A4\",\"color\":\"BLACK_WHITE\",\"copies\":1,\"side\":\"SIMPLEX\"}" \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/quotes")
[[ $old_id_photo_quote_code == 503 ]]
[[ $(mysql "${socket[@]}" id_photo_http -Nse "SELECT COUNT(*) FROM print_miniapp_demo_quote WHERE file_id='$id_photo_id'") == "$id_photo_quotes_before" ]]
stop_app
start_app "$candidate" "$app_port" 10737418240 id-photo-6in-v2 "$scratch/logs/stdout-$app_port.log"
wait_up "$app_port"

# Hold one valid HTTP request after admission; the second request must be rejected 429 before parsing.
cat >"$scratch/hold_request.py" <<'PY'
import os, socket, sys, time
port, token, image_path, marker, release, output, key = sys.argv[1:]
port = int(port)
boundary = '----codex-id-photo-boundary'
image = open(image_path, 'rb').read()
prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="specification"\r\n\r\nONE_INCH\r\n'
          f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="photo.png"\r\n'
          f'Content-Type: image/png\r\n\r\n').encode()
suffix = f'\r\n--{boundary}--\r\n'.encode()
body = prefix + image + suffix
headers = (f'POST /app-api/print/miniapp/demo/id-photo-files HTTP/1.1\r\n'
           f'Host: 127.0.0.1:{port}\r\nOrigin: http://127.0.0.1:{port}\r\n'
           f'Authorization: Bearer {token}\r\nIdempotency-Key: {key}\r\n'
           f'Content-Type: multipart/form-data; boundary={boundary}\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n').encode()
s = socket.create_connection(('127.0.0.1', port), timeout=5)
cut = len(prefix) + 64
s.sendall(headers + body[:cut])
open(marker, 'w').close()
for _ in range(2400):
    if os.path.exists(release): break
    time.sleep(.05)
else:
    raise SystemExit('release timeout')
s.sendall(body[cut:])
data = bytearray()
while True:
    chunk = s.recv(65536)
    if not chunk: break
    data.extend(chunk)
open(output, 'wb').write(data)
PY

cat >"$scratch/parse_raw_response.py" <<'PY'
import json, sys
raw = open(sys.argv[1], 'rb').read()
head, body = raw.split(b'\r\n\r\n', 1)
status = head.split(b'\r\n', 1)[0]
if b' 200 ' not in status:
    raise SystemExit(f'unexpected status: {status!r}')
if b'transfer-encoding: chunked' in head.lower():
    decoded = bytearray()
    while True:
        line, body = body.split(b'\r\n', 1)
        size = int(line.split(b';', 1)[0], 16)
        if size == 0: break
        decoded += body[:size]
        body = body[size + 2:]
    body = bytes(decoded)
data = json.loads(body)
file_id = data.get('data', {}).get('id')
if data.get('code') != 0 or not isinstance(file_id, str) or len(file_id) != 36:
    raise SystemExit(f'unexpected body: {data!r}')
print(file_id)
PY

python3 "$scratch/hold_request.py" "$app_port" "$token" "$scratch/id-photo.png" \
  "$scratch/held.marker" "$scratch/held.release" "$scratch/held.response" held-request &
held_pid=$!
child_pids+=("$held_pid")
for _ in $(seq 1 50); do [[ -f $scratch/held.marker ]] && break; sleep 0.1; done
[[ -f $scratch/held.marker ]]
for _ in $(seq 1 50); do
  held_task=$(find "$scratch/storage/id-photo-multipart" -mindepth 1 -maxdepth 1 -type d -name 'task-*' -print -quit)
  [[ -n $held_task ]] && break
  sleep 0.1
done
[[ -n $held_task && -d $held_task ]]
busy_code=$(curl -sS -o "$scratch/busy.json" -w '%{http_code}' \
  -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" -H "Host: 127.0.0.1:$app_port" \
  -H 'Idempotency-Key: busy-request' -F "file=@$scratch/id-photo.png;type=image/png" -F 'specification=TWO_INCH' \
  "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/id-photo-files")
[[ $busy_code == 429 ]]

# While the first instance owns a real task, a second JVM sharing the storage root must fail without touching it.
first_pid=$app_pid
first_log=$app_log
start_app "$candidate" "$((app_port + 1))" 10737418240 id-photo-6in-v2 "$scratch/logs/stdout-$((app_port + 1)).log" "$scratch/storage"
second_pid=$app_pid
second_log=$app_log
for _ in $(seq 1 100); do process_alive "$second_pid" || break; sleep 0.5; done
if process_alive "$second_pid"; then echo 'SECOND_STORAGE_OWNER_STARTED' >&2; exit 1; fi
wait "$second_pid" 2>/dev/null || true
grep -Fq 'Only one miniapp demo file worker may use this storage root' "$second_log"
[[ -d $held_task ]]
process_alive "$first_pid"
app_pid=$first_pid
app_log=$first_log

touch "$scratch/held.release"
wait "$held_pid"
held_file_id=$(python3 "$scratch/parse_raw_response.py" "$scratch/held.response")
[[ $(mysql "${socket[@]}" id_photo_http -Nse "SELECT CONCAT(source_kind,':',source_variant) FROM print_miniapp_demo_file WHERE id='$held_file_id'") == ID_PHOTO:ONE_INCH ]]
[[ ! -e $held_task ]]

# Permit release completes as the first response unwinds. Allow only a short 429
# hand-off window, then require the next request to be admitted successfully.
released_code=''
for _ in $(seq 1 20); do
  released_code=$(curl -sS -o "$scratch/released.json" -w '%{http_code}' \
    -H "Authorization: Bearer $token" -H "Origin: http://127.0.0.1:$app_port" \
    -H "Host: 127.0.0.1:$app_port" -H 'Idempotency-Key: after-busy' \
    -F "file=@$scratch/id-photo.png;type=image/png" -F 'specification=TWO_INCH' \
    "http://127.0.0.1:$app_port/app-api/print/miniapp/demo/id-photo-files")
  [[ $released_code == 200 ]] && break
  [[ $released_code == 429 ]]
  sleep 0.25
done
[[ $released_code == 200 ]]
released=$(<"$scratch/released.json")
jq -e '.code == 0 and (.data.id | length == 36)' <<<"$released" >/dev/null
released_file_id=$(jq -er '.data.id' <<<"$released")
wait_file_ready "$app_port" "$token" "$held_file_id" "$scratch/held-file-ready.json"
wait_file_ready "$app_port" "$token" "$released_file_id" "$scratch/released-file-ready.json"

# In-flight shutdown enters drain and exits after the held request is released.
rm -f "$scratch/drain.marker" "$scratch/drain.release" "$scratch/drain.response"
python3 "$scratch/hold_request.py" "$app_port" "$token" "$scratch/id-photo.png" \
  "$scratch/drain.marker" "$scratch/drain.release" "$scratch/drain.response" drain-request &
drain_client=$!
child_pids+=("$drain_client")
for _ in $(seq 1 50); do [[ -f $scratch/drain.marker ]] && break; sleep 0.1; done
[[ -f $scratch/drain.marker ]]
for _ in $(seq 1 50); do
  drain_task=$(find "$scratch/storage/id-photo-multipart" -mindepth 1 -maxdepth 1 -type d -name 'task-*' -print -quit)
  [[ -n $drain_task ]] && break
  sleep 0.1
done
[[ -n $drain_task && -d $drain_task ]]
kill "$app_pid"
sleep 1
process_alive "$app_pid"
touch "$scratch/drain.release"
wait "$drain_client"
drain_file_id=$(python3 "$scratch/parse_raw_response.py" "$scratch/drain.response")
# Spring also closes framework executors and pools after the admitted request
# has drained. Keep the business drain assertion strict, but allow the JVM's
# normal shutdown sequence up to 60 seconds on the 2-core server.
for _ in $(seq 1 240); do process_alive "$app_pid" || break; sleep 0.25; done
process_alive "$app_pid" && { echo 'DRAIN_TIMEOUT' >&2; exit 1; }
wait "$app_pid" 2>/dev/null || true
app_pid=''
app_log=''
[[ $(mysql "${socket[@]}" id_photo_http -Nse "SELECT CONCAT(source_kind,':',source_variant) FROM print_miniapp_demo_file WHERE id='$drain_file_id'") == ID_PHOTO:ONE_INCH ]]
[[ ! -e $drain_task ]]
[[ $(find "$scratch/storage/id-photo-multipart" "$scratch/storage/id-photo-scratch" -mindepth 1 -maxdepth 1 -type d -name 'task-*' | wc -l) == 0 ]]

# Low disk is rejected before multipart parsing and leaves no task.
start_app "$candidate" "$app_port" 50000000000000 id-photo-6in-v2 "$scratch/logs/stdout-$app_port.log"
wait_up "$app_port"
low_before=$(find "$scratch/storage/id-photo-multipart" -mindepth 1 -maxdepth 1 -type d -name 'task-*' | wc -l)
python3 "$scratch/prebody_request.py" "$app_port" "$token" low-disk 507
low_after=$(find "$scratch/storage/id-photo-multipart" -mindepth 1 -maxdepth 1 -type d -name 'task-*' | wc -l)
[[ $low_before == "$low_after" ]]
stop_app

echo 'ID_PHOTO_HTTP_ISOLATION_PASS partial_migration old_jar_rollback rollback_private_cleanup invalid_session low_disk custom_multipart idempotency persistence quote_app_guard quote_db_trigger cross_owner concurrency permit_release double_lock drain cleanup'
