import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = '/var/www/print-system/releases/miniapp-scan-file-copy-final-20261103T0210Z-1be1c6d'
const expectedIndexSha256 = 'a993fc4fc03ee92ce05df2d6e608fdbc8b3c812775eb214cab8be6128b084982'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `scan-file-copy-${runId}`)
const checks = []
const requests = []
const forbidden = []
const allowedWritePaths = new Set([
  '/app-api/print/miniapp/demo/sessions',
  '/app-api/print/miniapp/demo/image-files'
])
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
  scope: 'scan file-copy H5 selection to shared print settings',
  sourceCommit: '1be1c6d980672afd584664b461c07aa83d137141',
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  boundaries: {
    originalSystemWrites: 'NOT_RUN',
    certificateLayout: 'NOT_RUN',
    autoCorrection: 'NOT_RUN',
    downstreamOrderFlow: 'REUSED_UNCHANGED_PREVIOUSLY_VERIFIED',
    realPayment: 'NOT_RUN',
    realPrinter: 'NOT_RUN',
    wechatDevice: 'NOT_RUN',
    externalAcceptance: 'NOT_RUN'
  }
}

try {
  await fs.mkdir(out, { recursive: false, mode: 0o700 })
  const activeRelease = await fs.realpath('/var/www/print-system/miniapp')
  const indexSha256 = crypto.createHash('sha256')
    .update(await fs.readFile('/var/www/print-system/miniapp/index.html')).digest('hex')
  report.deployment = { activeRelease, indexSha256, expectedRelease, expectedIndexSha256 }
  check('deployment_matches_reviewed_candidate', activeRelease === expectedRelease && indexSha256 === expectedIndexSha256, report.deployment)
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
  await context.route('**/*', async route => {
    const request = route.request()
    const url = request.url()
    const parsed = new URL(url)
    const method = request.method()
    const localObject = parsed.protocol === 'blob:' || parsed.protocol === 'data:'
    const allowedWrite = ['GET', 'HEAD', 'OPTIONS'].includes(method) || allowedWritePaths.has(parsed.pathname)
    if ((!localObject && parsed.origin !== origin) || !allowedWrite) {
      forbidden.push({ method: request.method(), url })
      await route.abort('blockedbyclient')
      return
    }
    await route.continue()
  })
  context.on('request', request => {
    const method = request.method()
    const url = request.url()
    const parsed = new URL(url)
    requests.push({ method, path: parsed.pathname })
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !allowedWritePaths.has(parsed.pathname)) {
      forbidden.push({ method, url })
    }
  })

  let page = await context.newPage()
  await page.goto(`${origin}/miniapp/#/pages/scan-copy/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('文件复印已开放单张图片安全演示', { exact: false }).waitFor({ state: 'visible' })
  check('scan_directory_renders', await page.getByText('扫描复印', { exact: true }).isVisible()
    && await page.getByText('文件复印', { exact: true }).isVisible())

  await page.locator('.file-card').click()
  await page.getByText('严禁上传反动、色情、暴力、非法及涉密资料', { exact: true }).waitFor({ state: 'visible' })
  check('file_copy_source_sheet_matches_h5', await page.getByText('选择图片', { exact: true }).isVisible()
    && await page.getByText('拍照', { exact: true }).count() === 0
    && await page.getByText('手机相册', { exact: true }).count() === 0)
  report.screenshots.push(await shot(page, 'source-sheet'))

  const pngBase64 = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 320
    canvas.height = 200
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, 320, 200)
    ctx.fillStyle = '#1f2937'
    ctx.fillRect(38, 30, 244, 8)
    ctx.fillRect(38, 58, 190, 7)
    ctx.fillRect(38, 86, 220, 7)
    ctx.fillStyle = '#ff9634'
    ctx.fillRect(38, 126, 110, 34)
    return canvas.toDataURL('image/png').split(',')[1]
  })
  const chooserPromise = page.waitForEvent('filechooser')
  await page.getByText('选择图片', { exact: true }).click()
  const chooser = await chooserPromise
  await chooser.setFiles({ name: 'scan-copy-ui.png', mimeType: 'image/png', buffer: Buffer.from(pngBase64, 'base64') })

  await page.getByText('文件复印规则', { exact: true }).waitFor({ state: 'visible' })
  await page.getByText('安全演示会话已建立', { exact: true }).waitFor({ state: 'visible' })
  check('document_copy_context_survives_picker', await page.getByText('文件复印', { exact: true }).isVisible()
    && await page.getByText('scan-copy-ui.png', { exact: true }).isVisible()
    && await page.getByText('单张文件照片 · A4 纵向 · 等比完整显示 · 不裁剪', { exact: true }).isVisible())
  check('unsupported_processing_is_explicit', await page.getByText('证件组合、自动矫正和去阴影仍未开放。', { exact: true }).isVisible())
  report.screenshots.push(await shot(page, 'selected'))

  await page.close()
  page = await context.newPage()
  await page.goto(`${origin}/miniapp/#/pages/image-upload/index`, { waitUntil: 'domcontentloaded' })
  await page.getByText('图片选择已失效，请返回重新选择。', { exact: true }).waitFor({ state: 'visible' })
  check('selection_handoff_is_consumed_once', await page.getByText('scan-copy-ui.png', { exact: true }).count() === 0
    && await page.getByText('图片打印', { exact: true }).isVisible())

  await page.goto(`${origin}/miniapp/#/pages/scan-copy/index`, { waitUntil: 'domcontentloaded' })
  await page.locator('.file-card').click()
  const retryChooserPromise = page.waitForEvent('filechooser')
  await page.getByText('选择图片', { exact: true }).click()
  const retryChooser = await retryChooserPromise
  await retryChooser.setFiles({ name: 'scan-copy-ui.png', mimeType: 'image/png', buffer: Buffer.from(pngBase64, 'base64') })
  await page.getByText('文件复印规则', { exact: true }).waitFor({ state: 'visible' })
  check('ordinary_image_title_not_leaked_into_copy_flow', await page.getByText('文件复印', { exact: true }).isVisible())

  await page.getByText('上传并生成 A4 文档', { exact: true }).click()
  await page.getByText('转换完成', { exact: true }).waitFor({ state: 'visible', timeout: 60000 })
  check('image_upload_reaches_ready', await page.getByText('已生成 1 页 A4 PDF，原图与中间文件将在 1 小时内清理。', { exact: true }).isVisible())
  check('image_endpoint_used', requests.some(item => item.method === 'POST' && item.path === '/app-api/print/miniapp/demo/image-files'))

  await page.getByText('继续设置打印参数', { exact: true }).click()
  await page.getByText('打印设置', { exact: true }).first().waitFor({ state: 'visible' })
  check('enters_shared_settings', await page.getByText('scan-copy-ui.pdf', { exact: true }).isVisible()
    && await page.getByText('服务端解析完成', { exact: true }).isVisible()
    && await page.getByText(/· 1 页$/).isVisible()
    && await page.getByText('确认参数', { exact: true }).isVisible())
  check('no_external_or_unexpected_writes', forbidden.length === 0, forbidden)
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
  report.forbiddenRequests = forbidden
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed,
    report: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
