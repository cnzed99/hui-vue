#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/print-system
if [ "${1:-}" != "--within-disk-batch" ]; then
    exec python3 ops/run-build-batch.py --label frontend -- bash "$0" --within-disk-batch
fi
export PATH="$PWD/runtime/node/current/bin:$PWD/runtime/npm/bin:$PATH"
was_active=false
if systemctl is-active --quiet print-backend; then was_active=true; fi
restore_backend() { if "$was_active"; then sudo -n systemctl start print-backend; fi; }
trap restore_backend EXIT
if "$was_active"; then sudo -n systemctl stop print-backend; fi
(cd src/frontend && node --max-old-space-size=2048 ./node_modules/vite/bin/vite.js build --mode cloud) > evidence/frontend-build.log 2>&1
printf 'FRONTEND_BUILD_OK\n'
