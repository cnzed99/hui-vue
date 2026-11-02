import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const engineering = path.join(base, 'runtime/worktrees/m5-text-engineering-20261031')
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const runId = new Date().toISOString().replace(/[-:.]/g, '').replace('Z', 'Z-') + crypto.randomBytes(4).toString('hex')
const out = path.join(engineering, 'evidence/miniapp', `m6-library-browser-closed-loop-${runId}`)
const checks = []
let browser, adminContext, adminPage, miniContext, miniPage, adminToken
let session, fileRow, quote, order, payment, printTask
let stage = 'initialization'

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
  scope: 'M6 browser closed loop: miniapp library detail to admin simulated processing and miniapp result',
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  boundaries: { realPayment: 'NOT_RUN', realPrinter: 'NOT_RUN', wechatDevice: 'NOT_RUN', favorite: 'NOT_RUN', realShare: 'NOT_RUN', storeScopeFilter: 'NOT_RUN', externalAcceptance: 'NOT_RUN' }
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
      else if (pathname.endsWith('/library-files') || pathname.endsWith('/file')) fileRow = body.data
      else if (pathname.endsWith('/quotes') || pathname.endsWith('/quote')) quote = body.data
      else if (pathname.endsWith('/orders') || pathname.endsWith('/order')) order = body.data
      else if (pathname.endsWith('/payments') || pathname.endsWith('/payment')) payment = body.data
      else if (pathname.endsWith('/print-result')) printTask = body.data
    } catch {}
  })

  const entryId = 'demo-weekly-plan'
  const contentVersion = 'v1'
  stage = 'library_initial_load'
  await miniPage.goto(origin + '/miniapp/#/pages/library/index', { waitUntil: 'domcontentloaded' })
  await miniPage.locator('.bar-title').getByText('文库', { exact: true }).waitFor({ state: 'visible' })
  await miniPage.getByText('每周计划模板', { exact: true }).waitFor({ state: 'visible', timeout: 15000 })
  check('miniapp_library_lists_original_entries', await miniPage.locator('.document-card').count() === 3)
  stage = 'library_fill'
  await miniPage.locator('.search-input input').fill('每周')
  stage = 'library_search_click'
  const [searchResponse] = await Promise.all([
    miniPage.waitForResponse(response => {
      const url = new URL(response.url())
      return response.request().method() === 'GET'
        && url.pathname === '/app-api/print/miniapp/demo/library'
        && url.searchParams.get('keyword') === '每周'
    }, { timeout: 15000 }),
    miniPage.locator('.search-button').click({ timeout: 15000 })
  ])
  stage = 'library_search_response'
  const searchBody = await searchResponse.json()
  check('miniapp_library_search_response_is_exact', searchResponse.status() === 200 && searchBody?.code === 0
    && searchBody.data?.items?.length === 1 && searchBody.data.items[0]?.id === entryId)
  stage = 'library_search_render'
  await miniPage.waitForFunction(() => document.querySelectorAll('.document-card').length === 1, null, { timeout: 15000 })
  check('miniapp_library_server_search_filters_list', await miniPage.locator('.document-card').count() === 1)
  stage = 'library_detail_request'
  const [detailResponse] = await Promise.all([
    miniPage.waitForResponse(response => {
      const url = new URL(response.url())
      return response.request().method() === 'GET'
        && url.origin === origin
        && url.pathname === '/app-api/print/miniapp/demo/library-entry'
        && url.searchParams.get('id') === entryId
    }, { timeout: 15000 }),
    miniPage.getByText('每周计划模板', { exact: true }).click()
  ])
  stage = 'library_detail_response'
  const detailBody = await detailResponse.json()
  const previewPages = detailBody?.data?.previewPages
  report.detailDiagnostics = {
    httpStatus: detailResponse.status(),
    businessCode: detailBody?.code ?? null,
    entryId: detailBody?.data?.entry?.id ?? null,
    contentVersion: detailBody?.data?.entry?.contentVersion ?? null,
    entryPages: detailBody?.data?.entry?.pages ?? null,
    previewPageCount: Array.isArray(previewPages) ? previewPages.length : null,
    previewPageNumbers: Array.isArray(previewPages) ? previewPages.map(page => page?.pageNo ?? null) : []
  }
  check('miniapp_library_detail_response_is_exact', detailResponse.status() === 200 && detailBody?.code === 0
    && detailBody.data?.entry?.id === entryId && detailBody.data.entry.contentVersion === contentVersion
    && detailBody.data.entry.pages === 3 && Array.isArray(previewPages) && previewPages.length === 3
    && previewPages.every((page, index) => page?.pageNo === index + 1))
  stage = 'library_detail_render'
  await miniPage.waitForFunction(() => document.querySelectorAll('.paper').length === 3, null, { timeout: 15000 })
  report.detailDiagnostics.paperCount = await miniPage.locator('.paper').count()
  check('miniapp_library_detail_has_three_preview_pages', report.detailDiagnostics.paperCount === 3)
  await miniPage.getByText('第 3 页', { exact: true }).waitFor({ state: 'visible', timeout: 15000 })
  check('miniapp_library_detail_shows_page_badges', await miniPage.getByText('第 3 页', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(miniPage, 'miniapp-library-preview'))
  await miniPage.getByText('收藏', { exact: true }).click()
  await miniPage.getByText('收藏将在微信身份接入后开放', { exact: true }).waitFor({ state: 'visible' })
  await miniPage.getByText('分享', { exact: true }).click()
  await miniPage.getByText('微信分享待 AppID 配置及真机验证', { exact: true }).waitFor({ state: 'visible' })
  check('miniapp_library_reserved_actions_are_explicit', true)
  stage = 'library_print'
  await miniPage.locator('.print-button').click()
  await miniPage.getByText('打印设置', { exact: true }).waitFor({ state: 'visible', timeout: 25000 })
  await waitCaptured('session', () => session)
  await waitCaptured('ready_file', () => fileRow?.status === 'READY' ? fileRow : null)
  check('miniapp_library_generated_ready_pdf', fileRow.ready === true && fileRow.pageCount === 3 && fileRow.displayName === '每周计划模板.pdf')
  check('miniapp_settings_shows_server_parse', await miniPage.getByText('服务端解析完成', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(miniPage, 'miniapp-library-settings'))

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
  report.screenshots.push(await screenshot(miniPage, 'miniapp-library-paid', [
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
  check('admin_ui_loaded_library_order', await adminPage.getByText(order.orderNo, { exact: true }).isVisible())
  check('admin_ui_preserves_real_printer_boundary', await adminPage.getByText('不会驱动真实打印机', { exact: true }).isVisible())
  await clickConfirmed('创建唯一模拟打印任务', '模拟终端领取')
  await clickConfirmed('模拟终端领取', '模拟终端受理')
  await clickConfirmed('模拟终端受理', '开始模拟执行')
  await clickConfirmed('开始模拟执行', '写入模拟成功回执')
  adminPage.once('dialog', dialog => dialog.accept())
  await adminPage.getByRole('button', { name: '写入模拟成功回执', exact: true }).click()
  await adminPage.getByText('模拟成功回执', { exact: true }).waitFor({ state: 'visible', timeout: 15000 })
  check('admin_ui_completed_simulated_print', await adminPage.getByText('该回执不代表真实出纸。', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(adminPage, 'admin-library-success', [
    adminPage.locator('.print-admin__identity'), adminPage.locator('.orders-wide dd')
  ]))

  await miniPage.getByText(/刷新模拟打印进度|重试查询模拟打印进度/).click()
  await miniPage.getByText('打印状态：模拟打印成功', { exact: true }).waitFor({ state: 'visible', timeout: 20000 })
  await waitCaptured('print_result', () => printTask?.receipt ? printTask : null)
  check('miniapp_ui_received_simulated_receipt', printTask.state === 'SUCCEEDED' && printTask.receipt.simulation === true)
  check('miniapp_ui_preserves_physical_print_boundary', await miniPage.getByText('仅为系统模拟，不代表真实出纸', { exact: true }).isVisible())
  report.screenshots.push(await screenshot(miniPage, 'miniapp-library-result', [
    miniPage.getByText(order.orderNo, { exact: true }), miniPage.getByText(new RegExp('^收据 '))
  ]))

  report.status = 'PASS'
  report.fixture = {
    principalId: session.principalId,
    fileId: fileRow.id,
    orderId: order.id,
    taskId: printTask.id,
    entryId,
    contentVersion,
    retained: true,
    reason: 'Immutable simulated browser-loop task and receipt retained as isolated development evidence.'
  }
} catch (error) {
  report.status = 'FAIL'
  const safeCode = typeof error?.message === 'string' && /^(check_failed|capture_timeout):[A-Za-z0-9_]+$/.test(error.message)
    ? error.message
    : null
  report.failure = { type: error?.name || 'Error', stage, ...(safeCode ? { code: safeCode } : {}) }
  if (stage.startsWith('library_detail') && miniPage) {
    try { report.screenshots.push(await screenshot(miniPage, 'failure-library-detail')) } catch {}
  }
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
