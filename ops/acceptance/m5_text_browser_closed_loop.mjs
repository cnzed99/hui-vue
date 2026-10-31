import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const engineering = path.join(base, 'runtime/worktrees/m5-text-engineering-20261031')
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const runId = new Date().toISOString().replace(/[-:.]/g, '').replace('Z', 'Z-') + crypto.randomBytes(4).toString('hex')
const out = path.join(engineering, 'evidence/miniapp', `m5-text-browser-closed-loop-${runId}`)
const checks = []
let browser, adminContext, adminPage, miniContext, miniPage, adminToken
let session, fileRow, quote, order, payment, printTask

function check(name, condition) {
  checks.push({ name, passed: Boolean(condition) })
  if (!condition) throw new Error(`check_failed:${name}`)
}

async function waitCaptured(name, getter, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const value = getter()
    if (value) return value
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`capture_timeout:${name}`)
}

async function clickConfirmed(name, next) {
  adminPage.once('dialog', dialog => dialog.accept())
  await adminPage.getByRole('button', { name, exact: true }).click()
  await adminPage.getByRole('button', { name: next, exact: true }).waitFor({ state: 'visible', timeout: 15000 })
}

async function screenshot(page, name, masks = []) {
  const destination = path.join(out, name + '.png')
  await page.screenshot({ path: destination, fullPage: false, mask: masks, maskColor: '#64748b' })
  return {
    name,
    path: path.relative(engineering, destination),
    sha256: crypto.createHash('sha256').update(await fs.readFile(destination)).digest('hex')
  }
}

