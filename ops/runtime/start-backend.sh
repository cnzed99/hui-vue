#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/print-system
set -a
source runtime/secrets/runtime.env
set +a
jar_path="$(readlink -f runtime/releases/current/backend.jar)"
[ -f "$jar_path" ] || { echo "Missing immutable release jar"; exit 2; }
exec java -Xms128m -Xmx768m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -jar "$jar_path" --spring.profiles.active=cloud --spring.config.additional-location=file:/home/ubuntu/print-system/ops/runtime/application-cloud.yaml
