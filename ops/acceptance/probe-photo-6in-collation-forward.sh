#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd -- "$(git rev-parse --show-toplevel)"
scratch=$(mktemp -d /tmp/print-photo-collation-XXXXXXXX)
[[ $(realpath -e "$scratch") == "$scratch" && ! -L $scratch && $scratch == /tmp/print-photo-collation-???????? ]]
pid=''
cleanup(){ if [[ -n $pid ]] && kill -0 "$pid" 2>/dev/null;then kill "$pid";wait "$pid" || true;fi;rm -rf --one-file-system -- "$scratch"; }
trap cleanup EXIT
mkdir "$scratch/data"
mysqld --no-defaults --initialize-insecure --datadir="$scratch/data" --log-error="$scratch/init.log"
mysqld --no-defaults --datadir="$scratch/data" --socket="$scratch/mysql.sock" --pid-file="$scratch/mysql.pid" --skip-networking --mysqlx=0 --innodb-buffer-pool-size=32M --performance-schema=OFF --skip-log-bin --log-error="$scratch/mysql.log" &
pid=$!
for _ in $(seq 1 30);do kill -0 "$pid";if mysqladmin --no-defaults --protocol=socket --socket="$scratch/mysql.sock" -uroot ping --silent 2>/dev/null;then break;fi;sleep 1;done
output=target/photo-collation-forward-checks
mkdir -p "$output"
python3 ops/acceptance/probe-photo-6in-collation-forward.py --socket "$scratch/mysql.sock" --datadir "$scratch/data" --output "$output"
