import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import { extname, join, resolve } from 'node:path'

const [artifact, reportPath, sourceCommit] = process.argv.slice(2)
assert.ok(artifact && reportPath && sourceCommit, 'usage: archive_pdf_browser.mjs H5_DIR REPORT_PATH COMMIT')
const dist = resolve(artifact)
const runtime = '/home/ubuntu/print-system/runtime/browser-tools'
process.env.PLAYWRIGHT_BROWSERS_PATH = join(runtime, 'browsers')
process.env.LD_LIBRARY_PATH = join(runtime, 'sysroot/usr/lib/x86_64-linux-gnu')
process.env.FONTCONFIG_FILE = join(runtime, 'fonts.conf')
process.env.XDG_CACHE_HOME = join(runtime, 'cache')
const { chromium } = createRequire(join(runtime, 'package.json'))('playwright')
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const server = createServer(async (request, response) => {
  const prefix = '/miniapp/'
  const url = new URL(request.url, 'http://localhost')
  const file = resolve(dist, '.' + url.pathname.slice(prefix.length - 1))
  if (!url.pathname.startsWith(prefix) || (file !== dist && !file.startsWith(dist + '/'))) {
    response.writeHead(404).end()
    return
  }
  const target = file === dist ? join(file, 'index.html') : file
  try {
    const body = await readFile(target)
    response.writeHead(200, { 'Content-Type': mime[extname(target)] || 'application/octet-stream' }).end(body)
  } catch {
    response.writeHead(404).end()
  }
})
await new Promise(done => server.listen(0, '127.0.0.1', done))
const origin = 'http://127.0.0.1:' + server.address().port
const checks = []
const mockErrors = []
const report = {
  sourceCommit,
  scope: 'isolated H5 browser with mocked archive/session/file HTTP endpoints',
  originalSystem: 'NOT_RUN',
  realBackendIntegration: 'NOT_RUN',
  realWeChat: 'NOT_RUN',
  checks
}
let session = {
  sessionId: 'archive-test-session',
  principalId: 'archive-test-principal',
  accessToken: 'A'.repeat(43),
  actorType: 'MINIAPP_DEMO',
  expiresAtMs: Date.now() + 3600000
}
const digest = 'a'.repeat(64)
const fileId = '00000000-0000-4000-8000-000000000007'
const bytes = Buffer.from('PK mock archive bytes')
const record = {
  id: fileId, displayName: 'server-document.pdf', sizeBytes: 3276,
  pageCount: null, status: 'UPLOADED', failureCode: null,
  createdAtMs: Date.now(), expiresAtMs: session.expiresAtMs, ready: false
}
const keys = []
let inspected = 0
let polled = 0
let heldReply
let releaseHeld
const staleArrived = new Promise(done => { heldReply = done })
const staleRelease = new Promise(done => { releaseHeld = done })
let browser

function pass(name) { checks.push({ name, passed: true }) }

