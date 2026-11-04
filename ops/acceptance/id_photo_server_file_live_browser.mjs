import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { deflateSync } from 'node:zlib'
import { createRequire } from 'node:module'

const root = '/home/ubuntu/print-system'
const runtime = path.join(root, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const expectedRelease = '/var/www/print-system/releases/miniapp-id-photo-server-file-20261103-69d93c1'
const expectedIndexSha256 = '69d93c169fed9249f57b24e016f6d9add92a37273afe855be81bc7781b7da968'
const sourceCommit = 'ff741ea1e68c4e44f8f1833bbbd8949dedb40697'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(root, 'evidence/miniapp', `id-photo-server-file-live-${runId}`)
const checks = []
const writes = []
let uploadPayload = null
let readyPayload = null
let browser
let context

function check(name, condition) {
  const passed = Boolean(condition)
  checks.push({ name, passed })
  if (!passed) throw new Error(`check_failed:${name}`)
}

function fileProjection(payload) {
  const item = payload?.data
  return {
    code: payload?.code ?? null,
    id: typeof item?.id === 'string' ? item.id : null,
    status: typeof item?.status === 'string' ? item.status : null,
    ready: typeof item?.ready === 'boolean' ? item.ready : null,
    pageCount: Number.isSafeInteger(item?.pageCount) ? item.pageCount : null,
    sizeBytes: Number.isSafeInteger(item?.sizeBytes) ? item.sizeBytes : null,
    failureCode: typeof item?.failureCode === 'string' || item?.failureCode === null ? item.failureCode : null
  }
}

function crc32(buffer) {
  let crc = 0xffffffff
  for (const byte of buffer) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
  }
  return (crc ^ 0xffffffff) >>> 0
}

function chunk(type, data) {
  const kind = Buffer.from(type)
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const checksum = Buffer.alloc(4)
  checksum.writeUInt32BE(crc32(Buffer.concat([kind, data])))
  return Buffer.concat([length, kind, data, checksum])
}

function syntheticPng() {
  const width = 600
  const height = 600
  const stride = width * 3 + 1
  const raw = Buffer.alloc(stride * height)
  for (let y = 0; y < height; y++) {
    const row = y * stride
    raw[row] = 0
    for (let x = 0; x < width; x++) {
      const offset = row + 1 + x * 3
      raw[offset] = x < width / 2 ? 235 : 120
      raw[offset + 1] = y < height / 2 ? 210 : 145
      raw[offset + 2] = 190
    }
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header.set([8, 2, 0, 0, 0], 8)
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ])
}

const report = {
  schemaVersion: 1,
  scope: 'deployed H5 ID-photo local selection, preview and new-system server file generation',
  sourceCommit,
  runId,
  startedAt: new Date().toISOString(),
  checks,
  writes,
  boundaries: {
    originalSystem: 'NOT_ACCESSED',
    quote: 'NOT_RUN',
    order: 'NOT_RUN',
    payment: 'NOT_RUN',
    realPrinting: 'NOT_RUN',
    wechatDevice: 'NOT_RUN'
  }
}

