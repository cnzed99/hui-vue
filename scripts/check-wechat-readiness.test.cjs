const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const { mkdtempSync, readFileSync, rmSync } = require('node:fs')
const { join, resolve } = require('node:path')
const { tmpdir } = require('node:os')
const { evaluate, assertIntegration, httpsUrl, readConfig } = require('./wechat-readiness.cjs')

// Synthetic syntax fixtures only. Not real or approved AppID/domain; no network I/O.
const fixture = {
  VITE_APP_API_BASE: 'https://api.synthetic-wechat-fixture.cn/app-api',
  VITE_WECHAT_APP_ID: 'wx6f3a8c219d5e7b40',
  WECHAT_REQUEST_DOMAINS: 'https://api.synthetic-wechat-fixture.cn',
  WECHAT_UPLOAD_DOMAINS: 'https://api.synthetic-wechat-fixture.cn',
  WECHAT_DOWNLOAD_DOMAINS: 'https://files.synthetic-wechat-fixture.cn'
}
let checks = 0
function check(name, fn) { fn(); checks++; console.log('PASS ' + name) }
check('default configuration is NOT_READY and redacts rejected inputs', () => {
  const report = evaluate({ VITE_APP_API_BASE: 'https://user:secret@host.invalid/app-api', SECRET: 'private-key' })
  assert.equal(report.status, 'NOT_READY')
  assert.equal(report.configStatus, 'NOT_READY')
  assert.equal(report.checks.find(c => c.id === 'apiBase').value, null)
  assert.ok(!JSON.stringify(report).includes('user:secret@'))
  assert.ok(!JSON.stringify(report).includes('private-key'))
  assert.equal(report.deviceValidation, 'NOT_RUN')
})
check('valid syntax only permits compilation, never release acceptance', () => {
  const report = assertIntegration(fixture)
  assert.equal(report.configStatus, 'READY_FOR_COMPILE')
  assert.equal(report.status, 'NOT_READY')
  assert.equal(report.checks.find(c => c.id === 'paymentConfiguration').status, 'MISSING_OR_INVALID')
})
const badBases = [
  '', '/app-api', 'http://api.synthetic-wechat-fixture.cn/app-api',
  'https://localhost/app-api', 'https://a.local/app-api', 'https://a.internal/app-api',
  'https://127.0.0.1/app-api', 'https://2130706433/app-api', 'https://0x7f000001/app-api',
  'https://[::1]/app-api', 'https://10.2.3.4/app-api', 'https://api.example.com/app-api',
  'https://youfan.iyint.com/app-api', 'https://a.cn:444/app-api',
  'https://a.cn/app-api?x=1', 'https://a.cn/app-api?', 'https://a.cn/app-api#', 'https://a.cn/app-api/#part',
  'https://a.cn/other/../app-api', 'https://a.cn/%61pp-api', 'https://a.cn/app-api/../admin',
  'https://a.cn//app-api', 'https://a.cn/app-api/private', 'https://user@a.cn/app-api',
  'https://a.cn\\app-api', ' https://a.cn/app-api', 'https://a.cn./app-api', 'https://-a.cn/app-api'
]
check('reject HTTP, local/IP, placeholders, original system and URL/path bypasses', () => {
  for (const base of badBases) assert.equal(httpsUrl(base, true), null, base)
})
check('placeholder AppIDs and unapproved domain lists block integration', () => {
  for (const appId of ['', 'touristappid', 'wx0000000000000000', 'wx1234567890abcdef']) {
    assert.throws(() => assertIntegration({ ...fixture, VITE_WECHAT_APP_ID: appId }), /NOT_READY/)
  }
  for (const domains of ['', 'https://localhost', 'https://10.0.0.1', 'https://other.cn',
    'https://api.synthetic-wechat-fixture.cn/app-api', 'https://api.synthetic-wechat-fixture.cn,']) {
    assert.throws(() => assertIntegration({ ...fixture, WECHAT_UPLOAD_DOMAINS: domains }), /NOT_READY/)
  }
})
check('AppID and urlCheck must match generated manifest', () => {
  assert.doesNotThrow(() => assertIntegration(fixture, { 'mp-weixin': { appid: fixture.VITE_WECHAT_APP_ID, setting: { urlCheck: true } } }))
  assert.throws(() => assertIntegration(fixture, { 'mp-weixin': { appid: '', setting: { urlCheck: false } } }), /NOT_READY/)
})
check('external self-declarations remain unverified', () => {
  const env = { ...fixture }
  for (const key of Object.keys(readConfig({})).filter(key => key.endsWith('_CONFIRMED'))) env[key] = 'true'
  const report = evaluate(env)
  assert.equal(report.missing.length, 0)
  assert.equal(report.status, 'NOT_READY')
  assert.equal(report.deviceValidation, 'NOT_RUN')
  assert.equal(report.checks.find(c => c.id === 'deviceValidation').status, 'DECLARED_NOT_VERIFIED')
})
check('CLI writes machine JSON and propagates readiness exit status', () => {
  const scratch = mkdtempSync(join(tmpdir(), 'wechat-readiness-test-'))
  try {
    const output = join(scratch, 'readiness.json')
    const env = { ...process.env, ...readConfig({}), ...fixture }
    const positive = spawnSync(process.execPath, ['scripts/check-wechat-readiness.cjs', '--config-only', '--output', output], { cwd: resolve(__dirname, '..'), env, encoding: 'utf8' })
    assert.equal(positive.status, 0, positive.stderr)
    assert.equal(JSON.parse(readFileSync(output, 'utf8')).configStatus, 'READY_FOR_COMPILE')
    const release = spawnSync(process.execPath, ['scripts/check-wechat-readiness.cjs'], { cwd: resolve(__dirname, '..'), env, encoding: 'utf8' })
    assert.equal(release.status, 1)
    const negative = spawnSync(process.execPath, ['scripts/check-wechat-readiness.cjs', '--config-only'], { cwd: resolve(__dirname, '..'), env: { ...env, VITE_APP_API_BASE: '/app-api' }, encoding: 'utf8' })
    assert.equal(negative.status, 1)
    assert.equal(JSON.parse(negative.stdout).configStatus, 'NOT_READY')
  } finally { rmSync(scratch, { recursive: true, force: true }) }
})
console.log(JSON.stringify({ status: 'PASS', checks, fixtures: 'SYNTHETIC_NO_NETWORK', device: 'NOT_RUN' }))