try {
  browser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage'] })
  const context = await browser.newContext({ serviceWorkers: 'block' })
  await context.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin) {
      mockErrors.push('Unexpected external request')
      return route.abort()
    }
    const send = data => route.fulfill({ json: { code: 0, data } })
    try {
      if (url.pathname === '/app-api/print/miniapp/demo/sessions') return send(session)
      if (url.pathname === '/app-api/print/miniapp/demo/file') {
        assert.equal(url.searchParams.get('id'), fileId)
        assert.equal(request.headers().authorization, 'Bearer ' + session.accessToken)
        polled++
        return send({ ...record, status: 'READY', ready: true, pageCount: 4 })
      }
      const inspection = url.pathname === '/app-api/print/miniapp-demo/archive-inspections'
      const selection = url.pathname === '/app-api/print/miniapp-demo/archive-files'
      if (!inspection && !selection) return route.continue()
      assert.equal(request.method(), 'POST')
      assert.equal(request.headers().authorization, 'Bearer ' + session.accessToken)
      const form = await new Response(request.postDataBuffer(), {
        headers: { 'Content-Type': request.headers()['content-type'] }
      }).formData()
      const archive = form.get('archive')
      assert.equal(archive.name, 'test.zip')
      assert.deepEqual(Buffer.from(await archive.arrayBuffer()), bytes)
      if (inspection) {
        assert.deepEqual([...form.keys()], ['archive'])
        inspected++
        return send({ archiveSha256: digest, expiresAtMs: session.expiresAtMs, candidates: [
          { ordinal: 2, displayName: 'first.pdf', declaredSizeBytes: 256 },
          { ordinal: 7, displayName: 'second.pdf', declaredSizeBytes: 1024 }
        ] })
      }
      assert.deepEqual([...form.keys()], ['archive', 'archiveSha256', 'ordinal'])
      assert.equal(form.get('archiveSha256'), digest)
      const ordinal = form.get('ordinal')
      const key = request.headers()['idempotency-key']
      assert.match(key, /^miniapp-archive-/)
      keys.push({ key, ordinal })
      if (keys.length === 1) return route.abort('connectionreset')
      if (ordinal === '2') {
        heldReply()
        await staleRelease
      }
      return send(record)
    } catch (error) {
      mockErrors.push(String(error))
      return route.abort()
    }
  })

  const page = await context.newPage()
  page.setDefaultTimeout(10000)
  async function choose(name, buffer) {
    const chooser = page.waitForEvent('filechooser')
    await page.getByText('选择本地 ZIP', { exact: true }).click()
    await (await chooser).setFiles({ name, mimeType: 'application/zip', buffer })
  }
  const inspect = () => page.getByText('上传并检查 ZIP', { exact: true }).click()
  const extract = () => page.getByText('提取并解析所选 PDF', { exact: true }).click()
  const candidate = name => page.locator('.candidate').filter({ has: page.getByText(name, { exact: true }) })
  await page.goto(origin + '/miniapp/')
  await page.getByText('压缩包打印', { exact: true }).click()
  await page.waitForURL(url => url.hash.includes('/pages/archive-print/index'))
  pass('home_entry')

  await choose('bad.pdf', bytes)
  await page.getByText('请选择一个不超过 2 MiB 的 ZIP 文件。', { exact: true }).waitFor()
  assert.equal(inspected, 0)
  pass('reject_invalid_local_file_without_upload')

  await choose('test.zip', bytes)
  assert.equal(inspected, 0)
  await inspect()
  await candidate('second.pdf').click()
  await extract()
  await page.getByText('提交结果暂未确认，请重试当前选择。', { exact: true }).waitFor()
  pass('show_unknown_outcome')

  await page.reload()
  await choose('test.zip', bytes)
  await inspect()
  await page.getByText('候选 PDF', { exact: true }).waitFor()
  await extract()
  await page.getByText('服务端解析完成 · 4 页', { exact: true }).waitFor()
  assert.equal(inspected, 2)
  assert.equal(polled, 1)
  assert.equal(keys.length, 2)
  assert.equal(keys[0].ordinal, '7')
  assert.deepEqual(keys[0], keys[1])
  pass('same_key_and_original_ordinal_after_reload')

  await page.getByText('继续设置打印参数', { exact: true }).click()
  await page.waitForURL(url => url.hash.includes('/pages/document-settings/index'))
  await page.getByText('server-document.pdf', { exact: true }).waitFor()
  await page.getByText('3.2 KB · 4 页', { exact: true }).waitFor()
  pass('ready_server_metadata_in_existing_draft')

  await page.getByText('‹', { exact: true }).click()
  await candidate('first.pdf').click()
  await extract()
  let staleTimer
  await Promise.race([staleArrived, new Promise((_, reject) => {
    staleTimer = setTimeout(() => reject(new Error('mock selection did not arrive')), 10000)
  })]).finally(() => clearTimeout(staleTimer))
  await candidate('second.pdf').click()
  const staleResponse = page.waitForResponse(response => response.url().endsWith('/archive-files'))
  releaseHeld()
  await staleResponse
  assert.equal(keys[2].ordinal, '2')
  assert.notEqual(keys[0].key, keys[2].key)
  assert.equal(await page.getByText('继续设置打印参数', { exact: true }).count(), 0)
  assert.equal(polled, 1)
  pass('late_selection_response_does_not_apply_after_new_choice')

  await page.evaluate(() => {
    const key = Object.keys(localStorage).find(name => name.endsWith('miniapp-demo-session-v1'))
    if (!key) throw new Error('mock session storage missing')
    localStorage.removeItem(key)
  })
  session = { ...session, sessionId: 'replacement-session', principalId: 'replacement-principal', accessToken: 'B'.repeat(43) }
  await page.goto(origin + '/miniapp/#/pages/document-settings/index')
  await page.reload()
  await page.waitForURL(url => url.hash.includes('/pages/document-upload/index'))
  assert.equal(await page.evaluate(() => Object.keys(localStorage).some(key => key.endsWith('miniapp-print-draft-v1'))), false)
  await page.goto(origin + '/miniapp/#/pages/archive-print/index')
  await choose('test.zip', bytes)
  await inspect()
  await page.getByText('候选 PDF', { exact: true }).waitFor()
  assert.equal(await page.locator('.candidate.selected').count(), 0)
  await candidate('first.pdf').click()
  await extract()
  await page.getByText('服务端解析完成 · 4 页', { exact: true }).waitFor()
  assert.equal(keys[3].ordinal, '2')
  assert.notEqual(keys[3].key, keys[2].key)
  pass('new_session_cannot_resume_old_draft_or_selection_key')

  assert.deepEqual(mockErrors, [])
  pass('only_isolated_origin_and_frozen_fields')
} catch (error) {
  report.failure = String(error)
  report.mockErrors = mockErrors
  process.exitCode = 1
} finally {
  releaseHeld()
  await browser?.close()
  await new Promise(done => server.close(done))
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify(report, null, 2))
}
