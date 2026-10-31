import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const backend = 'http://127.0.0.1:48080'
const runId = new Date().toISOString().replace(/[-:.]/g, '').replace('Z', 'Z-') + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `m4-browser-closed-loop-${runId}`)
const checks = []
let browser, adminContext, adminPage, miniContext, miniPage, adminToken
let session, fileRow, quote, order

function check(name, condition) {
  checks.push({ name, passed: Boolean(condition) })
  if (!condition) throw new Error(`check_failed:${name}`)
}

function makePdf() {
  const stream = Buffer.from('BT /F1 18 Tf 72 720 Td (M4 browser closed loop) Tj ET\n')
  const objects = [
    Buffer.from('<< /Type /Catalog /Pages 2 0 R >>'),
    Buffer.from('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),
    Buffer.from('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'),
    Buffer.concat([Buffer.from(`<< /Length ${stream.length} >>\nstream\n`), stream, Buffer.from('endstream')]),
    Buffer.from('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
  ]
  const parts = [Buffer.from('%PDF-1.4\n%\xe2\xe3\xcf\xd3\n', 'binary')]
  const offsets = [0]
  let size = parts[0].length
  objects.forEach((value, index) => {
    offsets.push(size)
    const part = Buffer.concat([Buffer.from(`${index + 1} 0 obj\n`), value, Buffer.from('\nendobj\n')])
    parts.push(part); size += part.length
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
  const response = await adminContext.request.fetch(backend + route, { method, headers, data, multipart, timeout: 20000 })
  const body = await response.json()
  if (response.status() !== 200 || body?.code !== 0) throw new Error(`app_request_failed:${route}:${response.status()}:${body?.code}`)
  return body.data
}

async function waitFile(id) {
  for (let i = 0; i < 60; i++) {
    const value = await app('GET', `/app-api/print/miniapp/demo/file?id=${id}`, { token: session.accessToken })
    if (['READY', 'FAILED', 'DELETED'].includes(value.status)) return value
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('file_timeout')
}

async function clickConfirmed(name, next) {
  adminPage.once('dialog', dialog => dialog.accept())
  await adminPage.getByRole('button', { name, exact: true }).click()
  await adminPage.getByRole('button', { name: next, exact: true }).waitFor({ state: 'visible', timeout: 15000 })
}

async function screenshot(page, name, masks = []) {
  const destination = path.join(out, name + '.png')
  await page.screenshot({ path: destination, fullPage: false, mask: masks, maskColor: '#64748b' })
  return { name, path: path.relative(base, destination), sha256: crypto.createHash('sha256').update(await fs.readFile(destination)).digest('hex') }
}

const report = {
  schemaVersion: 1,
  scope: 'M4 loopback browser closed loop: miniapp API, admin UI simulated processing, miniapp result recovery',
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  boundaries: { realPayment: 'NOT_RUN', realPrinter: 'NOT_RUN', wechatDevice: 'NOT_RUN', externalAcceptance: 'NOT_RUN' }
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
  report.browser = browser.version()
  report.playwrightVersion = require('playwright/package.json').version

  adminContext = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  adminContext.setDefaultTimeout(15000)
  adminPage = await adminContext.newPage()

  session = await app('POST', '/app-api/print/miniapp/demo/sessions')
  check('demo_session_issued', typeof session?.accessToken === 'string' && session.accessToken.length === 43)
  const pdf = makePdf()
  const uploaded = await app('POST', '/app-api/print/miniapp/demo/files', {
    token: session.accessToken,
    key: `m4-browser-upload-${runId}`,
    multipart: { file: { name: `m4-browser-${runId}.pdf`, mimeType: 'application/pdf', buffer: pdf } }
  })
  fileRow = await waitFile(uploaded.id)
  check('browser_fixture_pdf_ready', fileRow.status === 'READY' && fileRow.pageCount === 1)
  quote = await app('POST', '/app-api/print/miniapp/demo/quotes', {
    token: session.accessToken, key: `m4-browser-quote-${runId}`,
    data: { fileId: fileRow.id, paper: 'A4', color: 'BLACK_WHITE', copies: 2, side: 'SIMPLEX' }
  })
  order = await app('POST', '/app-api/print/miniapp/demo/orders', {
    token: session.accessToken, key: `miniapp-order-v1-${quote.id}`, data: { quoteId: quote.id }
  })
  const payment = await app('POST', '/app-api/print/miniapp/demo/payments', {
    token: session.accessToken, key: `miniapp-payment-v1-${order.id}`, data: { orderId: order.id, mode: 'DEMO' }
  })
  check('miniapp_order_paid_before_admin_ui', payment.state === 'SUCCEEDED')

  const credentials = JSON.parse(await fs.readFile(path.join(base, 'runtime/secrets/smoke-admin.json'), 'utf8'))
  adminPage.on('response', async response => {
    try {
      if (new URL(response.url()).pathname !== '/admin-api/system/auth/login') return
      const body = await response.json()
      if (body?.code === 0 && typeof body.data?.accessToken === 'string') adminToken = body.data.accessToken
    } catch {}
  })
  await adminPage.goto(origin + `/print/demo-orders/${order.id}?source=miniapp`, { waitUntil: 'domcontentloaded' })
  await adminPage.locator('form.login-form:visible').waitFor({ state: 'visible' })
  const form = adminPage.locator('form.login-form:visible')
  await form.getByPlaceholder('请输入租户名称', { exact: true }).fill(credentials.tenantName || 'Print Demo')
  await form.getByPlaceholder('请输入用户名', { exact: true }).fill(credentials.username)
  await form.getByPlaceholder('请输入密码', { exact: true }).fill(credentials.password)
  const remember = form.locator('input[type=checkbox]')
  if (await remember.isChecked()) await form.locator('label.el-checkbox').filter({ hasText: '记住我' }).click()
  await form.getByRole('button', { name: '登录', exact: true }).click()
  await adminPage.getByRole('heading', { name: '小程序订单处理', exact: true }).waitFor({ state: 'visible', timeout: 25000 })
  check('admin_ui_rendered_miniapp_order_detail', await adminPage.getByText(order.orderNo, { exact: true }).isVisible())
  check('admin_ui_exposes_processing_boundary', await adminPage.getByText('不会驱动真实打印机', { exact: true }).isVisible())
  await clickConfirmed('创建唯一模拟打印任务', '模拟终端领取')
  await clickConfirmed('模拟终端领取', '模拟终端受理')
  await clickConfirmed('模拟终端受理', '开始模拟执行')
  await clickConfirmed('开始模拟执行', '写入模拟成功回执')
  adminPage.once('dialog', dialog => dialog.accept())
  await adminPage.getByRole('button', { name: '写入模拟成功回执', exact: true }).click()
  await adminPage.getByText('模拟成功回执', { exact: true }).waitFor({ state: 'visible', timeout: 15000 })
  check('admin_ui_completed_simulated_print', await adminPage.getByText('该回执不代表真实出纸。', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(adminPage, 'admin-m4-success', [adminPage.locator('.print-admin__identity'), adminPage.locator('.orders-wide dd')]))

  miniContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  miniContext.setDefaultTimeout(15000)
  const draft = { schemaVersion: 1, source: 'SERVER_UPLOAD', fileName: `m4-browser-${runId}.pdf`, sizeBytes: pdf.length,
    pages: 1, fileId: fileRow.id, paper: 'A4', color: 'BLACK_WHITE', side: 'SIMPLEX', copies: 2 }
  await miniContext.addInitScript(({ session, draft, quoteId }) => {
    const stored = data => JSON.stringify({ type: 'object', data })
    localStorage.setItem('miniapp-demo-session-v1', stored(session))
    localStorage.setItem('miniapp-print-draft-v1', stored(draft))
    localStorage.setItem(`miniapp-order-pending-v1:${draft.fileId}`, stored({ quoteId, principalId: session.principalId }))
  }, { session, draft, quoteId: quote.id })
  miniPage = await miniContext.newPage()
  await miniPage.goto(origin + '/miniapp/#/pages/order-confirm/index', { waitUntil: 'domcontentloaded' })
  await miniPage.getByText('打印状态：模拟打印成功', { exact: true }).waitFor({ state: 'visible', timeout: 25000 })
  check('miniapp_reentry_restored_paid_order', await miniPage.getByText('模拟支付成功，不涉及真实资金', { exact: true }).isVisible())
  check('miniapp_reentry_restored_print_receipt', await miniPage.getByText('模拟成功回执', { exact: true }).isVisible())
  check('miniapp_retains_physical_print_boundary', await miniPage.getByText('仅为系统模拟，不代表真实出纸', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(miniPage, 'miniapp-m4-result', [miniPage.getByText(order.orderNo, { exact: true }), miniPage.getByText(new RegExp('^收据 '))]))

  await miniPage.goto(origin + '/miniapp/#/', { waitUntil: 'domcontentloaded' })
  await miniPage.getByText('云途智印', { exact: true }).waitFor({ state: 'visible' })
  check('miniapp_home_and_bottom_navigation_render', await miniPage.getByText('文档打印', { exact: true }).isVisible() &&
    await miniPage.getByText('文库', { exact: true }).isVisible() && await miniPage.getByText('我的', { exact: true }).isVisible())

  report.status = 'PASS'
  report.fixture = { principalId: session.principalId, fileId: fileRow.id, orderId: order.id, retained: true,
    reason: 'Immutable M4 action and receipt records are retained as isolated development evidence.' }
} catch (error) {
  report.status = 'FAIL'
  report.failure = { type: error?.name || 'Error', stage: checks.at(-1)?.name || 'initialization' }
  process.exitCode = 1
} finally {
  if (adminToken && adminContext) {
    try {
      const response = await adminContext.request.post(origin + '/admin-api/system/auth/logout', {
        headers: { Authorization: `Bearer ${adminToken}`, 'tenant-id': '1' }, data: {}, timeout: 10000
      })
      const body = await response.json()
      report.adminSessionLogout = body?.code === 0 && body?.data === true ? 'PASS' : 'FAIL'
    } catch { report.adminSessionLogout = 'NOT_CONFIRMED'; process.exitCode = 1 }
  }
  if (miniContext) await miniContext.close().catch(() => {})
  if (adminContext) await adminContext.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString()
  report.passed = checks.filter(item => item.passed).length
  report.failed = checks.filter(item => !item.passed).length
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed,
    report: path.relative(base, path.join(out, 'report.json')), failure: report.failure || null }))
}
