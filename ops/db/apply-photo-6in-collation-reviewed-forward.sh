#!/usr/bin/env bash
# REVIEW CANDIDATE ONLY. No automatic restart, gate change, inverse migration or data write.
set -Eeuo pipefail
umask 077
[[ $EUID == 0 ]] || { echo 'Approved root maintenance window required' >&2;exit 2; }
[[ $# == 3 ]] || { echo 'usage: script reviewed-commit reviewed-repair-sql-sha expected-old-trigger-body-sha' >&2;exit 2; }
[[ $1 =~ ^[0-9a-f]{40}$ && $2 =~ ^[0-9a-f]{64}$ && $3 =~ ^[0-9a-f]{64}$ ]]
root=$(git rev-parse --show-toplevel);cd -- "$root"
[[ $root == /home/ubuntu/print-system/runtime/worktrees/photo-6in-backend-20261104 ]]
[[ $(git rev-parse HEAD) == "$1" && -z $(git status --porcelain) ]]
[[ ${PHOTO_COLLATION_SOURCE_REVIEW:-} == PASS && ${PHOTO_COLLATION_MIGRATION_REVIEW:-} == PASS && ${PHOTO_COLLATION_RELEASE_REVIEW:-} == PASS ]]
sql=$root/ops/db/print-miniapp-demo-photo-6in-trigger-collation-forward.sql
[[ $(sha256sum "$sql" | cut -d' ' -f1) == "$2" ]]
[[ $(df -PB1 /home/ubuntu/print-system | awk 'NR==2 {print $4}') -ge 10737418240 ]]
held=false
hold_stopped(){
  local result=$?
  if $held;then systemctl stop nginx.service print-backend.service || true;echo 'WRITERS_HELD_STOPPED: reviewed forward repair/readback and controlled compatible recovery required' >&2;fi
  exit "$result"
}
trap hold_stopped EXIT
held=true
systemctl stop nginx.service print-backend.service
! systemctl is-active --quiet nginx.service
! systemctl is-active --quiet print-backend.service
[[ $(systemctl show print-backend.service -p MainPID --value) == 0 ]]
[[ $(systemctl show print-backend.service -p Result --value) != timeout ]]
cgroup=$(systemctl show print-backend.service -p ControlGroup --value)
if [[ -n $cgroup && -e /sys/fs/cgroup$cgroup/cgroup.procs ]];then [[ -z $(cat "/sys/fs/cgroup$cgroup/cgroup.procs") ]];fi
mysql_cmd=(mysql --no-defaults --protocol=socket -uroot --batch --raw --skip-column-names print_demo)
[[ $("${mysql_cmd[@]}" -e "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB=DATABASE() AND ID<>CONNECTION_ID() AND COMMAND<>'Sleep'") == 0 ]]
[[ $("${mysql_cmd[@]}" -e "SELECT COUNT(*) FROM information_schema.INNODB_TRX t JOIN information_schema.PROCESSLIST p ON p.ID=t.trx_mysql_thread_id WHERE p.DB=DATABASE() AND p.ID<>CONNECTION_ID()") == 0 ]]
[[ $("${mysql_cmd[@]}" -e 'SELECT COUNT(*) FROM print_photo_price_binding WHERE tenant_id=1 AND store_id=1 AND quote_enabled=0') == 1 ]]
[[ $("${mysql_cmd[@]}" -e "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN('trg_print_demo_quote_document_only','trg_print_price_profile_immutable')") == 2 ]]
backup=$(mktemp -d /home/ubuntu/print-system/runtime/backups/photo-6in-collation-forward-XXXXXXXX)
chmod 700 "$backup"
"${mysql_cmd[@]}" -e 'SHOW CREATE TRIGGER trg_print_demo_quote_document_only' > "$backup/trigger-before.readback"
"${mysql_cmd[@]}" -e "SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_print_demo_quote_document_only'" > "$backup/trigger-body-before.txt"
[[ $(sha256sum "$backup/trigger-body-before.txt" | cut -d' ' -f1) == "$3" ]]
mysqldump --no-defaults --protocol=socket -uroot --single-transaction --routines --triggers --events print_demo | gzip > "$backup/database.sql.gz"
gzip -t "$backup/database.sql.gz"
sha256sum "$backup/database.sql.gz" "$backup/trigger-before.readback" "$backup/trigger-body-before.txt" > "$backup/SHA256SUMS"
sha256sum -c "$backup/SHA256SUMS" >/dev/null
# No --force: after DROP, any CREATE/readback failure leaves every writer stopped.
"${mysql_cmd[@]}" < "$sql" > "$backup/repair.readback"
[[ $("${mysql_cmd[@]}" -e "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_print_demo_quote_document_only' AND CHARACTER_SET_CLIENT='utf8mb4' AND COLLATION_CONNECTION='utf8mb4_bin' AND ACTION_STATEMENT LIKE '%ascii_bin%' AND ACTION_STATEMENT LIKE '%utf8mb4_bin%' AND ACTION_STATEMENT LIKE '%FOR UPDATE%'") == 1 ]]
"${mysql_cmd[@]}" -e 'SHOW CREATE TRIGGER trg_print_demo_quote_document_only' > "$backup/trigger-after.readback"
"${mysql_cmd[@]}" -e "SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_print_demo_quote_document_only'" > "$backup/trigger-body-after.txt"
python3 - "$sql" "$backup/trigger-body-after.txt" <<'PYVERIFY'
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
expected=source[source.index('\nBEGIN\n')+1:source.index('\nEND//')+len('\nEND')].strip()
actual=Path(sys.argv[2]).read_text().strip()
if expected!=actual:raise SystemExit('Trigger readback does not exactly match reviewed forward SQL body')
if actual.count('FOR UPDATE')!=3:raise SystemExit('Current locking-read count mismatch')
PYVERIFY
[[ $("${mysql_cmd[@]}" -e 'SELECT COUNT(*) FROM print_photo_price_binding WHERE tenant_id=1 AND store_id=1 AND quote_enabled=0') == 1 ]]
echo 'FORWARD_REPAIR_READBACK_COMPLETE_WRITERS_STOPPED: no automatic recovery or Stage D authorization'
