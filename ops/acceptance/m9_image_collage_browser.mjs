import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `m9-image-collage-browser-${runId}`)
const checks = []
const requests = []
const forbidden = []
let browser
let context

function check(name, condition, detail = undefined) {
  const item = { name, passed: Boolean(condition) }
  if (detail !== undefined) item.detail = detail
  checks.push(item)
  if (!condition) throw new Error(`check_failed:${name}`)
}

async function screenshot(page, name) {
  const target = path.join(out, `${name}.png`)
  await page.screenshot({ path: target })
  return { name, path: path.relative(base, target),
    sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex') }
}

const report = {
  schemaVersion: 1,
  scope: 'M9 H5 two-image collage to shared print settings',
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  boundaries: { originalSystemWrites: 'NOT_RUN', realPayment: 'NOT_RUN', realPrinter: 'NOT_RUN',
    wechatDevice: 'NOT_RUN', externalAcceptance: 'NOT_RUN' }
}

try {
  await fs.mkdir(out, { recursive: false, mode: 0o700 })
  process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(runtime, 'browsers')
  process.env.LD_LIBRARY_PATH = path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
  process.env.FONTCONFIG_FILE = path.join(runtime, 'fonts.conf')
  process.env.XDG_CACHE_HOME = path.join(runtime, 'cache')
  process.env.TMPDIR = path.join(runtime, 'run-tmp')
  await fs.mkdir(process.env.TMPDIR, { recursive: true, mode: 0o700 })
  const require = createRequire(path.join(runtime, 'package.json'))
  const { chromium } = require('playwright')
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'], env: { ...process.env } })
  context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  context.setDefaultTimeout(20000)
  context.on('request', request => {
    const method = request.method()
    const url = request.url()
    requests.push({ method, path: new URL(url).pathname })
    if (/youfan\.iyint\.com/i.test(url)) forbidden.push({ method, url })
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)
      && !url.startsWith(`${origin}/app-api/print/miniapp/demo/sessions`)
      && !url.startsWith(`${origin}/app-api/print/miniapp/demo/image-collages`)) forbidden.push({ method, url })
  })
  const page = await context.newPage()
  const png1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFElEQVR42mP4z8DAwMDAxMDAwMAAAAwBAf8B9pQAAAAASUVORK5CYII=', 'base64')
  const png2 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFElEQVR42mNgYPjPwMDAwMTAwMAAAAwBAf8B3c4AAAAASUVORK5CYII=', 'base64')
  const chooserPromise = page.waitForEvent('filechooser')
  await page.goto(`${origin}/miniapp/#/pages/image-collage/index?source=${encodeURIComponent('手机相册')}`,
    { waitUntil: 'domcontentloaded' })
  const chooser = await chooserPromise
  await chooser.setFiles([
    { name: 'm9-orange.png', mimeType: 'image/png', buffer: png1 },
    { name: 'm9-blue.png', mimeType: 'image/png', buffer: png2 }
  ])
  await page.getByText('已选 2/9 张', { exact: true }).waitFor({ state: 'visible' })
  check('two_images_selected_in_order', await page.locator('.tile').count() === 2)
  check('fixed_grid_boundary_visible', await page.getByText('固定网格开发演示', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(page, 'two-images-selected'))

  await page.getByText('生成拼版并设置打印', { exact: true }).click()
  await page.getByText('拼版已完成', { exact: true }).waitFor({ state: 'visible', timeout: 60000 })
  check('collage_upload_endpoint_used', requests.some(item => item.method === 'POST'
    && item.path === '/app-api/print/miniapp/demo/image-collages'))
  check('single_page_ready_visible', await page.getByText('已生成 1 页 A4 PDF。', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(page, 'collage-ready'))

  await page.getByText('继续设置打印参数', { exact: true }).click()
  await page.getByText('打印设置', { exact: true }).first().waitFor({ state: 'visible' })
  check('collage_enters_shared_settings', await page.getByText('图片拼版.pdf', { exact: true }).isVisible()
    && await page.getByText('服务端解析完成', { exact: true }).isVisible()
    && await page.getByText(/· 1 页$/).isVisible())
  check('no_original_or_unexpected_writes', forbidden.length === 0, forbidden)
  report.screenshots.push(await screenshot(page, 'shared-settings'))
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'
  report.failure = { type: error?.name || 'Error', message: String(error?.message || error).slice(0, 500),
    lastCheck: checks.at(-1)?.name || 'initialization' }
  process.exitCode = 1
} finally {
  if (context) await context.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString()
  report.passed = checks.filter(item => item.passed).length
  report.failed = checks.filter(item => !item.passed).length
  report.forbiddenRequests = forbidden
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed,
    report: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'),
    failure: report.failure || null }))
}
