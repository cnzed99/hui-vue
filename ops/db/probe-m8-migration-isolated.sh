#!/usr/bin/env bash
set -euo pipefail

root=/home/ubuntu/print-system/runtime
scratch="$(mktemp -d "$root/scratch/m8-sql-probe.XXXXXX")"
probe_db="print_m8_probe_$(date +%s)_$RANDOM"
source_db="$(sudo mysql -Nse "SELECT table_schema FROM information_schema.tables WHERE table_name='print_miniapp_demo_order' ORDER BY table_schema LIMIT 1")"
test -n "$source_db"

cleanup() {
  sudo mysql -e "DROP DATABASE IF EXISTS \`$probe_db\`" >/dev/null 2>&1 || true
  case "$scratch" in
    "$root"/scratch/m8-sql-probe.*) rm -rf -- "$scratch" ;;
    *) echo 'refusing unexpected scratch cleanup target' >&2 ;;
  esac
}
trap cleanup EXIT

sudo mysql -e "CREATE DATABASE \`$probe_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"
sudo mysqldump --no-data --routines --triggers --events --single-transaction "$source_db" > "$scratch/schema.sql"
sudo mysql "$probe_db" < "$scratch/schema.sql"

sudo mysql --batch --raw "$probe_db" \
  < /home/ubuntu/print-system/runtime/worktrees/m8-cancel-refund-engineering-20261102/ops/db/print-miniapp-demo-cancellation-refund-preflight.sql \
  > "$scratch/preflight-before.txt"
if ! awk 'NF==2 && $1!="check_name" && $2!="0" {bad=1} END {exit bad}' "$scratch/preflight-before.txt"; then
  echo 'preflight returned a non-zero violation count' >&2
  exit 1
fi

sudo mysql "$probe_db" \
  < /home/ubuntu/print-system/runtime/worktrees/m8-cancel-refund-engineering-20261102/ops/db/print-miniapp-demo-cancellation-refund-v1.sql

table_count="$(sudo mysql -N "$probe_db" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('print_miniapp_demo_cancellation','print_miniapp_demo_refund_receipt','print_miniapp_demo_refund_journal')")"
trigger_count="$(sudo mysql -N "$probe_db" -e "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('trg_pmdc_no_update','trg_pmdc_no_delete','trg_pmdrr_no_update','trg_pmdrr_no_delete','trg_pmdrj_no_update','trg_pmdrj_no_delete')")"
check_count="$(sudo mysql -N "$probe_db" -e "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK' AND constraint_name IN ('chk_pmdo_state','chk_pmdoe_type','chk_pmdpt_state','chk_pmdpt_lease','chk_pmdpt_cancel','chk_pmdc_outcome','chk_pmdrr_fixed','chk_pmdrj_entry')")"
test "$table_count" = 3
test "$trigger_count" = 6
test "$check_count" = 8
echo "migration_schema_pass tables=$table_count triggers=$trigger_count checks=$check_count"

sudo mysql "$probe_db" \
  < /home/ubuntu/print-system/runtime/worktrees/m8-cancel-refund-engineering-20261102/ops/db/print-miniapp-demo-cancellation-refund-rollback-no-writes.sql

remaining="$(sudo mysql -N "$probe_db" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('print_miniapp_demo_cancellation','print_miniapp_demo_refund_receipt','print_miniapp_demo_refund_journal')")"
cancel_check="$(sudo mysql -N "$probe_db" -e "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='print_miniapp_demo_print_task' AND constraint_name='chk_pmdpt_cancel'")"
test "$remaining" = 0
test "$cancel_check" = 0
echo "rollback_no_writes_pass tables=$remaining cancel_check=$cancel_check"
