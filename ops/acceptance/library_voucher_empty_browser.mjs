import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = process.env.EXPECTED_RELEASE
const expectedIndexSha256 = process.env.EXPECTED_INDEX_SHA256
const sourceCommit = '6495a55567124e6efcb074fd878f30c0746f33e2'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `library-voucher-empty-${runId}`)
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
  scope: 'read-only observed voucher and personal library empty states',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  deployment: { expectedRelease, expectedIndexSha256 },
  boundaries: {
    wechatLogin: 'NOT_IMPLEMENTED',
    voucherEntitlements: 'NOT_IMPLEMENTED',
    libraryEntitlements: 'NOT_IMPLEMENTED',
    purchase: 'NOT_RUN',
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

  await page.getByText('代金红包', { exact: true }).click()
  await page.getByText('暂无代金红包', { exact: true }).waitFor({ state: 'visible' })
  check('voucher_profile_route', page.url().includes('/pages/vouchers/index'))
  check('voucher_observed_tabs', await page.locator('.tab').allTextContents().then(items => items.join('|')) === '未使用|已使用|已过期')
  for (const tab of ['未使用', '已使用', '已过期']) {
    await page.getByText(tab, { exact: true }).click()
    check(`voucher_${tab}_active`, await page.locator('.tab.active').textContent() === tab)
    check(`voucher_${tab}_same_empty_copy`, await page.getByText('暂无代金红包', { exact: true }).count() === 1)
  }
  check('voucher_local_boundary', await page.getByText(/真实红包需微信登录和服务端权益接口/).isVisible())
  check('voucher_observed_illustration', await page.locator('.illustration .parcel').count() === 1)
  report.screenshots.push('voucher-empty.png')
  await page.screenshot({ path: path.join(out, 'voucher-empty.png') })

  await page.goto(`${origin}/miniapp/?case=${runId}-library#/pages/profile/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('我的文库', { exact: true }).click()
  await page.getByText('暂无文件哦~', { exact: true }).waitFor({ state: 'visible' })
  check('library_profile_route', page.url().includes('/pages/my-library/index'))
  check('library_observed_tabs', await page.locator('.tab').allTextContents().then(items => items.join('|')) === '已收藏|已购买')
  for (const tab of ['已收藏', '已购买']) {
    await page.getByText(tab, { exact: true }).click()
    check(`library_${tab}_active`, await page.locator('.tab.active').textContent() === tab)
    check(`library_${tab}_same_empty_copy`, await page.getByText('暂无文件哦~', { exact: true }).count() === 1)
    check(`library_${tab}_boundary`, await page.getByText(`${tab}列表将在微信登录与文库权益接口接入后加载。`, { exact: true }).isVisible())
  }
  check('library_observed_illustration', await page.locator('.illustration .parcel').count() === 1)
  check('no_fake_loading', await page.getByText('加载中', { exact: true }).count() === 0)
  check('no_writes_or_external_requests', writes.length === 0 && externalRequests.length === 0)
  report.screenshots.push('library-empty.png')
  await page.screenshot({ path: path.join(out, 'library-empty.png') })
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
