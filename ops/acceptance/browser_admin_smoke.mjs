/** Loopback UI acceptance only. No token/storage injection or print business writes. */
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import crypto from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const base = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const runtime = path.join(base, 'runtime/browser-tools')
const probeBrowser = process.argv.includes('--probe-browser')
const origin = 'http://127.0.0.1:18080'
const allowLogin = process.argv.includes('--allow-login')
const attemptArgument = process.argv.find(value => value.startsWith('--attempt='))?.slice('--attempt='.length)
const attempt = /^[1-9]\d{0,3}$/.test(attemptArgument || '') ? attemptArgument : String(Date.now())
const output = path.join(base, probeBrowser ? 'evidence/acceptance/browser-runtime-preflight' : 'evidence/acceptance/browser-admin-smoke', ...(allowLogin && !probeBrowser ? [`attempt-${attempt}`] : []))
const report = { schemaVersion: 1, scope: probeBrowser ? 'browser-runtime-preflight-no-login' : 'deployed-admin-shell-orders-disabled-only',
  startedAt: new Date().toISOString(), browser: null, checks: [], screenshots: [],
  collection: { har: false, trace: false, video: false, console: false, networkPayloadLogs: false,
    loginScreenshots: false, storageStateExport: false, persistentContext: false },
  exclusions: ['order-data-page', 'end-to-end-workflow', 'visual-parity', 'real-payment', 'terminal-receipt', 'physical-printing'],
  blockedBusinessWrites: 0, loginRequests: 0, loginSuccessfulResponses: 0, refreshRequests: 0, loginResponseCodes: [], attempt: allowLogin && !probeBrowser ? attempt : null, completed: false }
