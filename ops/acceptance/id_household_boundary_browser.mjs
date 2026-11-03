import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = process.env.EXPECTED_RELEASE
const expectedIndexSha256 = process.env.EXPECTED_INDEX_SHA256
const sourceCommit = 'e3d414c95e22d1033ad468acfa62b08fa63d8a78'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `id-household-boundary-${runId}`)
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
  scope: 'observed ID card and household register combination privacy-closed page',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  deployment: { expectedRelease, expectedIndexSha256 },
  boundaries: {
    cameraAccess: 'NOT_RUN',
    albumAccess: 'NOT_RUN',
    wechatImageAccess: 'NOT_RUN',
    documentUpload: 'NOT_RUN',
    edgeRecognition: 'NOT_IMPLEMENTED',
    layoutAndPricing: 'NOT_IMPLEMENTED',
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
  await page.goto(`${origin}/miniapp/?case=${runId}#/pages/scan-copy/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('扫描复印', { exact: true }).waitFor({ state: 'visible' })
  await page.getByText('身份证 + 户口本', { exact: true }).click()
  await page.getByText('身份证+户口本组合复印', { exact: true }).waitFor({ state: 'visible' })
  check('scan_copy_routes_to_observed_combo', page.url().includes('/pages/certificate-combo/index'))
  check('observed_hint_and_sections', await page.getByText('证件与拍摄背景的颜色请尽量区分开', { exact: true }).isVisible()
    && await page.getByText('身份证照片', { exact: true }).isVisible()
    && await page.getByText('户口本照片', { exact: true }).isVisible())
  check('four_observed_upload_positions', await page.locator('.upload-card').count() === 4)
  check('id_front_and_back_actions', await page.getByText('添加身份证正面照', { exact: true }).count() === 1
    && await page.getByText('添加身份证背面照', { exact: true }).count() === 1)
  check('two_household_actions', await page.getByText('添加户口本内页', { exact: true }).count() === 2)
  check('edge_recognition_copy', await page.getByText('添加清晰原图，自动识别证件边缘', { exact: true }).count() === 4)
  check('no_file_input_or_sensitive_controls', await page.locator('input, textarea').count() === 0)
  check('privacy_boundary_explicit', await page.getByText(/不会打开相机、相册或微信图片，不上传、识别、保存个人证件资料/).isVisible())
  await page.getByText('添加身份证正面照', { exact: true }).click()
  await page.getByText('添加身份证正面照尚未接入', { exact: true }).waitFor({ state: 'visible' })
  check('image_selection_closed', true)
  await page.getByText('下一步', { exact: true }).click()
  await page.getByText('证件上传与识别尚未接入', { exact: true }).waitFor({ state: 'visible' })
  check('next_step_closed', true)
  check('no_writes_or_external_requests', writes.length === 0 && externalRequests.length === 0)
  report.screenshot = 'id-household-boundary.png'
  await page.screenshot({ path: path.join(out, report.screenshot), fullPage: true })
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
