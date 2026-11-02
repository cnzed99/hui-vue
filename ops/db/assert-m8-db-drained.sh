#!/usr/bin/env bash
set -euo pipefail
[[ $# = 1 && $1 =~ ^[a-zA-Z0-9_]+$ ]] || exit 64
db_name=$1
connections="$(sudo mysql -Nse "SELECT COUNT(*) FROM information_schema.processlist WHERE DB='$db_name' AND ID<>CONNECTION_ID()")"
transactions="$(sudo mysql -Nse 'SELECT COUNT(*) FROM information_schema.innodb_trx')"
[[ $connections = 0 && $transactions = 0 ]] || { echo "M8_NOT_DRAINED connections=$connections transactions=$transactions" >&2; exit 76; }
