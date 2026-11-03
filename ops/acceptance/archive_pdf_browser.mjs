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
let quoteCalls = 0
let holdSelection = false
let heldReply
let releaseHeld
const staleArrived = new Promise(done => { heldReply = done })
const staleRelease = new Promise(done => { releaseHeld = done })
let quoteArrived
let releaseQuote
const quoteStart = new Promise(done => { quoteArrived = done })
const quoteRelease = new Promise(done => { releaseQuote = done })
const orderId = '00000000-0000-4000-8000-000000000008'
const deferred = new Map()
const writes = []
let paidOrder = false
function defer(kind) {
  const started = Promise.withResolvers()
  const release = Promise.withResolvers()
  deferred.set(kind, { started, release })
  return { started: started.promise, release: release.resolve }
}
async function hold(kind) {
  const gate = deferred.get(kind)
  if (!gate) return
  gate.started.resolve()
  await gate.release.promise
  deferred.delete(kind)
}
async function waitForMock(promise) {
  let timer
  await Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('Expected mock request did not arrive')), 5000)
  })]).finally(() => clearTimeout(timer))
}
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
        return send({ ...record, expiresAtMs: session.expiresAtMs, status: 'READY', ready: true, pageCount: 4 })
      }
      const quoteId = 'quote-' + session.sessionId
      const snapshot = {
        schemaVersion: 1, mode: 'DEMO', currency: 'CNY', aggregationPolicy: 'PER_FILE_ALL_COPIES',
        paper: 'A4', color: 'BLACK_WHITE', side: 'SIMPLEX', pagesPerCopy: 4, totalSheets: '4',
        pricePerSheet: '0.20', totalAmount: '0.80', expiresAtMs: session.expiresAtMs + 60000
      }
      const order = {
        id: orderId, orderNo: 'old-owner-order', source: 'MINIAPP_DEMO', simulation: true, currency: 'CNY',
        totalAmount: '0.80', orderState: 'OPEN', paymentState: paidOrder ? 'SUCCEEDED' : 'UNPAID',
        refundState: 'NONE', cancellationOutcome: null, printState: 'NOT_CREATED', version: paidOrder ? 2 : 1,
        item: { id: 'item', quoteId, fileId, amount: '0.80', quoteSnapshot: snapshot }
      }
      if (url.pathname === '/app-api/print/miniapp/demo/quotes') {
        assert.equal(request.headers().authorization, 'Bearer ' + session.accessToken)
        assert.equal(request.postDataJSON().fileId, fileId)
        quoteCalls++
        quoteArrived()
        await quoteRelease
        return send({ id: quoteId, status: 'VALID', snapshot })
      }
      if (url.pathname === '/app-api/print/miniapp/demo/orders' && request.method() === 'POST') {
        assert.equal(request.headers().authorization, 'Bearer ' + session.accessToken)
        assert.equal(request.postDataJSON().quoteId, quoteId)
        assert.equal(request.headers()['idempotency-key'], 'miniapp-order-v1-' + quoteId)
        writes.push('order:' + session.sessionId)
        await hold('order')
        return send(order)
      }
      if (url.pathname === '/app-api/print/miniapp/demo/payment') {
        assert.equal(url.searchParams.get('orderId'), orderId)
        assert.equal(request.headers().authorization, 'Bearer ' + session.accessToken)
        await hold('receipt')
        return send({
          id: 'late-receipt', orderId, provider: 'SIMULATED', mode: 'DEMO', state: 'SUCCEEDED', simulation: true,
          currency: 'CNY', amount: '0.80', createdAtMs: Date.now(),
          debit: { side: 'DEBIT', account: 'DEMO_CUSTOMER', currency: 'CNY', amount: '0.80' },
          credit: { side: 'CREDIT', account: 'DEMO_STORE', currency: 'CNY', amount: '0.80' }
        })
      }
      if (url.pathname === '/app-api/print/miniapp/demo/cancellation') {
        assert.equal(url.searchParams.get('orderId'), orderId)
        return send({ currentOrder: order, cancellation: null, capability: paidOrder
          ? { action: 'NONE', reason: 'WRITE_DISABLED', expectedVersion: 2 }
          : { action: 'CANCEL_UNPAID', reason: 'UNPAID', expectedVersion: 1 } })
      }
      if (url.pathname === '/app-api/print/miniapp/demo/cancellations') {
        assert.equal(request.headers().authorization, 'Bearer ' + session.accessToken)
        assert.deepEqual(request.postDataJSON(), { orderId, expectedVersion: 1 })
        writes.push('cancel:' + session.sessionId)
        await hold('cancel')
        return send({
          originalResult: {
            id: '00000000-0000-4000-8000-000000000009', orderId, outcome: 'CANCELLED_UNPAID', reason: 'UNPAID',
            orderVersionBefore: 1, orderVersionAfter: 2, createdAtMs: Date.now(), simulation: true,
            taskId: null, taskStateBefore: null, taskStateAfter: null, taskVersionBefore: null, taskVersionAfter: null,
            taskFenceBefore: null, taskFenceAfter: null
          },
          currentOrder: { ...order, orderState: 'CANCELLED', version: 2, cancellationOutcome: 'CANCELLED_UNPAID' },
          refundReceipt: null, reviewSummary: null
        })
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
      if (ordinal === '2' && holdSelection) {
        heldReply()
        await staleRelease
      }
      return send({ ...record, expiresAtMs: session.expiresAtMs })
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

  await candidate('first.pdf').click()
  await extract()
  await page.getByText('服务端解析完成 · 4 页', { exact: true }).waitFor()
  assert.equal(keys[1].ordinal, '2')
  assert.notEqual(keys[1].key, keys[0].key)
  await page.reload()
  await choose('test.zip', bytes)
  await inspect()
  await page.getByText('候选 PDF', { exact: true }).waitFor()
  await candidate('second.pdf').click()
  await extract()
  await page.getByText('服务端解析完成 · 4 页', { exact: true }).waitFor()
  assert.equal(inspected, 2)
  assert.equal(polled, 2)
  assert.equal(keys.length, 3)
  assert.equal(keys[0].ordinal, '7')
  assert.deepEqual(keys[0], keys[2])
  pass('same_key_after_unknown_a_switch_to_b_reload_and_return_to_a')

  await page.getByText('继续设置打印参数', { exact: true }).click()
  await page.waitForURL(url => url.hash.includes('/pages/document-settings/index'))
  await page.getByText('server-document.pdf', { exact: true }).waitFor()
  await page.getByText('3.2 KB · 4 页', { exact: true }).waitFor()
  pass('ready_server_metadata_in_existing_draft')

  await page.getByText('‹', { exact: true }).click()
  holdSelection = true
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
  assert.deepEqual(keys[3], keys[1])
  assert.equal(await page.getByText('继续设置打印参数', { exact: true }).count(), 0)
  assert.equal(polled, 2)
  holdSelection = false
  pass('late_selection_response_does_not_apply_after_new_choice')

  await extract()
  await page.getByText('继续设置打印参数', { exact: true }).click()
  await page.waitForURL(url => url.hash.includes('/pages/document-settings/index'))
  await page.clock.setFixedTime(session.expiresAtMs - 10000)
  session = { ...session, sessionId: 'replacement-session', accessToken: 'B'.repeat(43), expiresAtMs: session.expiresAtMs + 3600000 }
  await page.getByText('确认参数', { exact: true }).click()
  await page.waitForURL(url => url.hash.includes('/pages/archive-print/index'))
  assert.equal(quoteCalls, 0)
  assert.equal(await page.evaluate(() => Object.keys(localStorage).some(key => key.endsWith('miniapp-print-draft-v1'))), false)
  await choose('test.zip', bytes)
  await inspect()
  await page.getByText('候选 PDF', { exact: true }).waitFor()
  assert.equal(await page.locator('.candidate.selected').count(), 0)
  await candidate('first.pdf').click()
  await extract()
  await page.getByText('服务端解析完成 · 4 页', { exact: true }).waitFor()
  assert.equal(keys[5].ordinal, '2')
  assert.notEqual(keys[5].key, keys[1].key)
  pass('same_spa_same_principal_new_session_blocks_old_settings_draft_and_key')

  await page.getByText('继续设置打印参数', { exact: true }).click()
  await page.getByText('确认参数', { exact: true }).click()
  let quoteTimer
  await Promise.race([quoteStart, new Promise((_, reject) => {
    quoteTimer = setTimeout(() => reject(new Error('mock quote did not arrive')), 2500)
  })]).finally(() => clearTimeout(quoteTimer))
  await page.clock.setFixedTime(session.expiresAtMs + 1)
  releaseQuote()
  await page.waitForURL(url => url.hash.includes('/pages/archive-print/index'))
  assert.equal(await page.getByText('创建模拟订单', { exact: true }).count(), 0)
  pass('late_quote_discarded_after_session_expiry_in_same_spa')

  async function openFreshOrder(sessionId) {
    if (session.sessionId !== sessionId) session = {
      ...session, sessionId, accessToken: Buffer.from(sessionId).toString('base64url').padEnd(43, 'A'),
      expiresAtMs: session.expiresAtMs + 3600000
    }
    await choose('test.zip', bytes)
    await inspect()
    await candidate('first.pdf').click()
    await extract()
    await page.getByText('继续设置打印参数', { exact: true }).click()
    await page.getByText('确认参数', { exact: true }).click()
    await page.getByText('创建模拟订单', { exact: true }).waitFor()
    assert.equal(await page.getByText('恢复原模拟订单', { exact: true }).count(), 0)
  }
  const recoveryRecord = prefix => page.evaluate(prefix => {
    const key = Object.keys(localStorage).find(key => key.includes(prefix))
    return key ? localStorage.getItem(key) : null
  }, prefix)
  async function expireAndRelease(gate) {
    await page.clock.setFixedTime(session.expiresAtMs + 1)
    gate.release()
    await page.waitForURL(url => url.hash.includes('/pages/archive-print/index'))
  }

  await openFreshOrder('old-write-session')
  const orderGate = defer('order')
  await page.getByText('创建模拟订单', { exact: true }).click()
  await waitForMock(orderGate.started)
  const oldOrderRecord = 'miniapp-archive-order-pending-v1:old-write-session:'
  const oldOrderSaved = await recoveryRecord(oldOrderRecord)
  assert.ok(oldOrderSaved?.includes('quote-old-write-session'))
  await expireAndRelease(orderGate)
  assert.equal(await recoveryRecord(oldOrderRecord), oldOrderSaved)
  pass('late_order_keeps_original_pending_record_after_same_spa_session_change')

  paidOrder = true
  await openFreshOrder('old-receipt-session')
  const receiptGate = defer('receipt')
  await page.getByText('创建模拟订单', { exact: true }).click()
  await waitForMock(receiptGate.started)
  await page.getByText('old-owner-order', { exact: true }).waitFor()
  await expireAndRelease(receiptGate)
  assert.equal(await page.getByText('收据 late-receipt', { exact: true }).count(), 0)
  assert.ok((await recoveryRecord('miniapp-archive-order-pending-v1:old-receipt-session:'))?.includes('quote-old-receipt-session'))
  pass('late_receipt_discarded_while_original_order_recovery_remains')

  paidOrder = false
  await openFreshOrder('old-cancel-session')
  const cancelGate = defer('cancel')
  await page.getByText('创建模拟订单', { exact: true }).click()
  await page.locator('.cancel-panel .action').filter({ hasText: /^取消订单$/ }).click()
  await page.getByText('确认', { exact: true }).click()
  await waitForMock(cancelGate.started)
  const oldCancelRecord = 'miniapp-archive-cancellation-pending-v1:old-cancel-session:'
  const oldCancelSaved = await recoveryRecord(oldCancelRecord)
  assert.ok(oldCancelSaved?.includes('miniapp-cancel-v1-'))
  // Let the expired cancel handler observe a new same-principal session when it checks ownership.
  await page.clock.setFixedTime(session.expiresAtMs + 1)
  session = { ...session, sessionId: 'new-after-cancel', accessToken: 'C'.repeat(43), expiresAtMs: session.expiresAtMs + 3600000 }
  cancelGate.release()
  await page.waitForURL(url => url.hash.includes('/pages/archive-print/index'))
  assert.equal(await recoveryRecord(oldCancelRecord), oldCancelSaved)
  await openFreshOrder('new-after-cancel')
  await page.getByText('创建模拟订单', { exact: true }).click()
  await page.locator('.cancel-panel .action').filter({ hasText: /^取消订单$/ }).waitFor()
  assert.equal(await page.getByText('重试原取消请求', { exact: true }).count(), 0)
  assert.equal(await recoveryRecord(oldCancelRecord), oldCancelSaved)
  assert.deepEqual(writes, ['order:old-write-session', 'order:old-receipt-session', 'order:old-cancel-session',
    'cancel:old-cancel-session', 'order:new-after-cancel'])
  pass('late_cancel_preserves_old_key_and_new_session_cannot_inherit_or_resend_it')

  assert.deepEqual(mockErrors, [])
  pass('only_isolated_origin_and_frozen_fields')
} catch (error) {
  report.failure = String(error)
  report.mockErrors = mockErrors
  process.exitCode = 1
} finally {
  releaseHeld()
  releaseQuote()
  for (const gate of deferred.values()) gate.release.resolve()
  await browser?.close()
  await new Promise(done => server.close(done))
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify(report, null, 2))
}
