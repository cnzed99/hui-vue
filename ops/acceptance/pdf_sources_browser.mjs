import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'

const root = '/home/ubuntu/print-system'
const runtime = path.join(root, 'runtime/browser-tools')
const origin = 'http://127.0.0.1:18080'
const out = process.argv[2]
if (!out?.startsWith(path.join(root, 'evidence/deploy/pdf-sources-'))) throw new Error('Invalid evidence path')
const checks = [], requests = []
let browser, context, temp
const report = { schemaVersion: 1, scope: 'two H5 PDF source entries and client rejection paths', checks, screenshots: [], wechatDevice: 'NOT_RUN', realPayment: 'NOT_RUN', realPrinter: 'NOT_RUN' }
function check(name, value) { checks.push({ name, passed: Boolean(value) }); if (!value) throw new Error(name) }
const pdf = await fs.readFile(path.join(out, 'safe-fixture.pdf'))
async function shot(page, name) {
  const p = path.join(out, name + '.png'); await page.screenshot({ path: p })
  report.screenshots.push({ path: path.relative(root, p), sha256: crypto.createHash('sha256').update(await fs.readFile(p)).digest('hex') })
}
async function choose(page, value) {
  const [picker] = await Promise.all([page.waitForEvent('filechooser'), page.locator('.choose-button').click()])
  await picker.setFiles(value)
}
const filePosts = () => requests.filter(x => x.method === 'POST' && x.path === '/app-api/print/miniapp/demo/files').length
try {
  process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(runtime, 'browsers')
  process.env.LD_LIBRARY_PATH = path.join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
  process.env.FONTCONFIG_FILE = path.join(runtime, 'fonts.conf')
  process.env.XDG_CACHE_HOME = path.join(runtime, 'cache')
  temp = await fs.mkdtemp(path.join(runtime, 'run-tmp/pdf-sources-'))
  process.env.TMPDIR = temp
  const { chromium } = createRequire(path.join(runtime, 'package.json'))('playwright')
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'], env: { ...process.env } })
  context = await browser.newContext({ viewport: { width: 430, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' })
  context.setDefaultTimeout(20000)
  context.on('request', request => requests.push({ method: request.method(), path: new URL(request.url()).pathname, originalSystem: /youfan\.iyint\.com/i.test(request.url()) }))
  const page = await context.newPage()
  await page.goto(origin + '/miniapp/#/pages/document-method/index', { waitUntil: 'domcontentloaded' })
  await page.getByText('微信聊天文档打印', { exact: true }).click()
  await page.getByText('安全演示会话已建立', { exact: true }).waitFor()
  check('chat_entry_source_title', await page.locator('.title-row').innerText().then(x => x.includes('微信聊天文档')))
  check('h5_local_picker_explanation', await page.getByText(/当前为网页预览/).isVisible())
  await choose(page, [])
  check('cancel_keeps_empty_and_no_upload', await page.locator('.result-card').count() === 0 && filePosts() === 0)
  await choose(page, { name: 'unsupported.docx', mimeType: 'application/octet-stream', buffer: Buffer.from('safe fixture') })
  await page.getByText('当前仅支持 PDF；其它文档格式待接入。', { exact: true }).waitFor()
  await page.getByText('请重新选择 PDF', { exact: true }).click()
  check('non_pdf_does_not_upload', filePosts() === 0)
  await choose(page, { name: 'too-large.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(2097153, 32) })
  await page.getByText('文件超过 2MB 演示限制。', { exact: true }).waitFor()
  await page.getByText('请重新选择 PDF', { exact: true }).click()
  check('over_limit_does_not_upload', filePosts() === 0)
  for (const source of ['chat', 'invoice']) {
    if (source === 'invoice') {
      await page.goto(origin + '/miniapp/#/pages/invoice-print/index', { waitUntil: 'domcontentloaded' })
      await page.getByText('微信文件', { exact: true }).click()
      await page.getByText('安全演示会话已建立', { exact: true }).waitFor()
      check('invoice_entry_source_title', await page.locator('.title-row').innerText().then(x => x.includes('发票文件打印')))
    }
    await choose(page, { name: source + '-safe-fixture.pdf', mimeType: 'application/pdf', buffer: pdf })
    await page.getByText('上传并安全解析', { exact: true }).click()
    await page.getByText('服务端解析完成', { exact: true }).waitFor({ timeout: 45000 })
    await page.getByText('继续设置打印参数', { exact: true }).click()
    await page.getByText('打印设置', { exact: true }).first().waitFor()
    check(source + '_parsed_pdf_enters_settings', await page.getByText(source + '-safe-fixture.pdf', { exact: true }).isVisible())
    await shot(page, source + '-shared-settings')
  }
  check('two_pdf_uploads_only', filePosts() === 2)
  check('no_original_system_requests', requests.every(x => !x.originalSystem))
  report.status = 'PASS'
} catch (error) {
  report.status = 'FAIL'; report.failure = { type: error?.name || 'Error', message: String(error?.message || error).slice(0, 400) }; process.exitCode = 1
} finally {
  if (context) await context.close().catch(() => {})
  if (browser) await browser.close().catch(() => {})
  if (temp && path.dirname(temp) === path.join(runtime, 'run-tmp') && path.basename(temp).startsWith('pdf-sources-')) await fs.rm(temp, { recursive: true, force: true })
  report.requests = requests
  await fs.writeFile(path.join(out, 'browser-report.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(JSON.stringify({ status: report.status, checks: checks.length, uploads: filePosts(), wechatDevice: 'NOT_RUN' }))
}
