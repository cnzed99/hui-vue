import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = path.join(base, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `m5-profile-more-browser-${runId}`)
const checks = []
const forbiddenRequests = []
let browser
let context

function check(name, condition) {
  checks.push({ name, passed: Boolean(condition) })
  if (!condition) throw new Error(`check_failed:${name}`)
}

async function open(page, route, title) {
  await page.goto(`${origin}/miniapp/#${route}`, { waitUntil: 'domcontentloaded' })
  await page.getByText(title, { exact: true }).first().waitFor({ state: 'visible' })
}

async function openDirect(page, route, title, caseName) {
  await page.goto(`${origin}/miniapp/?direct=${caseName}-${runId}#${route}`, { waitUntil: 'domcontentloaded' })
  await page.getByText(title, { exact: true }).first().waitFor({ state: 'visible' })
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
  scope: 'M5 profile and more-print ordinary-page browser batch',
  runId,
  startedAt: new Date().toISOString(),
  checks,
  screenshots: [],
  boundaries: {
    originalSystemWrites: 'NOT_RUN',
    wechatSelectors: 'NOT_RUN',
    clipboardRead: 'NOT_RUN',
    realIdentity: 'NOT_RUN',
    realMembershipFunds: 'NOT_RUN',
    externalAcceptance: 'NOT_RUN'
  }
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
  context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' })
  context.setDefaultTimeout(12000)
  context.on('request', request => {
    const method = request.method()
    const url = request.url()
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) || /youfan\.iyint\.com/i.test(url)) forbiddenRequests.push({ method, url })
  })
  const page = await context.newPage()

  await open(page, '/pages/home/index', '云途智印')
  await page.getByText('发票打印', { exact: true }).click()
  await page.getByText('微信卡包', { exact: true }).waitFor({ state: 'visible' })
  check('invoice_sources_render', await page.getByText('微信卡包', { exact: true }).isVisible() && await page.getByText('微信文件', { exact: true }).isVisible())
  report.screenshots.push(await shot(page, 'invoice-print'))

  await open(page, '/pages/text-print/index', '文字速印')
  check('text_print_directory_render', await page.getByText('快速设计', { exact: true }).isVisible() && await page.getByText('自定义文字', { exact: true }).isVisible())
  await page.getByText('自定义文字', { exact: true }).click()
  const editor = page.locator('textarea')
  await editor.waitFor({ state: 'visible' })
  check('formatting_controls_explicitly_reserved', await page.getByText('排版工具仅还原入口，当前不改变文字样式', { exact: true }).isVisible()
    && await page.locator('.tool.active').count() === 0)
  await editor.fill('本地文字演示')
  check('text_editor_local_count', await page.getByText('文字字数：6，字数限制在1万字以内', { exact: true }).isVisible())
  report.screenshots.push(await shot(page, 'text-editor'))

  await open(page, '/pages/link-print/index', '链接打印')
  const linkInput = page.locator('textarea')
  check('link_page_does_not_autofill_clipboard', await linkInput.inputValue() === '')
  await linkInput.fill('https://example.invalid/article')
  check('link_page_explicit_input_only', await linkInput.inputValue() === 'https://example.invalid/article')
  report.screenshots.push(await shot(page, 'link-print'))

  await open(page, '/pages/profile/index', '我的')
  await page.getByText('会员卡', { exact: true }).click()
  await page.getByText('普通会员', { exact: true }).waitFor({ state: 'visible' })
  check('member_card_safe_local_state', await page.getByText('普通会员', { exact: true }).isVisible()
    && await page.locator('.balance-label').textContent() === '余额：'
    && await page.locator('.balance').textContent() === '0元')

  await open(page, '/pages/vouchers/index', '代金红包')
  await page.getByText('已使用', { exact: true }).click()
  await page.getByText('暂无已使用红包', { exact: true }).waitFor({ state: 'visible' })
  check('voucher_tabs_switch', await page.getByText('暂无已使用红包', { exact: true }).isVisible())

  await open(page, '/pages/my-library/index', '我的文库')
  await page.getByText('已购买', { exact: true }).click()
  await page.getByText('已购买列表将在微信登录与文库权益接口接入后加载。', { exact: true }).waitFor({ state: 'visible' })
  check('my_library_tabs_switch', await page.getByText('已购买列表将在微信登录与文库权益接口接入后加载。', { exact: true }).isVisible())

  await open(page, '/pages/service-center/index', '服务中心')
  check('service_center_safe_entries', await page.getByText('意见反馈', { exact: true }).isVisible() && await page.getByText('绑定手机', { exact: true }).isVisible())

  await open(page, '/pages/privacy/index', '隐私协议')
  check('privacy_directory_and_cancellation_boundary', await page.getByText('用户隐私政策', { exact: true }).isVisible()
    && await page.getByText('会员卡用户使用协议', { exact: true }).isVisible()
    && await page.getByText('用户注册协议', { exact: true }).isVisible()
    && await page.getByText(/当前按钮不会注销账户或删除数据/).isVisible())
  report.screenshots.push(await shot(page, 'profile-pages'))

  await openDirect(page, '/pages/invoice-print/index', '发票打印', 'invoice')
  await page.getByText('‹', { exact: true }).click()
  await page.getByText('云途智印', { exact: true }).first().waitFor({ state: 'visible' })
  check('direct_home_child_return_fallback', true)

  await openDirect(page, '/pages/vouchers/index', '代金红包', 'vouchers')
  await page.getByText('‹', { exact: true }).click()
  await page.getByText('我的', { exact: true }).first().waitFor({ state: 'visible' })
  check('direct_profile_child_return_fallback', true)
  check('no_forbidden_writes_or_original_calls', forbiddenRequests.length === 0)
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
  report.forbiddenRequests = forbiddenRequests
  await fs.mkdir(out, { recursive: true, mode: 0o700 })
  const target = path.join(out, 'report.json')
  await fs.writeFile(target, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: report.status, passed: report.passed, failed: report.failed, report: path.relative(base, target), sha256: crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex'), failure: report.failure || null }))
}
