#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/print-system
if [ "${1:-}" != "--within-disk-batch" ]; then
    exec python3 ops/run-build-batch.py --label backend -- bash "$0" --within-disk-batch
fi
export MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m'
(cd src/backend && mvn -B -ntp -pl yudao-server -am -DskipTests package) > evidence/backend-rebuild.log 2>&1
printf 'BACKEND_REBUILD_OK\n'
