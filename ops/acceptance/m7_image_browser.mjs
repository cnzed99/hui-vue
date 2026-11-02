import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `m7-image-browser-${runId}`)
const checks = []
const requests = []
const forbiddenRequests = []
let browser
let context

function check(name, condition, detail = undefined) {
  const item = { name, passed: Boolean(condition) }
  if (detail !== undefined) item.detail = detail
  checks.push(item)
  if (!condition) throw new Error(`check_failed:${name}`)
}

async function shot(page, name) {
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
  scope: 'M7 single-image H5 upload to shared print settings',
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  boundaries: {
    originalSystemWrites: 'NOT_RUN',
    collageUpload: 'NOT_RUN',
    aiEnhancement: 'NOT_RUN',
    cropping: 'NOT_RUN',
    orderCreation: 'NOT_RUN_SHARED_UI_ALREADY_VERIFIED',
    realPayment: 'NOT_RUN',
    realPrinter: 'NOT_RUN',
    wechatDevice: 'NOT_RUN',
    externalAcceptance: 'NOT_RUN'
  }
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
  context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  context.setDefaultTimeout(15000)
  context.on('request', request => {
    const method = request.method()
    const url = request.url()
    requests.push({ method, path: new URL(url).pathname })
    if (/youfan\.iyint\.com/i.test(url)) forbiddenRequests.push({ method, url })
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)
      && !url.startsWith(`${origin}/app-api/print/miniapp/demo/sessions`)
      && !url.startsWith(`${origin}/app-api/print/miniapp/demo/image-files`)) {
      forbiddenRequests.push({ method, url })
    }
  })
  const page = await context.newPage()
  await page.goto(`${origin}/miniapp/#/pages/image-method/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('普通图片打印已开放单张 JPG/PNG 安全演示上传', { exact: false }).waitFor({ state: 'visible' })
  check('image_directory_renders_four_methods', await page.locator('.method-card').count() === 4)

  await page.locator('.method-card').filter({ hasText: '图片拼版打印' }).click()
  await page.getByText('严禁上传反动、色情、暴力、非法及涉密资料', { exact: true }).waitFor({ state: 'visible' })
  await page.getByText('手机相册', { exact: true }).click()
  await page.getByText('图片拼版将在后续批次单独开放', { exact: true }).waitFor({ state: 'visible' })
  check('collage_remains_explicitly_blocked', page.url().includes('/pages/image-method/index'))

  await page.locator('.method-card').filter({ hasText: '图片打印' }).first().click()
  await page.getByText('严禁上传反动、色情、暴力、非法及涉密资料', { exact: true }).waitFor({ state: 'visible' })
  const pngBase64 = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 320
    canvas.height = 200
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, 320, 200)
    ctx.fillStyle = '#ff9634'
    ctx.fillRect(0, 0, 160, 100)
    ctx.fillStyle = '#2176ff'
    ctx.fillRect(160, 0, 160, 100)
    ctx.fillStyle = '#2aae72'
    ctx.fillRect(0, 100, 160, 100)
    ctx.fillStyle = '#222222'
    ctx.fillRect(160, 100, 160, 100)
    return canvas.toDataURL('image/png').split(',')[1]
  })
  const chooserPromise = page.waitForEvent('filechooser')
  await page.getByText('手机相册', { exact: true }).click()
  const chooser = await chooserPromise
  await chooser.setFiles({ name: 'm7-ui.png', mimeType: 'image/png', buffer: Buffer.from(pngBase64, 'base64') })

  await page.getByText('本批转换规则', { exact: true }).waitFor({ state: 'visible' })
  await page.getByText('安全演示会话已建立', { exact: true }).waitFor({ state: 'visible' })
  check('selected_image_preview_and_rules_render', await page.getByText('m7-ui.png', { exact: true }).isVisible()
    && await page.getByText('单张图片 · A4 纵向 · 等比完整显示 · 不裁剪 · 透明区域白底', { exact: true }).isVisible())
  report.screenshots.push(await shot(page, 'image-selected'))

  await page.getByText('上传并生成 A4 文档', { exact: true }).click()
  await page.getByText('转换完成', { exact: true }).waitFor({ state: 'visible', timeout: 60000 })
  check('image_upload_reaches_ready', await page.getByText('已生成 1 页 A4 PDF，原图与中间文件将在 1 小时内清理。', { exact: true }).isVisible())
  check('image_endpoint_was_used', requests.some(item => item.method === 'POST' && item.path === '/app-api/print/miniapp/demo/image-files'))
  report.screenshots.push(await shot(page, 'image-ready'))

  await page.getByText('继续设置打印参数', { exact: true }).click()
  await page.getByText('打印设置', { exact: true }).first().waitFor({ state: 'visible' })
  check('ready_image_enters_shared_settings', await page.getByText('m7-ui.pdf', { exact: true }).isVisible()
    && await page.getByText('服务端解析完成', { exact: true }).isVisible()
    && await page.getByText(/· 1 页$/).isVisible()
    && await page.getByText('确认参数', { exact: true }).isVisible())
  check('no_unexpected_writes_or_original_calls', forbiddenRequests.length === 0, forbiddenRequests)
  report.screenshots.push(await shot(page, 'shared-settings'))
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
  report.forbiddenRequests = forbiddenRequests
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed,
    report: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
