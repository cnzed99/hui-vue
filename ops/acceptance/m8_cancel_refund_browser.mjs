import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const backend = 'http://127.0.0.1:48080'
const runId = new Date().toISOString().replace(/[-:.]/g, '').replace('Z', 'Z-') + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `m8-cancel-refund-browser-${runId}`)
const checks = []
let browser, apiContext, miniContext, adminContext, adminPage, adminToken

function check(name, condition) {
  checks.push({ name, passed: Boolean(condition) })
  if (!condition) throw new Error(`check_failed:${name}`)
}
function makePdf() {
  const stream = Buffer.from('BT /F1 18 Tf 72 720 Td (M8 browser cancellation) Tj ET\n')
  const objects = [Buffer.from('<< /Type /Catalog /Pages 2 0 R >>'), Buffer.from('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),
    Buffer.from('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'),
    Buffer.concat([Buffer.from(`<< /Length ${stream.length} >>\nstream\n`), stream, Buffer.from('endstream')]),
    Buffer.from('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')]
  const parts = [Buffer.from('%PDF-1.4\n%\xe2\xe3\xcf\xd3\n', 'binary')]
  const offsets = [0]; let size = parts[0].length
  objects.forEach((value, index) => { offsets.push(size); const item = Buffer.concat([Buffer.from(`${index + 1} 0 obj\n`), value, Buffer.from('\nendobj\n')]); parts.push(item); size += item.length })
  const xref = size; parts.push(Buffer.from(`xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`))
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
  for (let i = 0; i < 60; i++) {
    const value = await app('GET', `/app-api/print/miniapp/demo/file?id=${id}`, { token })
    if (['READY', 'FAILED', 'DELETED'].includes(value.status)) return value
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('file_timeout')
}
async function screenshot(page, name, masks = []) {
  const destination = path.join(out, name + '.png')
  await page.screenshot({ path: destination, fullPage: false, mask: masks, maskColor: '#64748b' })
  return { name, path: path.relative(base, destination), sha256: crypto.createHash('sha256').update(await fs.readFile(destination)).digest('hex') }
}

const report = { schemaVersion: 1, scope: 'M8 deployed browser verification: cancelled/refunded order in miniapp and admin', runId,
  startedAt: new Date().toISOString(), checks, screenshots: [],
  boundaries: { realPayment: 'NOT_RUN', realRefund: 'NOT_RUN', realPrinter: 'NOT_RUN', wechatDevice: 'NOT_RUN', externalAcceptance: 'NOT_RUN' } }

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
  report.browser = browser.version(); report.playwrightVersion = require('playwright/package.json').version
  apiContext = await browser.newContext({ serviceWorkers: 'block' })

  const session = await app('POST', '/app-api/print/miniapp/demo/sessions')
  check('session_issued', typeof session?.accessToken === 'string' && session.accessToken.length > 30)
  const pdf = makePdf()
  const uploaded = await app('POST', '/app-api/print/miniapp/demo/files', { token: session.accessToken,
    key: `m8-browser-file-${runId}`, multipart: { file: { name: `m8-browser-${runId}.pdf`, mimeType: 'application/pdf', buffer: pdf } } })
  const file = await waitFile(session.accessToken, uploaded.id)
  check('fixture_file_ready', file.status === 'READY' && file.pageCount === 1)
  const quote = await app('POST', '/app-api/print/miniapp/demo/quotes', { token: session.accessToken, key: `m8-browser-quote-${runId}`,
    data: { fileId: file.id, paper: 'A4', color: 'BLACK_WHITE', copies: 2, side: 'SIMPLEX' } })
  const order = await app('POST', '/app-api/print/miniapp/demo/orders', { token: session.accessToken, key: `m8-browser-order-${runId}`, data: { quoteId: quote.id } })
  const payment = await app('POST', '/app-api/print/miniapp/demo/payments', { token: session.accessToken, key: `m8-browser-payment-${runId}`, data: { orderId: order.id, mode: 'DEMO' } })
  check('fixture_simulated_paid', payment.state === 'SUCCEEDED')
  const cancellation = await app('POST', '/app-api/print/miniapp/demo/cancellations', { token: session.accessToken, key: `m8-browser-cancel-${runId}`,
    data: { orderId: order.id, expectedVersion: 2 } })
  check('fixture_cancelled_and_refunded', cancellation.originalResult.outcome === 'CANCELLED_REFUNDED' && cancellation.refundReceipt?.amount === order.totalAmount)

  miniContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  miniContext.setDefaultTimeout(20000)
  await miniContext.addInitScript(value => localStorage.setItem('miniapp-demo-session-v1', JSON.stringify({ type: 'object', data: value })), session)
  const miniPage = await miniContext.newPage()
  await miniPage.goto(origin + '/miniapp/#/pages/profile/index', { waitUntil: 'domcontentloaded' })
  await miniPage.getByText('我的订单', { exact: true }).click()
  await miniPage.getByText(order.orderNo, { exact: true }).waitFor({ state: 'visible' })
  check('miniapp_list_shows_refund', await miniPage.getByText('已全额模拟退款', { exact: true }).isVisible())
  await miniPage.getByText(order.orderNo, { exact: true }).click()
  await miniPage.getByText('订单已取消', { exact: true }).first().waitFor({ state: 'visible' })
  check('miniapp_detail_shows_cancelled_state', await miniPage.getByText('模拟支付成功 · 已全额模拟退款', { exact: true }).isVisible())
  const refundBoundary = miniPage.getByText('订单已取消，全额模拟退款已记录；不涉及真实资金。', { exact: true })
  const refundReceipt = miniPage.getByText(new RegExp('^SIMULATED 退款收据'))
  await refundBoundary.waitFor({ state: 'visible', timeout: 20000 })
  await refundReceipt.waitFor({ state: 'visible', timeout: 20000 })
  check('miniapp_panel_shows_simulated_refund_boundary', await refundBoundary.isVisible() && await refundReceipt.isVisible())
  report.screenshots.push(await screenshot(miniPage, 'miniapp-cancel-refund', [miniPage.getByText(order.orderNo, { exact: true }), miniPage.getByText(new RegExp('^收据 '))]))

  adminContext = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  adminContext.setDefaultTimeout(20000); adminPage = await adminContext.newPage()
  const credentialsPath = path.join(base, 'runtime/secrets/smoke-admin.json')
  const info = await fs.lstat(credentialsPath)
  check('credential_file_private', info.isFile() && !info.isSymbolicLink() && info.uid === process.getuid() && (info.mode & 0o777) === 0o600)
  const credentials = JSON.parse(await fs.readFile(credentialsPath, 'utf8'))
  adminPage.on('response', async response => { try { if (new URL(response.url()).pathname !== '/admin-api/system/auth/login') return; const body = await response.json(); if (body?.code === 0 && typeof body.data?.accessToken === 'string') adminToken = body.data.accessToken } catch {} })
  await adminPage.goto(origin + `/print/demo-orders/${order.id}?source=miniapp`, { waitUntil: 'domcontentloaded' })
  const form = adminPage.locator('form.login-form:visible'); await form.waitFor({ state: 'visible' })
  await form.getByPlaceholder('请输入租户名称', { exact: true }).fill(credentials.tenantName || 'Print Demo')
  await form.getByPlaceholder('请输入用户名', { exact: true }).fill(credentials.username)
  await form.getByPlaceholder('请输入密码', { exact: true }).fill(credentials.password)
  const remember = form.locator('input[type=checkbox]')
  if (await remember.isChecked()) await form.locator('label.el-checkbox').filter({ hasText: '记住我' }).click()
  await form.getByRole('button', { name: '登录', exact: true }).click()
  await adminPage.getByRole('heading', { name: '小程序订单处理', exact: true }).waitFor({ state: 'visible', timeout: 25000 })
  await adminPage.getByText(order.orderNo, { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  const cancellationHeading = adminPage.getByRole('heading', { name: '取消与人工核实记录', exact: true })
  await cancellationHeading.waitFor({ state: 'visible', timeout: 20000 })
  check('admin_detail_renders_cancel_record', await cancellationHeading.isVisible())
  check('admin_detail_renders_refund_receipt', await adminPage.getByRole('heading', { name: '全额模拟退款收据', exact: true }).isVisible()
    && await adminPage.getByText('模拟退款已记录，不涉及真实资金，不代表微信退款到账。', { exact: true }).isVisible())
  check('admin_prevents_cancelled_print_task', await adminPage.getByText('订单已取消，不能创建或推进打印任务。', { exact: true }).isVisible()
    && await adminPage.getByRole('button', { name: '创建唯一模拟打印任务', exact: true }).count() === 0)
  report.screenshots.push(await screenshot(adminPage, 'admin-cancel-refund', [adminPage.locator('.print-admin__identity'), adminPage.locator('.orders-wide dd')]))
  report.status = 'PASS'; report.fixture = { orderId: order.id, retained: true, reason: 'Immutable M8 cancellation and refund records are retained as development evidence.' }
} catch (error) {
  report.status = 'FAIL'; report.failure = { type: error?.name || 'Error', stage: checks.at(-1)?.name || 'initialization' }; process.exitCode = 1
} finally {
  if (adminToken && adminContext) {
    try { const response = await adminContext.request.post(origin + '/admin-api/system/auth/logout', { headers: { Authorization: `Bearer ${adminToken}`, 'tenant-id': '1' }, data: {}, timeout: 10000 }); const body = await response.json(); report.adminSessionLogout = body?.code === 0 && body?.data === true ? 'PASS' : 'FAIL' }
    catch { report.adminSessionLogout = 'NOT_CONFIRMED'; process.exitCode = 1 }
  }
  if (adminContext) await adminContext.close().catch(() => {})
  if (miniContext) await miniContext.close().catch(() => {})
  if (apiContext) await apiContext.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString(); report.passed = checks.filter(item => item.passed).length; report.failed = checks.filter(item => !item.passed).length
  await fs.mkdir(out, { recursive: true, mode: 0o700 }); const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed, report: path.relative(base, target),
    sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
