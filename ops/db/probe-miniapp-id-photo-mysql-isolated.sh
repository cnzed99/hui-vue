#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd -- "$(git rev-parse --show-toplevel)"
project=/home/ubuntu/print-system
scratch=$(mktemp -d /tmp/print-id-photo-db-XXXXXXXX)
[[ $(realpath -e -- "$scratch") == "$scratch" && ! -L $scratch && $scratch == /tmp/print-id-photo-db-???????? ]]
port=${1:-45893}
[[ $port =~ ^[45][0-9]{4}$ ]]
pid=''
cleanup() {
  if [[ -n $pid ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    wait "$pid" || true
  fi
  rm -rf --one-file-system -- "$scratch"
}
trap cleanup EXIT
mkdir -- "$scratch/data" "$scratch/classes" "$scratch/print-zip-db-id-photo"
mysqld --no-defaults --initialize-insecure --datadir="$scratch/data" --log-error="$scratch/init.log"
mysqld --no-defaults --datadir="$scratch/data" --socket="$scratch/mysql.sock" \
  --pid-file="$scratch/mysql.pid" --bind-address=127.0.0.1 --port="$port" --mysqlx=0 \
  --innodb-buffer-pool-size=32M --performance-schema=OFF --skip-log-bin --log-error="$scratch/mysql.log" &
pid=$!
socket=(--no-defaults --protocol=socket --socket="$scratch/mysql.sock" -uroot)
for _ in $(seq 1 30); do
  kill -0 "$pid"
  if mysqladmin "${socket[@]}" ping --silent; then break; fi
  sleep 1
done
[[ $(mysql "${socket[@]}" -Nse 'SELECT @@datadir') == "$scratch/data/" ]]
mysql "${socket[@]}" <<'SQL'
CREATE DATABASE zip_candidate;
USE zip_candidate;
CREATE TABLE system_tenant (id BIGINT PRIMARY KEY, status INT NOT NULL, deleted BIT NOT NULL, expire_time DATETIME NOT NULL);
CREATE TABLE print_store (tenant_id BIGINT NOT NULL, id BIGINT NOT NULL, status INT NOT NULL, deleted BIT NOT NULL, PRIMARY KEY(tenant_id,id));
CREATE TABLE print_price_profile (
  tenant_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY(tenant_id,store_id,id)
);
INSERT INTO system_tenant VALUES (1,0,b'0',CURRENT_TIMESTAMP + INTERVAL 1 HOUR);
INSERT INTO print_store VALUES (1,1,0,b'0');
INSERT INTO print_price_profile VALUES (1,1,'11111111-1111-1111-1111-111111111111');
SQL
mysql "${socket[@]}" zip_candidate < "$project/ops/db/print-miniapp-demo-session-v1.sql"
mysql "${socket[@]}" zip_candidate < "$project/ops/db/print-miniapp-demo-files-v1.sql"
mysql "${socket[@]}" zip_candidate < ops/db/print-miniapp-demo-archive-provenance.sql
mysql "${socket[@]}" zip_candidate < "$project/runtime/candidates/m3-quote/print-miniapp-demo-quotes-v1.sql"
mysql "${socket[@]}" zip_candidate < ops/db/print-miniapp-demo-id-photo-source.sql

[[ $(mysql "${socket[@]}" -Nse "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='zip_candidate' AND TABLE_NAME='print_miniapp_demo_file' AND COLUMN_NAME IN ('source_kind','source_variant')") == 2 ]]
[[ $(mysql "${socket[@]}" -Nse "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='zip_candidate' AND CONSTRAINT_NAME='chk_print_demo_file_source'") == 1 ]]
[[ $(mysql "${socket[@]}" -Nse "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='zip_candidate' AND TRIGGER_NAME='trg_print_demo_quote_document_only'") == 1 ]]

test -d yudao-server/target/classes
deps=$(find /home/ubuntu/.m2/repository -type f -name '*.jar' | paste -sd :)
targets=$(find . -type d -path '*/target/classes' | paste -sd :)
archive_source=yudao-server/src/test/java/cn/iocoder/yudao/server/print/miniappdemo/file/MiniappArchiveMysqlChecks.java
javac -proc:none -d "$scratch/classes" -cp "$targets:$deps" "$archive_source"
java -Xmx128m -cp "$scratch/classes:$targets:$deps" \
  cn.iocoder.yudao.server.print.miniappdemo.file.MiniappArchiveMysqlChecks \
  "$port" "$scratch/print-zip-db-id-photo"

mysql "${socket[@]}" zip_candidate <<'SQL'
INSERT INTO print_miniapp_demo_principal
  (id,tenant_id,store_id,actor_type,status,created_at_ms,expires_at_ms)
VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',1,1,'MINIAPP_DEMO','ACTIVE',1000,9999999999999);
INSERT INTO print_miniapp_demo_file
  (id,tenant_id,store_id,principal_id,idempotency_sha256,content_sha256,display_name,size_bytes,page_count,status,
   storage_key,created_at_ms,updated_at_ms,expires_at_ms,source_kind,source_variant,archive_sha256,archive_entry_ordinal)
VALUES
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',1,1,'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',REPEAT('b',64),REPEAT('c',64),
   'document.pdf',100,1,'READY','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',1000,1000,9999999999999,'DOCUMENT',NULL,NULL,NULL),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc',1,1,'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',REPEAT('d',64),REPEAT('e',64),
   'id-photo.pdf',100,1,'READY','cccccccc-cccc-cccc-cccc-cccccccccccc',1000,1000,9999999999999,'ID_PHOTO','ONE_INCH',NULL,NULL);
INSERT INTO print_miniapp_demo_quote
  (id,tenant_id,store_id,principal_id,file_id,price_profile_id,idempotency_sha256,input_sha256,snapshot_json,created_at_ms,expires_at_ms)
VALUES
  ('dddddddd-dddd-dddd-dddd-dddddddddddd',1,1,'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','11111111-1111-1111-1111-111111111111',REPEAT('f',64),REPEAT('1',64),'{}',1000,2000);
SQL

if mysql "${socket[@]}" zip_candidate -e "
  INSERT INTO print_miniapp_demo_quote
    (id,tenant_id,store_id,principal_id,file_id,price_profile_id,idempotency_sha256,input_sha256,snapshot_json,created_at_ms,expires_at_ms)
  VALUES
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',1,1,'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
     'cccccccc-cccc-cccc-cccc-cccccccccccc','11111111-1111-1111-1111-111111111111',REPEAT('2',64),REPEAT('3',64),'{}',1000,2000);" \
     >"$scratch/forbidden.out" 2>"$scratch/forbidden.err"; then
  echo 'ID_PHOTO_QUOTE_TRIGGER_FAILED_OPEN' >&2
  exit 1
fi
grep -Fq 'Quote file type or owner is not allowed' "$scratch/forbidden.err"

if mysql "${socket[@]}" zip_candidate -e "
  UPDATE print_miniapp_demo_file SET source_kind='ID_PHOTO',source_variant=NULL
   WHERE id='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';" >"$scratch/check.out" 2>"$scratch/check.err"; then
  echo 'ID_PHOTO_SOURCE_CHECK_FAILED_OPEN' >&2
  exit 1
fi
grep -Eq 'chk_print_demo_file_source|Check constraint' "$scratch/check.err"
echo 'ID_PHOTO_MYSQL_ISOLATION_PASS source_pair document_quote id_photo_quote_block archive_regression'