const report = {
  schemaVersion: 1,
  scope: 'M5 browser closed loop: miniapp text UI to admin simulated processing and miniapp result',
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

  miniContext = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  miniContext.setDefaultTimeout(15000)
  miniPage = await miniContext.newPage()
  miniPage.on('response', async response => {
    try {
      const pathname = new URL(response.url()).pathname
      if (!pathname.startsWith('/app-api/print/miniapp/demo/')) return
      const body = await response.json()
      if (body?.code !== 0 || !body.data) return
      if (pathname.endsWith('/sessions')) session = body.data
      else if (pathname.endsWith('/text-files') || pathname.endsWith('/file')) fileRow = body.data
      else if (pathname.endsWith('/quotes') || pathname.endsWith('/quote')) quote = body.data
      else if (pathname.endsWith('/orders') || pathname.endsWith('/order')) order = body.data
      else if (pathname.endsWith('/payments') || pathname.endsWith('/payment')) payment = body.data
      else if (pathname.endsWith('/print-result')) printTask = body.data
    } catch {}
  })

  const input = `浏览器文字打印闭环 ${runId}\n第二行 Browser Print 123`
  const inputSha256 = crypto.createHash('sha256').update(input).digest('hex')
  await miniPage.goto(origin + '/miniapp/#/pages/text-editor/index', { waitUntil: 'domcontentloaded' })
  await miniPage.getByText('自定义文字', { exact: true }).waitFor({ state: 'visible' })
  await miniPage.locator('textarea').fill(input)
  check('miniapp_text_editor_accepts_input', await miniPage.getByText(/文字字数：[1-9][0-9]*，字数限制在1万字以内/).isVisible())
  await miniPage.locator('.submit').click()
  await miniPage.getByText('打印设置', { exact: true }).waitFor({ state: 'visible', timeout: 25000 })
  await waitCaptured('session', () => session)
  await waitCaptured('ready_file', () => fileRow?.status === 'READY' ? fileRow : null)
  check('miniapp_text_generated_ready_pdf', fileRow.ready === true && fileRow.pageCount >= 1 && fileRow.pageCount <= 20)
  check('miniapp_settings_shows_server_parse', await miniPage.getByText('服务端解析完成', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(miniPage, 'miniapp-text-settings'))

  await miniPage.getByText('双面打印', { exact: true }).click()
  await miniPage.locator('.stepper').getByText('＋', { exact: true }).click()
  await miniPage.locator('.primary').click()
  await miniPage.getByText('确认订单', { exact: true }).waitFor({ state: 'visible' })
  await miniPage.getByText('创建模拟订单', { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  await waitCaptured('quote', () => quote)
  check('miniapp_received_authoritative_quote', quote.status === 'VALID' && quote.snapshot.copies === '2' && quote.snapshot.side === 'DUPLEX')
  await miniPage.getByText('创建模拟订单', { exact: true }).click()
  await waitCaptured('order', () => order)
  await miniPage.getByText(order.orderNo, { exact: true }).waitFor({ state: 'visible' })
  check('miniapp_created_simulated_order', order.source === 'MINIAPP_DEMO' && order.simulation === true)
  await miniPage.getByText('模拟支付（不扣真实资金）', { exact: true }).click()
  await miniPage.getByText('确认模拟支付', { exact: true }).waitFor({ state: 'visible' })
  await miniPage.getByText('模拟支付', { exact: true }).last().click()
  await miniPage.getByText('模拟支付成功，不涉及真实资金', { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  await waitCaptured('payment', () => payment)
  check('miniapp_completed_simulated_payment', payment.state === 'SUCCEEDED' && payment.simulation === true)
  report.screenshots.push(await screenshot(miniPage, 'miniapp-text-paid', [
    miniPage.getByText(order.orderNo, { exact: true }), miniPage.getByText(new RegExp('^收据 '))
  ]))

  adminContext = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  adminContext.setDefaultTimeout(15000)
  adminPage = await adminContext.newPage()
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
  check('admin_ui_loaded_text_order', await adminPage.getByText(order.orderNo, { exact: true }).isVisible())
  check('admin_ui_preserves_real_printer_boundary', await adminPage.getByText('不会驱动真实打印机', { exact: true }).isVisible())
  await clickConfirmed('创建唯一模拟打印任务', '模拟终端领取')
  await clickConfirmed('模拟终端领取', '模拟终端受理')
  await clickConfirmed('模拟终端受理', '开始模拟执行')
  await clickConfirmed('开始模拟执行', '写入模拟成功回执')
  adminPage.once('dialog', dialog => dialog.accept())
  await adminPage.getByRole('button', { name: '写入模拟成功回执', exact: true }).click()
  await adminPage.getByText('模拟成功回执', { exact: true }).waitFor({ state: 'visible', timeout: 15000 })
  check('admin_ui_completed_simulated_print', await adminPage.getByText('该回执不代表真实出纸。', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(adminPage, 'admin-text-success', [
    adminPage.locator('.print-admin__identity'), adminPage.locator('.orders-wide dd')
  ]))

  await miniPage.getByText(/刷新模拟打印进度|重试查询模拟打印进度/).click()
  await miniPage.getByText('打印状态：模拟打印成功', { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  await waitCaptured('print_result', () => printTask?.receipt ? printTask : null)
  check('miniapp_ui_received_simulated_receipt', printTask.state === 'SUCCEEDED' && printTask.receipt.simulation === true)
  check('miniapp_ui_preserves_physical_print_boundary', await miniPage.getByText('仅为系统模拟，不代表真实出纸', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(miniPage, 'miniapp-text-result', [
    miniPage.getByText(order.orderNo, { exact: true }), miniPage.getByText(new RegExp('^收据 '))
  ]))

  report.status = 'PASS'
  report.fixture = {
    principalId: session.principalId,
    fileId: fileRow.id,
    orderId: order.id,
    taskId: printTask.id,
    inputSha256,
    retained: true,
    reason: 'Immutable simulated browser-loop task and receipt retained as isolated development evidence.'
  }
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
  if (adminContext) await adminContext.close().catch(() => {})
  if (miniContext) await miniContext.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  report.finishedAt = new Date().toISOString()
  report.passed = checks.filter(item => item.passed).length
  report.failed = checks.filter(item => !item.passed).length
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({
    status: report.status,
    passed: report.passed,
    failed: report.failed,
    report: path.relative(engineering, path.join(out, 'report.json')),
    failure: report.failure || null
  }))
}
