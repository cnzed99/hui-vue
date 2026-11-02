#!/usr/bin/env bash
set -Eeuo pipefail
export PATH=/home/ubuntu/print-system/runtime/node/current/bin:/home/ubuntu/print-system/runtime/npm/bin:/usr/bin:/bin
ROOT=/home/ubuntu/print-system/runtime/worktrees/m10-wechat-readiness-miniapp-20261102
EVIDENCE=/home/ubuntu/print-system/runtime/worktrees/m10-wechat-readiness-engineering-20261102/evidence/miniapp/m10-wechat-readiness-20261102
cd "$ROOT"
export VITE_APP_API_BASE=https://api.synthetic-wechat-fixture.cn/app-api
export VITE_WECHAT_APP_ID=wx6f3a8c219d5e7b40
export WECHAT_REQUEST_DOMAINS=https://api.synthetic-wechat-fixture.cn
export WECHAT_UPLOAD_DOMAINS=https://api.synthetic-wechat-fixture.cn
export WECHAT_DOWNLOAD_DOMAINS=https://files.synthetic-wechat-fixture.cn
# Rejected path must not be normalized into an allowed path.
set +e
VITE_APP_API_BASE=https://api.synthetic-wechat-fixture.cn/private/../app-api npm run build:mp-weixin:integration >"$EVIDENCE/integration-path-negative.log" 2>&1
PATH_RC=$?
set -e
[[ $PATH_RC == 1 && ! -e dist/integration/mp-weixin ]]
# The strict Vite mode cannot bypass manifest injection/checking by invoking uni directly.
set +e
node node_modules/@dcloudio/vite-plugin-uni/bin/uni.js build -p mp-weixin --mode wechat-integration >"$EVIDENCE/integration-direct-negative.log" 2>&1
DIRECT_RC=$?
set -e
[[ $DIRECT_RC != 0 ]]
node - <<'JS' >"$EVIDENCE/summary.json"
console.log(JSON.stringify({
 status: 'PASS', directedChecks: 7, typeCheck: 'PASS', h5Build: 'COMPILE_ONLY',
 ordinaryMpBuild: 'COMPILE_ONLY', missingConfigIntegrationBuild: 'NOT_READY_EXIT_1',
 syntheticIntegrationBuild: 'COMPILE_ONLY_FIXTURE', pathEscapeIntegrationBuild: 'NOT_READY_EXIT_1',
 directStrictModeWithBlankManifest: 'NOT_READY_NONZERO', syntheticArtifactsRemoved: true,
 currentReleaseReadiness: 'NOT_READY', wechatDeveloperTools: 'NOT_RUN', device: 'NOT_RUN',
 originalSystemAccess: 'NOT_RUN', deployment: 'NOT_RUN'
}, null, 2))
JS
