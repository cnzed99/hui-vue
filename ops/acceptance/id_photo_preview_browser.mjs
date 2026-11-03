import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = '/var/www/print-system/releases/miniapp-id-photo-preview-20261103t1024z-4bd5cb9'
const expectedIndexSha256 = '4dc2e6e10fdef1914b69ddbb8db830fec6193834d85a94775e7703a9c1fdfb08'
const sourceCommit = '4bd5cb91b25bc3e9af6df06f18816437d4d2a21d'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `id-photo-preview-${runId}`)
const checks = []
const businessWrites = []
let browser
let context
let openSequence = 0

function check(name, condition) {
  checks.push({ name, passed: Boolean(condition) })
  if (!condition) throw new Error(`check_failed:${name}`)
}

async function open(page, route) {
  openSequence += 1
  await page.goto(`${origin}/miniapp/?case=${runId}-${openSequence}#${route}`, { waitUntil: 'domcontentloaded' })
}

async function chooseSyntheticPhoto(page) {
  const chooserPromise = page.waitForEvent('filechooser')
  await page.locator('.frame').click()
  const chooser = await chooserPromise
  await chooser.setFiles({
    name: 'synthetic-id-photo.png',
    mimeType: 'image/png',
    buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')
  })
  await page.getByText('本地预览，点击图片可更换', { exact: true }).waitFor({ state: 'visible' })
}

async function screenshot(page, name) {
  const target = path.join(out, `${name}.png`)
  await page.screenshot({ path: target })
  return {
    name,
    path: path.relative(base, target),
    sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex')
  }
}

const report = {
  schemaVersion: 1,
  scope: 'authorized-observation-derived ID photo six-inch preview; no price, order, payment or print',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  deployment: { expectedRelease, expectedIndexSha256 },
  boundaries: {
    originalSystemWrites: 'NOT_RUN',
    priceCalculation: 'NOT_IMPLEMENTED',
    orderCreation: 'NOT_IMPLEMENTED',
    payment: 'NOT_IMPLEMENTED',
    realPrinting: 'NOT_RUN',
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
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && /(?:order|pay|refund|print-task|jobs|quote|price)/i.test(url)) {
      businessWrites.push({ method, url })
    }
  })
  const page = await context.newPage()

  await open(page, '/pages/id-photo-preview/index?size=one')
  await page.getByText('普通证件照', { exact: true }).waitFor({ state: 'visible' })
  check('direct_preview_requires_fresh_one_time_selection', true)

  const layouts = [
    { id: 'one', name: '1寸证件照', repeat: 10, rotated: 0 },
    { id: 'two', name: '2寸证件照', repeat: 4, rotated: 4 },
    { id: 'three', name: '3寸证件照', repeat: 2, rotated: 0 }
  ]
  for (const item of layouts) {
    await open(page, `/pages/id-photo-upload/index?size=${item.id}`)
    await page.getByText('上传证件照', { exact: true }).waitFor({ state: 'visible' })
    await chooseSyntheticPhoto(page)
    await page.getByText('下一步', { exact: true }).click()
    await page.getByText('拼版预览', { exact: true }).waitFor({ state: 'visible' })
    check(`${item.id}_layout_count`, await page.locator('.cell').count() === item.repeat)
    check(`${item.id}_rotation`, await page.locator('.photo.rotated').count() === item.rotated)
    check(`${item.id}_six_inch_and_size_labels`, await page.getByText('请用六寸相纸打印', { exact: true }).isVisible()
      && await page.getByText(item.name, { exact: true }).isVisible())
    await page.getByText('+', { exact: true }).click()
    check(`${item.id}_copy_stepper`, (await page.locator('.stepper').textContent())?.replace(/\s/g, '') === '-2+')
    await page.getByText('计价规则待确认', { exact: true }).click()
    await page.getByText('演示计价规则待确认', { exact: true }).waitFor({ state: 'visible' })
    report.screenshots.push(await screenshot(page, `${item.id}-layout`))
    await open(page, `/pages/id-photo-preview/index?size=${item.id}`)
    await page.getByText('普通证件照', { exact: true }).waitFor({ state: 'visible' })
    check(`${item.id}_selection_consumed_once`, true)
  }

  await open(page, '/pages/id-photo-upload/index?size=small-one')
  await page.getByText('上传证件照', { exact: true }).waitFor({ state: 'visible' })
  await chooseSyntheticPhoto(page)
  await page.getByText('下一步', { exact: true }).click()
  await page.getByText('该尺寸排版待继续观察', { exact: true }).waitFor({ state: 'visible' })
  check('unobserved_size_remains_reserved', page.url().includes('/pages/id-photo-upload/index'))
  check('no_price_order_payment_or_print_write', businessWrites.length === 0)
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
  report.businessWrites = businessWrites
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed, report: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
