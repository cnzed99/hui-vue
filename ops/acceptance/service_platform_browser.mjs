import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const worktree = path.join(base, 'runtime/worktrees/miniapp-id-photo-preview-20261103')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = '/var/www/print-system/releases/miniapp-service-platform-20261103t1030z-253f608'
const expectedIndexSha256 = 'a6611de40a22c92271447a34e8f4713adc302aa34e93153191bdc545f1274de2'
const sourceCommit = '253f608b88ef018be6aa25255bbf8b741c50e2c0'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `service-platform-${runId}`)
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
  scope: 'WeChat official feedback entry and phone authorization boundary',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  deployment: { expectedRelease, expectedIndexSha256 },
  boundaries: {
    originalSystemWrites: 'NOT_RUN',
    feedbackSubmission: 'NOT_RUN',
    phoneAuthorization: 'NOT_RUN',
    phoneDataReadOrUpload: 'NOT_RUN',
    wechatDeveloperTools: 'NOT_RUN',
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

  const weixinWxml = await fs.readFile(path.join(worktree, 'dist/build/mp-weixin/pages/service-center/index.wxml'), 'utf8')
  check('weixin_build_uses_official_feedback_only', weixinWxml.includes('open-type="feedback"') && !weixinWxml.includes('getPhoneNumber'))

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
  await page.goto(`${origin}/miniapp/?case=${runId}#/pages/service-center/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('服务中心', { exact: true }).waitFor({ state: 'visible' })
  check('h5_marks_feedback_as_wechat_platform', await page.getByText('微信官方入口，仅在微信小程序中可用', { exact: true }).isVisible()
    && await page.getByText('微信端', { exact: true }).isVisible())

  await page.getByText('意见反馈', { exact: true }).click()
  await page.getByText('请在微信小程序中使用官方反馈', { exact: true }).waitFor({ state: 'visible' })
  check('h5_feedback_does_not_fake_submission', true)

  await page.getByText('绑定手机', { exact: true }).click()
  await page.getByText('温馨提示', { exact: true }).waitFor({ state: 'visible' })
  check('phone_notice_states_consent_and_not_connected', await page.getByText(/由用户主动授权微信手机号/).isVisible()
    && await page.getByText(/不会读取或上传手机号/).isVisible())
  check('phone_notice_has_no_sensitive_input', await page.locator('input, textarea').count() === 0)
  await page.getByText('手机号授权待接入', { exact: true }).click()
  await page.getByText('微信手机号授权尚未接入', { exact: true }).waitFor({ state: 'visible' })
  check('phone_action_remains_reserved', true)
  check('no_writes_or_external_requests', writes.length === 0 && externalRequests.length === 0)
  report.screenshot = 'service-platform.png'
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
