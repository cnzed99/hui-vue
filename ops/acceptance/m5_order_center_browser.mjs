import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const backend = 'http://127.0.0.1:48080'
const runId = new Date().toISOString().replace(/[-:.]/g, '').replace('Z', 'Z-') + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `m5-order-center-browser-${runId}`)
const checks = []
let browser, apiContext, filledContext, emptyContext

function check(name, condition) {
  checks.push({ name, passed: Boolean(condition) })
  if (!condition) throw new Error(`check_failed:${name}`)
}
function makePdf() {
  const stream = Buffer.from('BT /F1 18 Tf 72 720 Td (M5 browser order center) Tj ET\n')
  const objects = [Buffer.from('<< /Type /Catalog /Pages 2 0 R >>'), Buffer.from('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),
    Buffer.from('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'),
    Buffer.concat([Buffer.from(`<< /Length ${stream.length} >>\nstream\n`), stream, Buffer.from('endstream')]),
    Buffer.from('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')]
  const parts = [Buffer.from('%PDF-1.4\n%\xe2\xe3\xcf\xd3\n', 'binary')]; const offsets = [0]; let size = parts[0].length
  objects.forEach((value, index) => { offsets.push(size); const part = Buffer.concat([Buffer.from(`${index + 1} 0 obj\n`), value, Buffer.from('\nendobj\n')]); parts.push(part); size += part.length })
  const xref = size; parts.push(Buffer.from(`xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`))
  offsets.slice(1).forEach(offset => parts.push(Buffer.from(`${String(offset).padStart(10, '0')} 00000 n \n`)))
  parts.push(Buffer.from(`trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`)); return Buffer.concat(parts)
}
async function app(method, route, { token, data, multipart, key } = {}) {
  const headers = { Origin: origin, Host: '127.0.0.1', Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (key) headers['Idempotency-Key'] = key
  const response = await apiContext.request.fetch(backend + route, { method, headers, data, multipart, timeout: 20000 })
  const body = await response.json()
  if (response.status() !== 200 || body?.code !== 0) throw new Error(`app_request_failed:${route}:${response.status()}:${body?.code}`)
  return body.data
}
async function waitFile(session, id) {
  for (let i = 0; i < 60; i++) {
    const row = await app('GET', `/app-api/print/miniapp/demo/file?id=${id}`, { token: session.accessToken })
    if (['READY', 'FAILED', 'DELETED'].includes(row.status)) return row
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('file_timeout')
}
async function pageFor(session) {
  const context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  context.setDefaultTimeout(15000)
  await context.addInitScript(sessionValue => {
    localStorage.setItem('miniapp-demo-session-v1', JSON.stringify({ type: 'object', data: sessionValue }))
  }, session)
  return { context, page: await context.newPage() }
}
async function shot(page, name, masks = []) {
  const target = path.join(out, name + '.png'); await page.screenshot({ path: target, mask: masks, maskColor: '#64748b' })
  return { name, path: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex') }
}

const report = { schemaVersion: 1, scope: 'M5 loopback browser order center: profile entry, list, detail, return, empty state',
  runId, startedAt: new Date().toISOString(), checks, screenshots: [],
  boundaries: { wechatDevice: 'NOT_RUN', realPayment: 'NOT_RUN', realPrinter: 'NOT_RUN', externalAcceptance: 'NOT_RUN' } }

try {
  await fs.mkdir(out, { recursive: false, mode: 0o700 })
  process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(runtime, 'browsers')
  process.env.LD_LIBRARY_PATH = path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
  process.env.FONTCONFIG_FILE = path.join(runtime, 'fonts.conf')
  process.env.XDG_CACHE_HOME = path.join(runtime, 'cache')
  process.env.TMPDIR = path.join(runtime, 'run-tmp')
  await fs.mkdir(process.env.TMPDIR, { recursive: true, mode: 0o700 })
  const require = createRequire(path.join(runtime, 'package.json')); const { chromium } = require('playwright')
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'], env: { ...process.env } })
  apiContext = await browser.newContext({ serviceWorkers: 'block' })
  const session = await app('POST', '/app-api/print/miniapp/demo/sessions')
  const pdf = makePdf(); const upload = await app('POST', '/app-api/print/miniapp/demo/files', { token: session.accessToken,
    key: `m5-browser-upload-${runId}`, multipart: { file: { name: `m5-browser-${runId}.pdf`, mimeType: 'application/pdf', buffer: pdf } } })
  const file = await waitFile(session, upload.id); check('browser_fixture_ready', file.status === 'READY' && file.pageCount === 1)
  const quote = await app('POST', '/app-api/print/miniapp/demo/quotes', { token: session.accessToken, key: `m5-browser-quote-${runId}`,
    data: { fileId: file.id, paper: 'A4', color: 'BLACK_WHITE', copies: 2, side: 'SIMPLEX' } })
  const order = await app('POST', '/app-api/print/miniapp/demo/orders', { token: session.accessToken, key: `m5-browser-order-${runId}`, data: { quoteId: quote.id } })
  const payment = await app('POST', '/app-api/print/miniapp/demo/payments', { token: session.accessToken, key: `m5-browser-payment-${runId}`,
    data: { orderId: order.id, mode: 'DEMO' } }); check('browser_fixture_simulated_paid', payment.state === 'SUCCEEDED')

  const filled = await pageFor(session); filledContext = filled.context
  await filled.page.goto(origin + '/miniapp/#/pages/profile/index', { waitUntil: 'domcontentloaded' })
  await filled.page.getByText('我的订单', { exact: true }).click()
  await filled.page.getByText(order.orderNo, { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  check('profile_entry_opens_nonempty_order_list', await filled.page.getByText('模拟支付成功', { exact: true }).isVisible())
  report.screenshots.push(await shot(filled.page, 'orders-nonempty', [filled.page.getByText(order.orderNo, { exact: true })]))
  await filled.page.getByText(order.orderNo, { exact: true }).click()
  await filled.page.getByText('订单详情', { exact: true }).waitFor({ state: 'visible' })
  await filled.page.getByText(`¥${order.totalAmount}`, { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  check('order_detail_renders_authoritative_amount', await filled.page.getByText(`¥${order.totalAmount}`, { exact: true }).isVisible())
  check('order_detail_keeps_simulation_boundaries', await filled.page.getByText('模拟支付不涉及真实资金', { exact: true }).isVisible()
    && await filled.page.getByText(/真实打印端尚未接入/).isVisible())
  await filled.page.getByText('刷新订单状态', { exact: true }).click()
  await filled.page.getByText(order.orderNo, { exact: true }).waitFor({ state: 'visible' })
  report.screenshots.push(await shot(filled.page, 'order-detail', [filled.page.getByText(order.orderNo, { exact: true }), filled.page.getByText(new RegExp('^收据 '))]))
  await filled.page.getByText('‹', { exact: true }).click()
  await filled.page.getByText(order.orderNo, { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  check('return_refreshes_and_restores_scoped_list', true)

  const emptySession = await app('POST', '/app-api/print/miniapp/demo/sessions')
  const empty = await pageFor(emptySession); emptyContext = empty.context
  await empty.page.goto(origin + '/miniapp/#/pages/profile/index', { waitUntil: 'domcontentloaded' })
  await empty.page.getByText('我的订单', { exact: true }).click()
  await empty.page.getByText('暂无订单哦~', { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  check('other_principal_sees_original_style_empty_state', await empty.page.getByText('已经没有更多信息了', { exact: true }).isVisible()
    && await empty.page.getByText(order.orderNo, { exact: true }).count() === 0)
  report.screenshots.push(await shot(empty.page, 'orders-empty'))
  report.status = 'PASS'; report.fixture = { principalId: session.principalId, orderId: order.id, retained: true }
} catch (error) {
  report.status = 'FAIL'; report.failure = { type: error?.name || 'Error', stage: checks.at(-1)?.name || 'initialization' }; process.exitCode = 1
} finally {
  if (filledContext) await filledContext.close().catch(() => {})
  if (emptyContext) await emptyContext.close().catch(() => {})
  if (apiContext) await apiContext.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString(); report.passed = checks.filter(item => item.passed).length; report.failed = checks.filter(item => !item.passed).length
  await fs.mkdir(out, { recursive: true, mode: 0o700 }); const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed, report: path.relative(base, target),
    sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}

