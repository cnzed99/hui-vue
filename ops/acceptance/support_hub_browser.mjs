import path from 'node:path'
import fs from 'node:fs/promises'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const base = '/home/ubuntu/print-system'
const runtime = '/home/ubuntu/print-system/runtime/browser-tools'
const expectedRelease = '/var/www/print-system/releases/miniapp-support-hub-fix-20261103T0218Z-2d97b8c'
const expectedIndexSha256 = '71af6f640c32dc3d190090c75468c07052c2c6b7cc8531537d62113f6a4d2212'
const runId = new Date().toISOString().replace(/[-:.]/g, '') + '-' + crypto.randomBytes(4).toString('hex')
const out = path.join(base, 'evidence/miniapp', `support-hub-${runId}`)
process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(runtime, 'browsers')
process.env.LD_LIBRARY_PATH = path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
process.env.FONTCONFIG_FILE = path.join(runtime, 'fonts.conf')
process.env.XDG_CACHE_HOME = path.join(runtime, 'cache')
process.env.TMPDIR = path.join(runtime, 'run-tmp')
const require = createRequire(path.join(runtime, 'package.json'))
const { chromium } = require('playwright')
const browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'], env: { ...process.env } })
const context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' })
const page = await context.newPage()
const writes = []
page.on('request', request => {
  if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push(`${request.method()} ${request.url()}`)
})
const result = {
  schemaVersion: 1,
  scope: 'service center read-only help navigation',
  sourceCommit: '2d97b8c966479caea712ffa1a8a59c93766cb369',
  startedAt: new Date().toISOString(),
  checks: [],
  boundaries: { feedbackWrite: 'NOT_IMPLEMENTED', phoneBinding: 'NOT_IMPLEMENTED', externalAcceptance: 'NOT_RUN' }
}
function check(name, passed) {
  result.checks.push({ name, passed: Boolean(passed) })
  if (!passed) throw new Error(name)
}
try {
  const activeRelease = await fs.realpath('/var/www/print-system/miniapp')
  const indexSha256 = crypto.createHash('sha256').update(await fs.readFile('/var/www/print-system/miniapp/index.html')).digest('hex')
  result.deployment = { activeRelease, indexSha256, expectedRelease, expectedIndexSha256 }
  check('deployment_matches_candidate', activeRelease === expectedRelease && indexSha256 === expectedIndexSha256)
  await page.goto('http://127.0.0.1:18080/miniapp/#/pages/service-center/index', { waitUntil: 'domcontentloaded' })
  await page.getByText('新系统只读帮助', { exact: true }).waitFor()
  check('service_center_boundaries', await page.getByText('新手教程', { exact: true }).isVisible()
    && await page.getByText('订单与售后', { exact: true }).isVisible()
    && await page.getByText('未接入', { exact: true }).count() === 2)
  await page.getByText('新手教程', { exact: true }).click()
  await page.getByText('用户指南-文章列表', { exact: true }).waitFor()
  check('tutorial_navigation', page.url().includes('#/pages/tutorial-list/index'))
  await page.goto('http://127.0.0.1:18080/miniapp/#/pages/service-center/index', { waitUntil: 'domcontentloaded' })
  await page.getByText('订单与售后', { exact: true }).click()
  await page.getByText('我的订单', { exact: true }).waitFor()
  check('orders_navigation', page.url().includes('#/pages/orders/index'))
  const sessionWrites = writes.filter(value => value === 'POST http://127.0.0.1:18080/app-api/print/miniapp/demo/sessions')
  check('no_business_writes', writes.length === sessionWrites.length && sessionWrites.length <= 1)
  result.status = 'PASS'
} catch (error) {
  result.status = 'FAIL'
  result.error = String(error?.message || error)
  process.exitCode = 1
} finally {
  result.writes = writes
  result.finishedAt = new Date().toISOString()
  result.passed = result.checks.filter(item => item.passed).length
  result.failed = result.checks.filter(item => !item.passed).length
  await browser.close()
  await fs.mkdir(out, { recursive: false, mode: 0o700 })
  const report = path.join(out, 'report.json')
  await fs.writeFile(report, JSON.stringify(result, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ status: result.status, passed: result.passed, failed: result.failed,
    report: path.relative(base, report), sha256: crypto.createHash('sha256').update(await fs.readFile(report)).digest('hex') }))
}
