import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = '/var/www/print-system/releases/miniapp-franchise-boundary-20261103t1035z-c8493bd'
const expectedIndexSha256 = '21b2a510a826ae5b2faebbd5f38013c719519a18eb1df377e1ec2a00fedd8986'
const sourceCommit = 'c8493bdadb2e249a1e1af734372eb7b7e6e70662'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `franchise-boundary-${runId}`)
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
  scope: 'franchise application observed-form privacy-closed boundary',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  deployment: { expectedRelease, expectedIndexSha256 },
  boundaries: {
    applicationSubmission: 'NOT_IMPLEMENTED',
    personalDataCollection: 'NOT_RUN',
    locationAccess: 'NOT_RUN',
    photoUpload: 'NOT_RUN',
    originalSystemWrites: 'NOT_RUN',
    externalAcceptance: 'NOT_RUN'
  }
}

try {
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
  await page.getByText('我要加盟', { exact: true }).click()
  await page.getByText('申请开通', { exact: true }).waitFor({ state: 'visible' })
  check('profile_routes_to_franchise_boundary', page.url().includes('/pages/franchise/index'))
  check('observed_two_steps_render', await page.getByText('填写基本信息', { exact: true }).isVisible()
    && await page.getByText('关注公众号获取账号', { exact: true }).isVisible())
  check('observed_fields_render', await page.getByText('联系人', { exact: true }).isVisible()
    && await page.getByText('联系电话', { exact: true }).isVisible()
    && await page.getByText('门店名称', { exact: true }).isVisible()
    && await page.getByText('门店类型', { exact: true }).isVisible()
    && await page.getByText('门店位置', { exact: true }).isVisible()
    && await page.getByText('门店照片', { exact: true }).isVisible())
  check('all_personal_inputs_disabled', await page.locator('input').count() === 3
    && await page.locator('input:not([disabled])').count() === 0)
  check('privacy_boundary_is_explicit', await page.getByText(/不会采集、定位、上传或提交任何资料/).isVisible())
  await page.getByText('申请接口待接入', { exact: true }).click()
  await page.getByText('加盟申请提交尚未接入', { exact: true }).waitFor({ state: 'visible' })
  check('submit_is_closed', true)
  check('no_writes_or_external_requests', writes.length === 0 && externalRequests.length === 0)
  report.screenshot = 'franchise-boundary.png'
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
