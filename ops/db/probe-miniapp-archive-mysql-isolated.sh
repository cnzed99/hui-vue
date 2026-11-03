#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd -- "$(git rev-parse --show-toplevel)"
project=/home/ubuntu/print-system
scratch=$(mktemp -d /tmp/print-zip-db-XXXXXXXX)
[[ $(realpath -e -- "$scratch") == "$scratch" && ! -L $scratch && $scratch == /tmp/print-zip-db-???????? ]]
port=${1:-45883}
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
mkdir -- "$scratch/data" "$scratch/classes"
mysqld --no-defaults --initialize-insecure --datadir="$scratch/data" --log-error="$scratch/init.log"
mysqld --no-defaults --datadir="$scratch/data" --socket="$scratch/mysql.sock" \
  --pid-file="$scratch/mysql.pid" --bind-address=127.0.0.1 --port="$port" --mysqlx=0 \
  --innodb-buffer-pool-size=32M --performance-schema=OFF --skip-log-bin --log-error="$scratch/mysql.log" &
pid=$!
socket=(--no-defaults --protocol=socket --socket="$scratch/mysql.sock" -uroot)
for i in $(seq 1 30); do
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
INSERT INTO system_tenant VALUES (1,0,b'0',CURRENT_TIMESTAMP + INTERVAL 1 HOUR);
INSERT INTO print_store VALUES (1,1,0,b'0');
SQL
mysql "${socket[@]}" zip_candidate < "$project/ops/db/print-miniapp-demo-session-v1.sql"
mysql "${socket[@]}" zip_candidate < "$project/ops/db/print-miniapp-demo-files-v1.sql"
mysql "${socket[@]}" zip_candidate < ops/db/print-miniapp-demo-archive-provenance.sql
[[ $(mysql "${socket[@]}" -Nse "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='zip_candidate' AND CONSTRAINT_NAME='chk_print_demo_file_archive_source'") == 1 ]]
test -d yudao-server/target/classes
deps=$(find /home/ubuntu/.m2/repository -type f -name '*.jar' | paste -sd :)
targets=$(find . -type d -path '*/target/classes' | paste -sd :)
test_source=yudao-server/src/test/java/cn/iocoder/yudao/server/print/miniappdemo/file/MiniappArchiveMysqlChecks.java
javac -proc:none -d "$scratch/classes" -cp "$targets:$deps" "$test_source"
java -Xmx128m -cp "$scratch/classes:$targets:$deps" cn.iocoder.yudao.server.print.miniappdemo.file.MiniappArchiveMysqlChecks "$port" "$scratch"
