#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/print-system
export MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m'
(cd src/backend && mvn -B -ntp -pl yudao-server -am -DskipTests package) > evidence/backend-rebuild.log 2>&1
printf 'BACKEND_REBUILD_OK\n'
