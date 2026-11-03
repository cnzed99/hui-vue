import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = process.env.EXPECTED_RELEASE
const expectedIndexSha256 = process.env.EXPECTED_INDEX_SHA256
const sourceCommit = '51de85faffc48d757f090e3d86009d3873bcd4f1'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `account-settings-boundary-${runId}`)
const checks = []
const writes = []
const externalRequests = []
let browser
let context

function check(name, condition) {
  checks.push({ name, passed: Boolean(condition) })
  if (!condition) throw new Error(`check_failed:${name}`)
}

const report = {
  schemaVersion: 1,
  scope: 'account settings observed-form privacy-closed boundary',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  deployment: { expectedRelease, expectedIndexSha256 },
  boundaries: {
    accountDataRead: 'NOT_RUN',
    accountDataWrite: 'NOT_RUN',
    avatarUpload: 'NOT_RUN',
    phoneAuthorization: 'NOT_RUN',
    originalSystemWrites: 'NOT_RUN',
    externalAcceptance: 'NOT_RUN'
  }
}

try {
  check('expected_deployment_declared', Boolean(expectedRelease && expectedIndexSha256))
  await fs.mkdir(out, { recursive: false, mode: 0o700 })
  const activeRelease = await fs.realpath('/var/www/print-system/miniapp')
  const indexSha256 = crypto.createHash('sha256').update(await fs.readFile('/var/www/print-system/miniapp/index.html')).digest('hex')
  report.deployment.activeRelease = activeRelease
  report.deployment.indexSha256 = indexSha256
  check('deployment_matches_candidate', activeRelease === expectedRelease && indexSha256 === expectedIndexSha256)

  process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(runtime, 'browsers')
  process.env.LD_LIBRARY_PATH = path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
  process.env.FONTCONFIG_FILE = path.join(runtime, 'fonts.conf')
  process.env.XDG_CACHE_HOME = path.join(runtime, 'cache')
  process.env.TMPDIR = path.join(runtime, 'run-tmp')
  await fs.mkdir(process.env.TMPDIR, { recursive: true, mode: 0o700 })
  const require = createRequire(path.join(runtime, 'package.json'))
  const { chromium } = require('playwright')
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'], env: { ...process.env } })
  context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  context.setDefaultTimeout(12000)
  context.on('request', request => {
    const method = request.method()
    const url = request.url()
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) writes.push({ method, url })
    if (/^https?:/i.test(url) && !url.startsWith(origin)) externalRequests.push({ method, url })
  })
  const page = await context.newPage()
  await page.goto(`${origin}/miniapp/?case=${runId}#/pages/profile/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('我的', { exact: true }).first().waitFor({ state: 'visible' })
  const settings = page.locator('.settings').first()
  await settings.waitFor({ state: 'visible' })
  await settings.click()
  await page.getByText('账户设置', { exact: true }).waitFor({ state: 'visible' })
  check('profile_routes_to_account_settings', page.url().includes('/pages/account-settings/index'))
  check('observed_fields_render', await page.getByText('头像', { exact: true }).isVisible()
    && await page.getByText('昵称', { exact: true }).isVisible()
    && await page.getByText('姓名', { exact: true }).isVisible()
    && await page.getByText('手机号', { exact: true }).isVisible()
    && await page.getByText('性别', { exact: true }).isVisible()
    && await page.getByText('男', { exact: true }).isVisible()
    && await page.getByText('女', { exact: true }).isVisible())
  check('all_personal_inputs_disabled', await page.locator('input').count() === 3
    && await page.locator('input:not([disabled])').count() === 0)
  check('no_avatar_file_input', await page.locator('input[type="file"]').count() === 0)
  check('privacy_boundary_is_explicit', await page.getByText(/不会读取、采集或保存个人资料/).isVisible())
  await page.getByText('保存接口待接入', { exact: true }).click()
  await page.getByText('账户资料保存尚未接入', { exact: true }).waitFor({ state: 'visible' })
  check('save_is_closed', true)
  check('no_writes_or_external_requests', writes.length === 0 && externalRequests.length === 0)
  report.screenshot = 'account-settings-boundary.png'
  await page.screenshot({ path: path.join(out, report.screenshot) })
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'
  report.failure = { type: error?.name || 'Error', message: String(error?.message || error).slice(0, 500), lastCheck: checks.at(-1)?.name || 'initialization' }
  process.exitCode = 1
} finally {
  if (context) await context.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString()
  report.passed = checks.filter(item => item.passed).length
  report.failed = checks.filter(item => !item.passed).length
  report.writes = writes
  report.externalRequests = externalRequests
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed, report: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
