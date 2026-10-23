#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/print-system
if [ "${1:-}" != "--within-disk-batch" ]; then
    exec python3 ops/run-build-batch.py --label baseline -- bash "$0" --within-disk-batch
fi
export PATH="$PWD/runtime/node/current/bin:$PWD/runtime/npm/bin:$PATH"
export MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m'
export NODE_OPTIONS='--max-old-space-size=2048'
free_k=$(df -Pk . | awk 'NR==2 {print $4}')
[ "$free_k" -ge 10485760 ] || { echo LOW_DISK_STOP; exit 2; }
(cd src/backend && mvn -B -ntp -pl yudao-server -am -DskipTests package) > evidence/backend-build.log 2>&1
printf 'BACKEND_BUILD_OK\n'
(cd src/frontend && pnpm install --frozen-lockfile --ignore-scripts --store-dir /home/ubuntu/print-system/runtime/pnpm-store) > evidence/frontend-install.log 2>&1
printf 'FRONTEND_INSTALL_OK\n'
(cd src/frontend && node --max-old-space-size=2048 ./node_modules/vite/bin/vite.js build --mode cloud) > evidence/frontend-build.log 2>&1
printf 'FRONTEND_BUILD_OK\n'
df -h .
du -sh src/backend src/frontend runtime
