import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const originValue = new URL(origin).origin
const expectedRelease = process.env.EXPECTED_RELEASE
const expectedIndexSha256 = process.env.EXPECTED_INDEX_SHA256
const sourceCommit = '76bb28bf1704e660dc0c35b19d84c3ed28c9b34a'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `certificate-catalog-${runId}`)
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
  scope: 'observed identity card, household register and identity-bank privacy-closed pages',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  deployment: { expectedRelease, expectedIndexSha256 },
  boundaries: {
    cameraAccess: 'NOT_RUN', albumAccess: 'NOT_RUN', wechatImageAccess: 'NOT_RUN',
    documentUpload: 'NOT_RUN', edgeRecognition: 'NOT_IMPLEMENTED', layoutAndPricing: 'NOT_IMPLEMENTED',
    originalSystemWrites: 'NOT_RUN', externalAcceptance: 'NOT_RUN'
  }
}

async function openFromCatalog(page, label, expectedType) {
  await page.goto(`${origin}/miniapp/?case=${runId}-${expectedType}#/pages/scan-copy/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('扫描复印', { exact: true }).waitFor({ state: 'visible' })
  await page.getByText(label, { exact: true }).click()
  await page.waitForURL(url => url.hash.includes(`/pages/certificate-single/index?type=${expectedType}`))
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
    if (/^https?:/i.test(url) && new URL(url).origin !== originValue) externalRequests.push({ method, url })
  })
  const page = await context.newPage()

  await openFromCatalog(page, '身份证 / 银行卡', 'identity-card')
  await page.getByText('添加身份证', { exact: true }).waitFor({ state: 'visible' })
  check('identity_observed_hint', await page.getByText('可一次选择最多2张身份证照片，按正面、反面顺序添加', { exact: true }).isVisible())
  check('identity_two_positions', await page.locator('.upload-card').count() === 2)
  check('identity_actions_and_sides', await page.getByText('添加正面照', { exact: true }).count() === 1
    && await page.getByText('添加背面照', { exact: true }).count() === 1
    && await page.getByText('正面', { exact: true }).count() === 1
    && await page.getByText('反面', { exact: true }).count() === 1)
  check('identity_one_swap', await page.getByText('↕ 交换顺序', { exact: true }).count() === 1)
  check('identity_no_sensitive_controls', await page.locator('input, textarea').count() === 0)
  await page.getByText('添加正面照', { exact: true }).click()
  await page.getByText('添加正面照尚未接入', { exact: true }).waitFor({ state: 'visible' })
  check('identity_selection_closed', true)
  report.screenshots.push('identity-card-boundary.png')
  await page.screenshot({ path: path.join(out, 'identity-card-boundary.png'), fullPage: true })

  await openFromCatalog(page, '户口本复印', 'household-register')
  await page.getByText('添加户口本', { exact: true }).waitFor({ state: 'visible' })
  check('household_observed_hint', await page.getByText('根据需要添加户口本，常用是2张，最多支持4张', { exact: true }).isVisible())
  check('household_four_positions', await page.locator('.upload-card').count() === 4
    && await page.getByText('添加户口本页', { exact: true }).count() === 4)
  check('household_three_swaps', await page.getByText('↕ 交换顺序', { exact: true }).count() === 3)
  check('household_no_sensitive_controls', await page.locator('input, textarea').count() === 0)
  report.screenshots.push('household-register-boundary.png')
  await page.screenshot({ path: path.join(out, 'household-register-boundary.png'), fullPage: true })

  await openFromCatalog(page, '身份证 + 银行卡', 'identity-bank')
  await page.getByText('添加身份证+银行卡', { exact: true }).waitFor({ state: 'visible' })
  check('identity_bank_observed_hint', await page.getByText('证件与拍摄背景的颜色请尽量区分开', { exact: true }).isVisible())
  check('identity_bank_four_positions', await page.locator('.upload-card').count() === 4)
  check('identity_bank_exact_actions', await page.getByText('添加身份证正面照', { exact: true }).count() === 1
    && await page.getByText('添加身份证背面照', { exact: true }).count() === 1
    && await page.getByText('添加银行卡正面照', { exact: true }).count() === 1
    && await page.getByText('添加银行卡背面照', { exact: true }).count() === 1)
  check('identity_bank_three_swaps', await page.getByText('↕ 交换顺序', { exact: true }).count() === 3)
  check('identity_bank_no_sensitive_controls', await page.locator('input, textarea').count() === 0)
  await page.getByText('下一步', { exact: true }).click()
  await page.getByText('证件上传与识别尚未接入', { exact: true }).waitFor({ state: 'visible' })
  check('catalog_next_step_closed', true)
  report.screenshots.push('identity-bank-boundary.png')
  await page.screenshot({ path: path.join(out, 'identity-bank-boundary.png'), fullPage: true })

  check('no_writes_or_external_requests', writes.length === 0 && externalRequests.length === 0)
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
