#!/usr/bin/env bash
# DRAFT ONLY. This script is not a deployment. It intentionally keeps quote writers stopped.
set -Eeuo pipefail
umask 077
[[ $EUID == 0 ]] || { echo 'Run in a separately approved root maintenance window' >&2;exit 2; }
[[ $# == 4 ]] || { echo 'usage: script reviewed-git-commit reviewed-sql-sha compatible-jar compatible-jar-sha' >&2;exit 2; }
root=$(git rev-parse --show-toplevel);cd -- "$root"
[[ $root == /home/ubuntu/print-system/runtime/worktrees/photo-6in-backend-20261104 ]]
[[ $1 =~ ^[0-9a-f]{40}$ && $2 =~ ^[0-9a-f]{64}$ && $4 =~ ^[0-9a-f]{64}$ ]]
[[ $(git rev-parse HEAD) == "$1" && -z $(git status --porcelain) ]]
sql=$root/ops/db/print-miniapp-demo-photo-6in.sql
[[ $(sha256sum "$sql" | cut -d' ' -f1) == "$2" ]]
jar=$(realpath -e -- "$3")
[[ $jar == /home/ubuntu/print-system/runtime/* && -f $jar && ! -L $jar && $(sha256sum "$jar" | cut -d' ' -f1) == "$4" ]]
unzip -Z1 "$jar" | grep -Fx 'BOOT-INF/classes/cn/iocoder/yudao/server/print/miniappdemo/photo/MiniappDemoPhotoRendererMain.class'
# Final-diff/permission/migration review and artifact hashes must be supplied by the operator, never inferred from design PASS.
[[ ${PHOTO_6IN_FINAL_REVIEW:-} == PASS && ${PHOTO_6IN_MIGRATION_REVIEW:-} == PASS ]]
[[ $(df -PB1 /home/ubuntu/print-system | awk 'NR==2 {print $4}') -ge 10737418240 ]]
backup=$(mktemp -d /home/ubuntu/print-system/runtime/backups/photo-6in-pre-migration-XXXXXXXX)
chmod 700 "$backup"
mysqldump --single-transaction --routines --triggers --events print_demo | gzip > "$backup/database.sql.gz"
gzip -t "$backup/database.sql.gz"
sha256sum "$backup/database.sql.gz" > "$backup/SHA256SUMS"
sha256sum -c "$backup/SHA256SUMS" >/dev/null
writing_stopped=false
hold_stopped(){
  local status=$?
  if $writing_stopped;then
    systemctl stop nginx.service print-backend.service || true
    echo 'WRITERS_HELD_STOPPED: do not resume until reviewed compatible JAR, both app flags=false, binding.quote_enabled=0, and trigger readback verified.' >&2
  fi
  exit "$status"
}
trap hold_stopped EXIT
writing_stopped=true
systemctl stop nginx.service print-backend.service
! systemctl is-active --quiet nginx.service
! systemctl is-active --quiet print-backend.service
[[ $(mysql -Nse "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='print_demo' AND ID<>CONNECTION_ID() AND COMMAND<>'Sleep'") == 0 ]]
# --force is deliberately prohibited. Any DDL/readback failure exits with both writers stopped.
mysql --show-warnings print_demo < "$sql" > "$backup/migration-readback.log"
[[ $(mysql print_demo -Nse 'SELECT COUNT(*) FROM print_photo_price_binding WHERE tenant_id=1 AND store_id=1 AND revision=1 AND quote_enabled=0') == 1 ]]
[[ $(mysql -Nse "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='print_demo' AND TRIGGER_NAME='trg_print_demo_quote_document_only' AND ACTION_STATEMENT LIKE '%FOR UPDATE%' AND ACTION_STATEMENT LIKE '%quote_enabled%'") == 1 ]]
[[ $(mysql print_demo -Nse "SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE rm.menu_id=2011 AND rm.deleted=0 AND (r.code<>'tenant_admin' OR rm.tenant_id<>1)") == 0 ]]
echo 'MIGRATION_APPLIED_GATE_0_WRITERS_STOPPED: separate approved compatibility deployment and health verification required.'
# No inverse migration, no JAR switch, no configuration write, no service restart is authorized by this script.
