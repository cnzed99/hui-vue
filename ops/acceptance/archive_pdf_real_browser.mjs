import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { createServer, request as upstream } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve } from 'node:path'

const [dir, reportPath, sourceCommit] = process.argv.slice(2)
assert.ok(dir && reportPath && sourceCommit, 'H5_DIR REPORT_PATH COMMIT required')
const dist = resolve(dir)
const fileName = process.env.ARCHIVE_FIXTURE_PDF || 'synthetic.pdf'
assert.match(fileName, /^synthetic(?:-[a-z0-9]+)?\.pdf$/)
const runtime = '/home/ubuntu/print-system/runtime/browser-tools'
Object.assign(process.env, {
  PLAYWRIGHT_BROWSERS_PATH: join(runtime, 'browsers'),
  LD_LIBRARY_PATH: join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu'),
  FONTCONFIG_FILE: join(runtime, 'fonts.conf'),
  XDG_CACHE_HOME: join(runtime, 'cache')
})
const { chromium } = createRequire(join(runtime, 'package.json'))('playwright')
const fixture = execFileSync('python3', ['-c', [
  'import io,sys,zipfile',
  'objects=[b"<< /Type /Catalog /Pages 2 0 R >>",b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>",b"<< /Length 0 >>\\nstream\\nendstream"]',
  'pdf=bytearray(b"%PDF-1.4\\n"); offsets=[]',
  'for n, obj in enumerate(objects,1):',
  ' offsets.append(len(pdf)); pdf+=f"{n} 0 obj\\n".encode()+obj+b"\\nendobj\\n"',
  'xref=len(pdf); pdf+=b"xref\\n0 5\\n0000000000 65535 f \\n"',
  'for off in offsets: pdf+=f"{off:010d} 00000 n \\n".encode()',
  'pdf+=f"trailer\\n<< /Size 5 /Root 1 0 R >>\\nstartxref\\n{xref}\\n%%EOF\\n".encode()',
  'buf=io.BytesIO()',
  'with zipfile.ZipFile(buf,"w",compression=zipfile.ZIP_STORED) as z:',
  ' z.writestr("ignored.txt",b"Synthetic fixture"); z.writestr(sys.argv[1],pdf)',
  'sys.stdout.buffer.write(buf.getvalue())'
].join('\n'), fileName])
const checks = []
const requests = []
const endpoint = 'http://127.0.0.1:45920'
const report = { sourceCommit, h5IndexSha256: createHash('sha256').update(await readFile(join(dist, 'index.html'))).digest('hex'),
  fixture: 'synthetic ZIP containing one-page PDF', isolated: true, realWeChat: 'NOT_RUN', originalSystem: 'NOT_RUN', checks, requests }
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const server = createServer(async (req, res) => {
  const url = new URL(req.url, endpoint)
  if (url.pathname.startsWith('/app-api/print/miniapp')) {
    const headers = { ...req.headers, host: '127.0.0.1', origin: endpoint, referer: endpoint + '/miniapp/' }
    delete headers.connection
    const api = upstream(endpoint + url.pathname + url.search, { method: req.method, headers }, response => {
      requests.push({ method: req.method, path: url.pathname, status: response.statusCode })
      res.writeHead(response.statusCode, response.headers)
      response.pipe(res)
    })
    api.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end() })
    req.pipe(api)
    return
  }
  const prefix = '/miniapp/'
  const target = resolve(dist, '.' + url.pathname.slice(prefix.length - 1))
  if (!url.pathname.startsWith(prefix) || (target !== dist && !target.startsWith(dist + '/'))) return res.writeHead(404).end()
  try {
    const file = target === dist ? join(dist, 'index.html') : target
    res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' }).end(await readFile(file))
  } catch {
    if (!res.headersSent) res.writeHead(404)
    res.end()
  }
})
await new Promise(done => server.listen(0, '127.0.0.1', done))
const origin = 'http://127.0.0.1:' + server.address().port
let browser
let stage = 'browser_launch'
try {
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const context = await browser.newContext({ serviceWorkers: 'block' })
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort())
  const page = await context.newPage()
  page.setDefaultTimeout(12000)
  const pass = name => checks.push({ name, passed: true })
  const button = label => page.getByText(label, { exact: true })
  await page.goto(origin + '/miniapp/')
  await button('压缩包打印').click()
  stage = 'zip_inspection'
  const chooser = page.waitForEvent('filechooser')
  await button('选择本地 ZIP').click()
  await (await chooser).setFiles({ name: 'synthetic.zip', mimeType: 'application/zip', buffer: fixture })
  await button('上传并检查 ZIP').click()
  await page.locator('.candidate').filter({ hasText: fileName }).click()
  pass('real_session_and_zip_inspection')
  stage = 'selection_and_ready'
  await button('提取并解析所选 PDF').click()
  await button('服务端解析完成 · 1 页').waitFor({ timeout: 30000 })
  pass('real_zip_selection_and_pdf_ready')
  stage = 'settings_and_quote'
  await button('继续设置打印参数').click()
  await button(fileName).waitFor()
  await button('确认参数').click()
  await button('创建模拟订单').waitFor()
  pass('real_authoritative_quote')
  stage = 'order'
  await button('创建模拟订单').click()
  await button('模拟支付（不扣真实资金）').waitFor()
  pass('real_simulated_order_created')
  stage = 'payment'
  await button('模拟支付（不扣真实资金）').click()
  await page.locator('.uni-modal').getByText('模拟支付', { exact: true }).click()
  await button('模拟支付成功，不涉及真实资金').waitFor()
  pass('real_simulated_payment_receipt')
  console.log('BROWSER_PAYMENT_READY')
  stage = 'print_result'
  await page.locator('.print-receipt').getByText('模拟成功回执', { exact: true }).waitFor({ timeout: 120000 })
  pass('real_simulated_print_result_visible')
} catch {
  report.incompleteAt = stage
  process.exitCode = 1
} finally {
  await browser?.close()
  await new Promise(done => server.close(done))
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify(report, null, 2))
}
