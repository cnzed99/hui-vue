import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const backend = 'http://127.0.0.1:48080'
const expectedRelease = '/var/www/print-system/releases/miniapp-id-photo-server-file-20261103-69d93c1'
const expectedIndexSha256 = '69d93c169fed9249f57b24e016f6d9add92a37273afe855be81bc7781b7da968'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `member-activity-${runId}`)
const checks = []
let browser
let apiContext
let miniContext
let emptyContext
let failureContext
let malformedTimeContext
let mismatchedRefundContext

function check(name, condition, detail = undefined) {
  const item = { name, passed: Boolean(condition) }
  if (detail !== undefined) item.detail = detail
  checks.push(item)
  if (!condition) throw new Error(`check_failed:${name}`)
}

function makePdf() {
  const stream = Buffer.from('BT /F1 18 Tf 72 720 Td (Member activity fixture) Tj ET\n')
  const objects = [Buffer.from('<< /Type /Catalog /Pages 2 0 R >>'), Buffer.from('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),
    Buffer.from('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'),
    Buffer.concat([Buffer.from(`<< /Length ${stream.length} >>\nstream\n`), stream, Buffer.from('endstream')]),
    Buffer.from('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')]
  const parts = [Buffer.from('%PDF-1.4\n%\xe2\xe3\xcf\xd3\n', 'binary')]
  const offsets = [0]
  let size = parts[0].length
  objects.forEach((value, index) => {
    offsets.push(size)
    const item = Buffer.concat([Buffer.from(`${index + 1} 0 obj\n`), value, Buffer.from('\nendobj\n')])
    parts.push(item)
    size += item.length
  })
  const xref = size
  parts.push(Buffer.from(`xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`))
  offsets.slice(1).forEach(offset => parts.push(Buffer.from(`${String(offset).padStart(10, '0')} 00000 n \n`)))
  parts.push(Buffer.from(`trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`))
  return Buffer.concat(parts)
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

async function waitFile(token, id) {
  for (let i = 0; i < 60; i += 1) {
    const value = await app('GET', `/app-api/print/miniapp/demo/file?id=${id}`, { token })
    if (['READY', 'FAILED', 'DELETED'].includes(value.status)) return value
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('file_timeout')
}

async function shot(page, name) {
  const target = path.join(out, `${name}.png`)
  await page.screenshot({ path: target })
  return { name, path: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex') }
}

async function bindSession(context, session) {
  await context.addInitScript(value => localStorage.setItem('miniapp-demo-session-v1', JSON.stringify({ type: 'object', data: value })), session)
}

const report = {
  schemaVersion: 1,
  scope: 'member card simulated activity using existing scoped reads',
  sourceCommit: 'ff741ea',
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  boundaries: {
    balance: 'NOT_IMPLEMENTED',
    memberLevel: 'NOT_IMPLEMENTED',
    discount: 'NOT_IMPLEMENTED',
    realPayment: 'NOT_RUN',
    realRefund: 'NOT_RUN',
    wechatDevice: 'NOT_RUN',
    externalAcceptance: 'NOT_RUN'
  }
}

try {
  await fs.mkdir(out, { recursive: false, mode: 0o700 })
  const activeRelease = await fs.realpath('/var/www/print-system/miniapp')
  const indexSha256 = crypto.createHash('sha256').update(await fs.readFile('/var/www/print-system/miniapp/index.html')).digest('hex')
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
  apiContext = await browser.newContext({ serviceWorkers: 'block' })

  const session = await app('POST', '/app-api/print/miniapp/demo/sessions')
  const uploaded = await app('POST', '/app-api/print/miniapp/demo/files', {
    token: session.accessToken,
    key: `member-activity-file-${runId}`,
    multipart: { file: { name: `member-activity-${runId}.pdf`, mimeType: 'application/pdf', buffer: makePdf() } }
  })
  const file = await waitFile(session.accessToken, uploaded.id)
  check('fixture_file_ready', file.status === 'READY' && file.pageCount === 1)
  const quote = await app('POST', '/app-api/print/miniapp/demo/quotes', {
    token: session.accessToken,
    key: `member-activity-quote-${runId}`,
    data: { fileId: file.id, paper: 'A4', color: 'BLACK_WHITE', copies: 2, side: 'SIMPLEX' }
  })
  const order = await app('POST', '/app-api/print/miniapp/demo/orders', {
    token: session.accessToken,
    key: `member-activity-order-${runId}`,
    data: { quoteId: quote.id }
  })
  const payment = await app('POST', '/app-api/print/miniapp/demo/payments', {
    token: session.accessToken,
    key: `member-activity-payment-${runId}`,
    data: { orderId: order.id, mode: 'DEMO' }
  })
  const cancellation = await app('POST', '/app-api/print/miniapp/demo/cancellations', {
    token: session.accessToken,
    key: `member-activity-cancel-${runId}`,
    data: { orderId: order.id, expectedVersion: 2 }
  })
  check('fixture_has_payment_and_refund', payment.state === 'SUCCEEDED'
    && cancellation.originalResult.outcome === 'CANCELLED_REFUNDED'
    && cancellation.refundReceipt?.amount === order.totalAmount)

  miniContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  miniContext.setDefaultTimeout(20000)
  await bindSession(miniContext, session)
  const page = await miniContext.newPage()
  await page.goto(`${origin}/miniapp/#/pages/member-card/index`, { waitUntil: 'domcontentloaded' })
  check('member_card_does_not_invent_balance', await page.getByText('余额能力：', { exact: true }).isVisible()
    && await page.getByText('未开通', { exact: true }).isVisible()
    && await page.getByText('0元', { exact: true }).count() === 0)
  await page.locator('.detail-link').click()
  await page.getByText(order.orderNo, { exact: true }).first().waitFor({ state: 'visible' })
  const rows = page.locator('.item')
  check('activity_shows_verified_payment_and_refund', await rows.count() === 2
    && await rows.nth(0).getByText('全额模拟退款', { exact: true }).isVisible()
    && await rows.nth(1).getByText('模拟支付', { exact: true }).isVisible()
    && await page.getByText(`+¥${order.totalAmount}`, { exact: true }).isVisible()
    && await page.getByText(`-¥${order.totalAmount}`, { exact: true }).isVisible())
  check('activity_preserves_money_boundary', await page.getByText('不代表会员余额或真实资金', { exact: false }).isVisible())
  report.screenshots.push(await shot(page, 'paid-and-refunded'))

  emptyContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  emptyContext.setDefaultTimeout(20000)
  const emptyPage = await emptyContext.newPage()
  await emptyPage.goto(`${origin}/miniapp/#/pages/member-activity/index`, { waitUntil: 'domcontentloaded' })
  await emptyPage.getByText('暂无模拟消费记录', { exact: true }).waitFor({ state: 'visible' })
  check('other_principal_has_empty_activity', await emptyPage.locator('.item').count() === 0)

  failureContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  failureContext.setDefaultTimeout(20000)
  await bindSession(failureContext, session)
  await failureContext.route('**/app-api/print/miniapp/demo/payment?**', route => route.abort('failed'))
  const failurePage = await failureContext.newPage()
  await failurePage.goto(`${origin}/miniapp/#/pages/member-activity/index`, { waitUntil: 'domcontentloaded' })
  await failurePage.getByText('模拟明细暂时无法核实', { exact: true }).waitFor({ state: 'visible' })
  check('receipt_failure_is_not_rendered_as_empty_or_zero', await failurePage.locator('.item').count() === 0
    && await failurePage.getByText('暂无模拟消费记录', { exact: true }).count() === 0)

  malformedTimeContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  malformedTimeContext.setDefaultTimeout(20000)
  await bindSession(malformedTimeContext, session)
  await malformedTimeContext.route('**/app-api/print/miniapp/demo/payment?**', async route => {
    const response = await route.fetch()
    const envelope = await response.json()
    if (envelope?.data) envelope.data.createdAtMs = 'invalid-time'
    await route.fulfill({ response, json: envelope })
  })
  const malformedTimePage = await malformedTimeContext.newPage()
  await malformedTimePage.goto(`${origin}/miniapp/#/pages/member-activity/index`, { waitUntil: 'domcontentloaded' })
  await malformedTimePage.getByText('模拟明细暂时无法核实', { exact: true }).waitFor({ state: 'visible' })
  check('malformed_payment_time_fails_closed', await malformedTimePage.locator('.item').count() === 0)

  mismatchedRefundContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  mismatchedRefundContext.setDefaultTimeout(20000)
  await bindSession(mismatchedRefundContext, session)
  await mismatchedRefundContext.route('**/app-api/print/miniapp/demo/cancellation?**', async route => {
    const response = await route.fetch()
    const envelope = await response.json()
    const refund = envelope?.data?.cancellation?.refundReceipt
    if (refund) refund.paymentReceiptId = '00000000-0000-4000-8000-000000000000'
    await route.fulfill({ response, json: envelope })
  })
  const mismatchedRefundPage = await mismatchedRefundContext.newPage()
  await mismatchedRefundPage.goto(`${origin}/miniapp/#/pages/member-activity/index`, { waitUntil: 'domcontentloaded' })
  await mismatchedRefundPage.getByText('模拟明细暂时无法核实', { exact: true }).waitFor({ state: 'visible' })
  check('mismatched_refund_receipt_fails_closed', await mismatchedRefundPage.locator('.item').count() === 0)

  report.status = 'PASS'
  report.fixture = { orderId: order.id, retained: true, reason: 'Immutable simulated payment and refund retained as isolated development evidence.' }
} catch (error) {
  report.status = 'FAIL'
  report.failure = { type: error?.name || 'Error', message: String(error?.message || error).slice(0, 500), lastCheck: checks.at(-1)?.name || 'initialization' }
  process.exitCode = 1
} finally {
  if (mismatchedRefundContext) await mismatchedRefundContext.close().catch(() => {})
  if (malformedTimeContext) await malformedTimeContext.close().catch(() => {})
  if (failureContext) await failureContext.close().catch(() => {})
  if (emptyContext) await emptyContext.close().catch(() => {})
  if (miniContext) await miniContext.close().catch(() => {})
  if (apiContext) await apiContext.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString()
  report.passed = checks.filter(item => item.passed).length
  report.failed = checks.filter(item => !item.passed).length
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed,
    report: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
