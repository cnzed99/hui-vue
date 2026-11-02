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
node - <<'JS' >"$EVIDENCE/artifact-checks.json"
const fs = require('node:fs'), assert = require('node:assert/strict')
const root = 'dist/integration/mp-weixin'
const project = JSON.parse(fs.readFileSync(root + '/project.config.json'))
assert.equal(project.appid, process.env.VITE_WECHAT_APP_ID)
assert.equal(project.setting.urlCheck, true)
const manifest = JSON.parse(fs.readFileSync('src/manifest.json'))
assert.equal(manifest['mp-weixin'].appid, '')
assert.equal(manifest['mp-weixin'].setting.urlCheck, false)
const expected = ['catalog', 'demoCancellations', 'demoFiles', 'demoImageCollages', 'demoLibrary', 'demoOrders', 'demoPayments', 'demoPrintResult', 'demoQuotes', 'demoSession', 'demoTextFiles'].sort()
const consumers = fs.readdirSync('src/api').filter(name => name.endsWith('.ts') && /import\.meta\.env\.VITE_APP_API_BASE\b/.test(fs.readFileSync('src/api/' + name, 'utf8'))).map(name => name.slice(0, -3)).sort()
assert.deepEqual(consumers, expected)
for (const name of consumers) {
  const code = fs.readFileSync(root + '/api/' + name + '.js', 'utf8')
  assert.ok(code.includes(process.env.VITE_APP_API_BASE), name + ': missing configured API base')
  assert.ok(!code.includes('VITE_APP_API_BASE'), name + ': unresolved env reference')
}
const caller = fs.readFileSync(root + '/api/demoImageCollages.js', 'utf8')
const binding = caller.match(/(?:const|var|let)\s+([A-Za-z_$][\w$]*)=require\(["']\.\/demoImageCollageStaged\.js["']\)/)?.[1]
assert.ok(binding, 'caller must require staged adapter')
assert.ok(caller.includes(binding + '.uploadStagedImageCollage('), 'caller must invoke adapter export')
assert.ok(caller.includes(String.raw`.replace(/\/image-collages$/,"/image-collage-upload-sessions")`), 'caller must replace exact staged route')
assert.match(caller, new RegExp(binding + '\\.uploadStagedImageCollage\\([\\w$]+\\.replace\\('))
const adapter = fs.readFileSync(root + '/api/demoImageCollageStaged.js', 'utf8')
assert.match(adapter, /exports\.uploadStagedImageCollage\s*=/)
console.log(JSON.stringify({
  status: 'PASS', fixtureOnly: true, appIdInjected: true, urlCheckEnabled: true,
  apiBaseConsumers: consumers, noUnresolvedEnv: true, stagedAdapterConnection: 'STATIC_COMPILED_ONLY',
  sourceManifestFieldsPreserved: true, device: 'NOT_RUN'
}, null, 2))
JS
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