let outputOwned = false, ownSession = null, resolveLoginOutcome
const loginOutcome = new Promise(resolve => { resolveLoginOutcome = resolve })
let browser, context, page, credentials, stage = 'preflight', loggedIn = false, loginAttempted = false
const observed = { context: null, permission: null, ordersDisabled: false, logout: false }
const check = (id, condition) => {
  report.checks.push({ id, status: condition ? 'PASS' : 'FAIL' })
  if (!condition) { const failure = new Error('check_failed'); failure.name = 'CheckFailure'; throw failure }
}
const urlIs = (wanted) => { try { return new URL(page.url()).pathname === wanted } catch { return false } }
const waitPath = (wanted) => page.waitForURL(url => url.pathname === wanted, { timeout: 25000 })
const safeScreenshot = async (name) => {
  // Screenshots are only possible after login and after the login form unmounts.
  if (!loggedIn || urlIs('/login') || await page.locator('input[type=password]').count()) throw new Error('unsafe_screenshot_state')
  const destination = path.join(output, name + '.png')
  await page.screenshot({ path: destination, fullPage: false,
    mask: [page.locator('.print-admin__identity'), page.locator('[data-testid="print-account-name"]'), page.locator('[data-testid="print-store-name"]')], maskColor: '#64748b' })
  report.screenshots.push({ name, path: path.relative(base, destination), sha256: crypto.createHash('sha256').update(await fs.readFile(destination)).digest('hex') })
}
const readCredentials = async () => {
  const location = path.join(base, 'runtime/secrets/smoke-admin.json')
  const info = await fs.lstat(location)
  if (!info.isFile() || info.isSymbolicLink() || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o600 || info.size > 8192) throw new Error('private_credential_file_required')
  const value = JSON.parse(await fs.readFile(location, 'utf8'))
  if (!value || !['username', 'password'].every(k => typeof value[k] === 'string' && value[k].length > 0)) throw new Error('credential_schema_invalid')
  return value
}
const positive = value => Number.isSafeInteger(value) && value > 0
const exec = promisify(execFile)
const serviceSnapshot = async () => {
  const { stdout } = await exec('systemctl', ['is-active', 'mysql', 'redis-server', 'nginx', 'print-backend'])
  return stdout.trim().split(/\s+/).every(value => value === 'active')
}
try {
  if (allowLogin && !probeBrowser) {
    await fs.mkdir(path.dirname(output), { recursive: true, mode: 0o700 })
    await fs.mkdir(output, { mode: 0o700 }) // EEXIST refuses to overwrite an earlier attempt.
  } else await fs.mkdir(output, { recursive: true, mode: 0o700 })
  outputOwned = true
  report.scriptSha256 = crypto.createHash('sha256').update(await fs.readFile(fileURLToPath(import.meta.url))).digest('hex')
  const free = await fs.statfs(base)
  check('disk-reserve-at-least-10GiB-before', free.bavail * free.bsize >= 10 * 1024 ** 3)
  check('four-services-active-before', await serviceSnapshot())
  if (!allowLogin && !probeBrowser) {
    report.checks.push({ id: 'real-ui-login-roundtrip', status: 'NOT_RUN', reason: 'Explicit --allow-login required.' })
  } else {
    if (!probeBrowser) credentials = await readCredentials()
    process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(runtime, 'browsers')
    process.env.LD_LIBRARY_PATH = path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
    process.env.FONTCONFIG_FILE = path.join(runtime, 'fonts.conf')
    process.env.XDG_CACHE_HOME = path.join(runtime, 'cache')
    process.env.TMPDIR = path.join(runtime, 'run-tmp')
    await fs.mkdir(process.env.TMPDIR, { recursive: true, mode: 0o700 })
    const require = createRequire(path.join(runtime, 'package.json'))
    const { chromium } = require('playwright')
    report.playwrightVersion = require('playwright/package.json').version
    stage = 'browser-launch'
    browser = await chromium.launch({ headless: true, timeout: 30000, args: ['--disable-dev-shm-usage'], env: { ...process.env, LD_LIBRARY_PATH: path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu'), FONTCONFIG_FILE: path.join(runtime, 'fonts.conf'), XDG_CACHE_HOME: path.join(runtime, 'cache') } })
    report.browser = browser.version()
    if (probeBrowser) {
      stage = 'credential-free-runtime-probe'
      context = await browser.newContext({ serviceWorkers: 'block' })
      await context.route('**/*', route => route.abort('blockedbyclient'))
      page = await context.newPage()
      await page.goto('about:blank')
      check('chromium-launches-isolated-context-without-network-or-credentials', page.url() === 'about:blank')
      report.completed = true
    } else {
    context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
    context.setDefaultTimeout(15000)
    await context.route('**/*', async route => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.origin === origin && url.pathname === '/admin-api/system/auth/login' && request.method() === 'POST') report.loginRequests++
      if (url.origin === origin && url.pathname === '/admin-api/system/auth/refresh-token') report.refreshRequests++
      if (url.protocol === 'data:' || url.protocol === 'blob:') return route.continue()
      if (url.origin !== origin) return route.abort('blockedbyclient')
      if (url.pathname.startsWith('/admin-api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method()) &&
          !['/admin-api/system/auth/login', '/admin-api/system/auth/logout', '/admin-api/system/auth/refresh-token'].includes(url.pathname)) {
        report.blockedBusinessWrites++
        return route.abort('blockedbyclient')
      }
      return route.continue()
    })
    page = await context.newPage()
    // Do not log request/console/trace/HAR data. Counts are fixed fields; own login token stays only in memory.
    page.on('response', async response => {
      try {
        const url = new URL(response.url())
        if (url.origin !== origin) return
        if (url.pathname === '/admin-api/system/auth/login' && response.request().method() === 'POST') {
          try {
            const body = await response.json()
            report.loginResponseCodes.push({ http: response.status(), business: typeof body.code === 'number' ? body.code : null })
            if (body.code === 0) {
              report.loginSuccessfulResponses++
              if (typeof body.data?.accessToken === 'string' && body.data.accessToken) {
                ownSession = { token: body.data.accessToken, tenant: await response.request().headerValue('tenant-id') }
              }
            }
          } finally { resolveLoginOutcome() }
          return
        }
        if (!['/admin-api/print/context', '/admin-api/system/auth/get-permission-info', '/admin-api/print/orders', '/admin-api/system/auth/logout'].includes(url.pathname)) return
        const body = await response.json()
        if (url.pathname === '/admin-api/print/context' && body.code === 0) observed.context = body.data
        if (url.pathname === '/admin-api/system/auth/get-permission-info' && body.code === 0) observed.permission = body.data
        if (url.pathname === '/admin-api/print/orders' && response.status() === 503 && body.code === 1510030000) observed.ordersDisabled = true
        if (url.pathname === '/admin-api/system/auth/logout' && body.code === 0 && body.data === true) observed.logout = true
      } catch { /* no response payload or exception text is logged */ }
    })
    stage = 'unauthenticated-guard'
    const servedDocument = await page.goto(origin + '/print/demo-orders', { waitUntil: 'domcontentloaded' })
    if (servedDocument) report.deployedDocumentSha256 = crypto.createHash('sha256').update(await servedDocument.body()).digest('hex')
    await waitPath('/login')
    check('unauthenticated-orders-route-redirects-to-login', new URL(page.url()).searchParams.get('redirect') === '/print/demo-orders')
    stage = 'real-login-form'
    const form = page.locator('form.login-form:visible')
    await form.waitFor({ state: 'visible' })
    const tenant = form.getByPlaceholder('请输入租户名称', { exact: true })
    check('real-tenant-field-visible', await tenant.isVisible())
    stage = 'login-tenant-input'
    await tenant.fill(typeof credentials.tenantName === 'string' && credentials.tenantName ? credentials.tenantName : 'Print Demo')
    stage = 'login-username-input'
    await form.getByPlaceholder('请输入用户名', { exact: true }).fill(credentials.username)
    stage = 'login-password-input'
    await form.getByPlaceholder('请输入密码', { exact: true }).fill(credentials.password)
    stage = 'login-remember-label'
    const rememberInput = form.locator('input[type=checkbox]')
    if (await rememberInput.isChecked()) await form.locator('label.el-checkbox').filter({ hasText: '记住我' }).click()
    check('remember-me-disabled-through-normal-label-click', !await rememberInput.isChecked())
    stage = 'login-submit'
    loginAttempted = true
    await form.getByRole('button', { name: '登录', exact: true }).click()
    stage = 'login-redirect'
    // A captcha or login failure is reported as this stage failure; no bypass and no screenshot.
    await waitPath('/print/demo-orders')
    await page.locator('[data-testid="print-admin-shell"]').waitFor({ state: 'visible' })
    loggedIn = true
    stage = 'login-response-validation'
    let loginCaptureTimer
    await Promise.race([loginOutcome, new Promise(resolve => { loginCaptureTimer = setTimeout(resolve, 10000) })])
    if (loginCaptureTimer) clearTimeout(loginCaptureTimer)
    check('one-real-login-request-and-success-with-own-token-captured', report.loginRequests === 1 && report.loginSuccessfulResponses === 1 && !!ownSession)
    check('real-form-login-keeps-protected-redirect', urlIs('/print/demo-orders'))
    stage = 'orders-feature-disabled'
    await page.locator('[data-testid="demo-orders"] [data-error-kind="disabled"]').waitFor({ state: 'visible' })
    check('deployed-orders-shows-feature-disabled', await page.getByRole('heading', { name: '演示订单尚未启用', exact: true }).isVisible())
    check('disabled-is-not-empty-data', await page.locator('[data-testid="orders-empty"]').count() === 0)
    check('actual-orders-HTTP503-business-code-observed', observed.ordersDisabled)
    await safeScreenshot('orders-disabled')
    stage = 'workbench-context'
    await page.getByRole('link', { name: '返回工作台', exact: true }).click()
    await waitPath('/print/workbench')
    await page.locator('[data-testid="print-context-ready"]').waitFor({ state: 'visible' })
    check('workbench-renders-real-store-context', await page.locator('[data-testid="print-store-name"]').isVisible())
    check('context-owner-matches-authenticated-user', observed.context?.actorType === 'STAFF' && positive(observed.context?.tenantId) &&
      positive(observed.context?.storeId) && positive(observed.context?.userId) && observed.context?.userId === observed.permission?.user?.id &&
      (!Number.isSafeInteger(credentials.tenantId) || observed.context.tenantId === credentials.tenantId))
    check('deployed-shell-has-original-module-navigation', await page.locator('nav[aria-label="一级模块"] button').count() > 0)
    check('deployed-workbench-has-demo-orders-link', await page.getByRole('link', { name: '演示订单', exact: true }).isVisible())
    await safeScreenshot('workbench-context')
    stage = 'workbench-orders-navigation'
    await page.getByRole('link', { name: '演示订单', exact: true }).click()
    await waitPath('/print/demo-orders')
    await page.locator('[data-error-kind="disabled"]').waitFor({ state: 'visible' })
    check('workbench-orders-navigation-reaches-disabled-state', urlIs('/print/demo-orders'))
    stage = 'normal-ui-logout'
    await page.locator('.print-admin__identity').getByRole('button', { name: '退出', exact: true }).click()
    await waitPath('/login')
    loggedIn = false
    check('normal-ui-logout-returns-to-login', urlIs('/login'))
    await page.waitForTimeout(150)
    check('real-logout-response-succeeded', observed.logout)
    stage = 'post-logout-guard'
    await page.goto(origin + '/print/workbench', { waitUntil: 'domcontentloaded' })
    await waitPath('/login')
    check('logout-workbench-access-requires-login', new URL(page.url()).searchParams.get('redirect') === '/print/workbench')
    check('no-print-business-write-attempted', report.blockedBusinessWrites === 0)
    check('fresh-login-session-did-not-require-auth-refresh', report.refreshRequests === 0)
    report.completed = true
    }
  }
} catch (error) {
  // Playwright errors can include typed input; never serialize message, stack, locator call log or payload.
  report.failure = { stage, type: ['TimeoutError', 'CheckFailure'].includes(error?.name) ? error.name : 'SanitizedFailure' }
  process.exitCode = 1
} finally {
  report.loginClickAttempted = loginAttempted
  report.loginSubmitted = report.loginRequests > 0
  if (report.loginRequests > 0) {
    // Allow a pending own login response to be captured before cleanup. Never serialize it.
    let timer
    await Promise.race([loginOutcome, new Promise(resolve => { timer = setTimeout(resolve, 35000) })])
    if (timer) clearTimeout(timer)
  }
  if ((ownSession || report.loginSuccessfulResponses > 0) && !observed.logout && page && !page.isClosed()) {
    // Normal UI logout is always preferred. Exact own-token cleanup below is the only API fallback.
    try {
      if (urlIs('/login') || await page.locator('.print-admin__identity').count() === 0) {
        await page.goto(origin + '/print/workbench', { waitUntil: 'domcontentloaded', timeout: 10000 })
        await page.locator('.print-admin__identity').waitFor({ state: 'visible', timeout: 5000 })
      }
      await page.locator('.print-admin__identity').getByRole('button', { name: '退出', exact: true }).click({ timeout: 5000 })
      await waitPath('/login'); loggedIn = false
    } catch { report.cleanupUiLogout = 'NOT_CONFIRMED' }
  }
  if (ownSession && context) {
    // Exact fallback is only for this run's captured login token; never use browser storage or another session.
    const headers = { Authorization: 'Bearer ' + ownSession.token, ...(ownSession.tenant ? { 'tenant-id': ownSession.tenant } : {}) }
    try {
      if (!observed.logout) {
        const response = await context.request.post(origin + '/admin-api/system/auth/logout', { headers, timeout: 10000, maxRedirects: 0 })
        const body = await response.json()
        report.cleanupExactLogout = { http: response.status(), business: typeof body.code === 'number' ? body.code : null, confirmed: body.code === 0 && body.data === true }
      }
      const response = await context.request.get(origin + '/admin-api/system/auth/get-permission-info', { headers, timeout: 10000, maxRedirects: 0 })
      let business = null
      try { const body = await response.json(); business = typeof body.code === 'number' ? body.code : null } catch {}
      report.ownSessionRevocation = { http: response.status(), business, confirmed: response.status() === 401 || business === 401 }
      if (!report.ownSessionRevocation.confirmed) { report.completed = false; process.exitCode = 1 }
    } catch { report.cleanupLogout = 'NOT_CONFIRMED'; report.completed = false; process.exitCode = 1 }
  } else if (report.loginRequests > 0 && (report.loginSuccessfulResponses > 0 || report.loginResponseCodes.length === 0)) {
    report.cleanupLogout = 'NOT_CONFIRMED_NO_OWN_TOKEN'; report.completed = false; process.exitCode = 1
  }
  ownSession = null
  report.contextClosed = context ? await context.close().then(() => true, () => false) : null
  report.browserClosed = browser ? await browser.close().then(() => true, () => false) : null
  if (report.contextClosed === false || report.browserClosed === false || report.cleanupLogout?.startsWith('NOT_CONFIRMED')) {
    report.completed = false; process.exitCode = 1
  }
  credentials = null
  observed.context = null; observed.permission = null
  report.finishedAt = new Date().toISOString()
  try {
    report.fourServicesActiveAfter = await serviceSnapshot()
    const free = await fs.statfs(base)
    report.diskReserveAfter = free.bavail * free.bsize >= 10 * 1024 ** 3
    if (!report.fourServicesActiveAfter || !report.diskReserveAfter) { report.completed = false; process.exitCode = 1 }
    if (!outputOwned) throw new Error('existing_evidence_not_overwritten')
    await fs.writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
    console.log(JSON.stringify({ completed: report.completed, passed: report.checks.filter(v => v.status === 'PASS').length,
      failed: report.checks.filter(v => v.status === 'FAIL').length, failureStage: report.failure?.stage || null,
      report: path.relative(base, path.join(output, 'report.json')), screenshots: report.screenshots.length }))
  } catch { process.exitCode = 1; console.log('browser_acceptance_report_write_failed') }
}
