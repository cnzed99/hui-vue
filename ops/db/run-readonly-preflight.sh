#!/usr/bin/env bash
set -euo pipefail

db_name="$(sudo mysql -Nse "SELECT table_schema FROM information_schema.tables WHERE table_name='print_miniapp_demo_order' ORDER BY table_schema LIMIT 1")"
test -n "$db_name"
echo preflight_database_found
sudo mysql --batch --raw "$db_name" \
  < /home/ubuntu/print-system/runtime/worktrees/m8-cancel-refund-engineering-20261102/ops/db/print-miniapp-demo-cancellation-refund-preflight.sql