try {
  await fs.mkdir(out, { recursive: false, mode: 0o700 })
  const activeRelease = await fs.realpath('/var/www/print-system/miniapp')
  const index = await fs.readFile('/var/www/print-system/miniapp/index.html')
  const indexSha256 = crypto.createHash('sha256').update(index).digest('hex')
  report.deployment = { activeRelease, indexSha256, expectedRelease, expectedIndexSha256 }
  check('deployed_h5_exact_candidate', activeRelease === expectedRelease && indexSha256 === expectedIndexSha256)

  Object.assign(process.env, {
    PLAYWRIGHT_BROWSERS_PATH: path.join(runtime, 'browsers'),
    LD_LIBRARY_PATH: path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu'),
    FONTCONFIG_FILE: path.join(runtime, 'fonts.conf'),
    XDG_CACHE_HOME: path.join(runtime, 'cache'),
    TMPDIR: path.join(runtime, 'run-tmp')
  })
  await fs.mkdir(process.env.TMPDIR, { recursive: true, mode: 0o700 })
  const { chromium } = createRequire(path.join(runtime, 'package.json'))('playwright')
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'], env: { ...process.env } })
  context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  context.setDefaultTimeout(15000)
  context.on('request', request => {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push({ method: request.method(), url: request.url() })
  })
  const page = await context.newPage()
  await page.goto(`${origin}/miniapp/?case=${runId}#/pages/id-photo-upload/index?size=one`, { waitUntil: 'domcontentloaded' })
  await page.getByText('上传证件照', { exact: true }).waitFor({ state: 'visible' })
  check('one_inch_upload_page_visible', await page.getByText('1寸', { exact: true }).isVisible())

  const chooserPromise = page.waitForEvent('filechooser')
  await page.locator('.frame').click()
  const chooser = await chooserPromise
  const image = syntheticPng()
  check('synthetic_png_within_limit', image.length > 0 && image.length < 2 * 1024 * 1024)
  await chooser.setFiles({ name: 'id-photo-live.png', mimeType: 'image/png', buffer: image })
  await page.getByText('本地预览，点击图片可更换', { exact: true }).waitFor({ state: 'visible' })
  check('local_preview_visible', await page.locator('.frame .preview').isVisible())

  await page.getByText('下一步', { exact: true }).click()
  await page.getByText('拼版预览', { exact: true }).waitFor({ state: 'visible' })
  check('ten_one_inch_cells', await page.locator('.cell').count() === 10)
  check('server_file_boundary_visible', await page.getByText('当前可生成服务端六寸相纸 PDF；价格、订单、支付和真实打印仍未开放。', { exact: true }).isVisible())

  const uploadResponsePromise = page.waitForResponse(response => {
    const url = new URL(response.url())
    return response.request().method() === 'POST'
      && url.origin === origin && url.pathname === '/app-api/print/miniapp/demo/id-photo-files'
  }, { timeout: 30000 })
  const readyResponsePromise = page.waitForResponse(async response => {
    const url = new URL(response.url())
    if (response.status() !== 200 || response.request().method() !== 'GET' || url.origin !== origin
      || url.pathname !== '/app-api/print/miniapp/demo/file') return false
    try {
      const body = await response.json()
      if (body?.code === 0 && body?.data?.status === 'READY' && body?.data?.ready === true) {
        readyPayload = body
        return true
      }
    } catch {}
    return false
  }, { timeout: 30000 }).catch(() => null)
  await page.getByText('生成打印文件', { exact: true }).click()
  const uploadResponse = await uploadResponsePromise
  check('upload_http_200', uploadResponse.status() === 200)
  try { uploadPayload = await uploadResponse.json() } catch { uploadPayload = { parseError: true } }
  if (uploadPayload?.data?.status === 'READY' && uploadPayload?.data?.ready === true) {
    readyPayload = uploadPayload
  } else {
    await readyResponsePromise
  }
  const fileResult = page.locator('.file-result')
  await fileResult.waitFor({ state: 'visible', timeout: 30000 })
  check('generated_file_ready_in_ui', await fileResult.getByText('状态：已就绪', { exact: true }).isVisible())
  const generatedButton = page.locator('.footer uni-button')
  check('one_generated_button', await generatedButton.count() === 1)
  check('button_locked_after_generation', await generatedButton.getAttribute('disabled') === 'true'
    && (await generatedButton.textContent())?.trim() === '打印文件已生成')
  check('upload_response_valid', uploadPayload?.code === 0 && typeof uploadPayload?.data?.id === 'string'
    && ['UPLOADED', 'PROCESSING', 'READY'].includes(uploadPayload?.data?.status))
  check('ready_response_matches_upload', readyPayload?.code === 0 && readyPayload?.data?.id === uploadPayload?.data?.id
    && readyPayload?.data?.status === 'READY' && readyPayload?.data?.ready === true)
  check('one_id_photo_post', writes.filter(item => item.url.includes('/demo/id-photo-files')).length === 1)
  check('no_quote_order_payment_or_print_task_write', writes.every(item => !/(?:quotes|orders|payments|refund|print-task)/i.test(item.url)))
  check('new_system_origin_only', writes.every(item => item.url.startsWith(origin)))

  report.generatedFileId = uploadPayload.data.id
  const screenshot = path.join(out, 'one-inch-server-file-ready.png')
  await page.screenshot({ path: screenshot, fullPage: true })
  report.screenshot = { path: path.relative(root, screenshot), sha256: crypto.createHash('sha256').update(await fs.readFile(screenshot)).digest('hex') }
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'
  report.failure = { type: error?.name || 'Error', message: String(error?.message || error).slice(0, 1000), lastCheck: checks.at(-1)?.name || 'initialization' }
  process.exitCode = 1
} finally {
  if (context) await context.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString()
  report.passed = checks.filter(item => item.passed).length
  report.failed = checks.filter(item => !item.passed).length
  report.upload = fileProjection(uploadPayload)
  report.ready = fileProjection(readyPayload)
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed,
    report: path.relative(root, target), generatedFileId: report.generatedFileId || null,
    sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
