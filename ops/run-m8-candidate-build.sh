#!/usr/bin/env bash
set -euo pipefail

root=/home/ubuntu/print-system
backend="$root/runtime/worktrees/m8-cancel-refund-backend-20261102"
admin="$root/runtime/worktrees/m8-cancel-refund-admin-20261102"
miniapp="$root/runtime/worktrees/m8-cancel-refund-miniapp-20261102"
evidence="$root/evidence/build/m8-cancel-refund-candidate"
mkdir -p -- "$evidence"

test "$(git -C "$backend" rev-parse HEAD)" = 57221d2d853b276cdd8fbbfc736a0281bde3f8dc
test "$(git -C "$admin" rev-parse HEAD)" = 6575759a145cce02f0aa8dc46952a6add2bfa192
test "$(git -C "$miniapp" rev-parse HEAD)" = 6de36ca866758b31a1f08c4dc7f496b6e8b37790
test -e "$admin/node_modules"
test -e "$miniapp/node_modules"

export PATH="$root/runtime/node/current/bin:$root/runtime/npm/bin:$PATH"
export MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m'

(cd "$backend" && mvn -B -ntp -pl yudao-server -am -Dmaven.test.skip=true package) \
  > "$evidence/backend-build.log" 2>&1
echo BACKEND_BUILD_OK

(cd "$admin" && node --max-old-space-size=2048 ./node_modules/vite/bin/vite.js build --mode cloud) \
  > "$evidence/admin-build.log" 2>&1
echo ADMIN_BUILD_OK

(cd "$miniapp" && node --max-old-space-size=1536 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit) \
  > "$evidence/miniapp-typecheck.log" 2>&1
echo MINIAPP_TYPECHECK_OK

(cd "$miniapp" && NODE_OPTIONS='--max-old-space-size=1536' pnpm run build:h5) \
  > "$evidence/miniapp-build.log" 2>&1
echo MINIAPP_BUILD_OK

sha256sum \
  "$backend/yudao-server/target/yudao-server.jar" \
  "$admin/dist-prod/index.html" \
  "$miniapp/dist/build/h5/index.html" \
  > "$evidence/artifacts.sha256"
echo CANDIDATE_ARTIFACTS_HASHED
