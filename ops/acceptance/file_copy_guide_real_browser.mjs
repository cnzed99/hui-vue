import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer, request as upstream } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve } from 'node:path'

const [dir, reportPath, sourceCommit] = process.argv.slice(2)
assert.ok(dir && reportPath && sourceCommit, 'H5_DIR REPORT_PATH COMMIT required')
const dist = resolve(dir)
const fileName = 'synthetic-file-copy-guide-20261103.png'
const runtime = '/home/ubuntu/print-system/runtime/browser-tools'
Object.assign(process.env, {
  PLAYWRIGHT_BROWSERS_PATH: join(runtime, 'browsers'),
  LD_LIBRARY_PATH: join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu'),
  FONTCONFIG_FILE: join(runtime, 'fonts.conf'),
  XDG_CACHE_HOME: join(runtime, 'cache')
})
const { chromium } = createRequire(join(runtime, 'package.json'))('playwright')
const checks = []
const requests = []
const endpoint = 'http://127.0.0.1:18080'
const report = { sourceCommit, h5IndexSha256: createHash('sha256').update(await readFile(join(dist, 'index.html'))).digest('hex'),
  fixture: 'synthetic PNG of a document', environment: 'development demo backend via loopback edge with a fresh demo session',
  realWeChat: 'NOT_RUN', originalSystem: 'NOT_RUN', checks, requests }
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const server = createServer(async (req, res) => {
  const url = new URL(req.url, endpoint)
  if (url.pathname.startsWith('/app-api/print/miniapp')) {
    const headers = { ...req.headers, host: '127.0.0.1', origin: endpoint, referer: endpoint + '/miniapp/' }
    delete headers.connection
    const api = upstream(endpoint + url.pathname + url.search, { method: req.method, headers }, response => {
      requests.push({ method: req.method, path: url.pathname, status: response.statusCode })
      res.writeHead(response.statusCode, response.headers)
      response.pipe(res)
    })
    api.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end() })
    req.pipe(api)
    return
  }
  const prefix = '/miniapp/'
  const target = resolve(dist, '.' + url.pathname.slice(prefix.length - 1))
  if (!url.pathname.startsWith(prefix) || (target !== dist && !target.startsWith(dist + '/'))) return res.writeHead(404).end()
  try {
    const file = target === dist ? join(dist, 'index.html') : target
    res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' }).end(await readFile(file))
  } catch {
    if (!res.headersSent) res.writeHead(404)
    res.end()
  }
})
await new Promise(done => server.listen(0, '127.0.0.1', done))
const origin = 'http://127.0.0.1:' + server.address().port
let browser
let stage = 'browser_launch'
try {
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const context = await browser.newContext({ serviceWorkers: 'block' })
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort())
  const page = await context.newPage()
  page.setDefaultTimeout(12000)
  const pass = name => checks.push({ name, passed: true })
  const button = label => page.getByText(label, { exact: true })
  stage = 'guide_and_cancel'
  const guideRoute = '/pages/file-copy-guide/index'
  await page.goto(origin + '/miniapp/#' + guideRoute)
  await button('智能文件复印').waitFor()
  await page.locator('.guide .back').click()
  await page.waitForURL(url => url.hash.includes('/pages/scan-copy/index'))
  pass('deep_link_returns_to_scan_copy')
  await page.locator('.file-card').click()
  await page.waitForURL(url => url.hash.includes(guideRoute))
  await button('手机拍文件 拍完就打印').waitFor()
  await button('为了您的复印效果，建议将文件放置在白色的背景下拍摄').waitFor()
  await button('当前支持单张照片转 A4 PDF，自动裁边和透视矫正暂未开放。').waitFor()
  await button('开始使用').click()
  await button('取消').click()
  await page.locator('.source-sheet').waitFor({ state: 'hidden' })
  assert.equal(requests.length, 0)
  await button('开始使用').click()
  const cancelled = page.waitForEvent('filechooser')
  await button('选择图片').click()
  await (await cancelled).setFiles([])
  assert.ok(new URL(page.url()).hash.includes(guideRoute))
  assert.equal(requests.length, 0)
  pass('start_and_cancel_do_not_upload')
  stage = 'choose_image'
  const png = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 240
    canvas.height = 320
    const draw = canvas.getContext('2d')
    draw.fillStyle = '#fff'
    draw.fillRect(0, 0, 240, 320)
    draw.fillStyle = '#222'
    draw.fillRect(35, 44, 110, 8)
    for (let y = 70; y < 245; y += 24) draw.fillRect(35, y, 160, 3)
    return canvas.toDataURL('image/png').split(',')[1]
  })
  await button('开始使用').click()
  const chooser = page.waitForEvent('filechooser')
  await button('选择图片').click()
  await (await chooser).setFiles({ name: fileName, mimeType: 'image/png', buffer: Buffer.from(png, 'base64') })
  await page.waitForURL(url => url.hash.includes('/pages/image-upload/index'))
  await button('文件复印规则').waitFor()
  assert.equal(requests.some(row => row.path.endsWith('/image-files')), false)
  pass('document_copy_purpose_preserved_before_upload')
  stage = 'upload_and_convert'
  await button('上传并生成 A4 文档').click()
  await button('转换完成').waitFor({ timeout: 30000 })
  pass('real_image_to_one_page_a4_pdf')
  stage = 'settings_and_quote'
  await button('继续设置打印参数').click()
  await button(fileName.replace(/\.png$/, '.pdf')).waitFor()
  await page.getByText(/· 1 页$/).waitFor()
  await button('确认参数').click()
  await button('创建模拟订单').waitFor()
  pass('real_authoritative_quote')
  stage = 'order'
  await button('创建模拟订单').click()
  await button('模拟支付（不扣真实资金）').waitFor()
  pass('real_simulated_order_created')
  stage = 'payment'
  await button('模拟支付（不扣真实资金）').click()
  await page.locator('.uni-modal').getByText('模拟支付', { exact: true }).click()
  await button('模拟支付成功，不涉及真实资金').waitFor()
  pass('real_simulated_payment_receipt')
  await writeFile(reportPath + '.payment', fileName + '\n', { mode: 0o600 })
  stage = 'print_result'
  await page.locator('.print-receipt').getByText('模拟成功回执', { exact: true }).waitFor({ timeout: 120000 })
  pass('real_simulated_print_result_visible')
} catch {
  report.incompleteAt = stage
  process.exitCode = 1
} finally {
  await browser?.close()
  await new Promise(done => server.close(done))
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify(report, null, 2))
}
