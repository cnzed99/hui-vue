// Isolated frontend contract checks; no network, database, Vite or file fixtures.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
let response, options, checks = 0, requestCount = 0
const request = {}
for (const method of ['get', 'post']) request[method] = async value => {
  requestCount++
  options = { method, ...value }
  return value.transformResponse[0](JSON.stringify(response)).data
}
const source = fs.readFileSync('src/print-admin/workflow/api.ts', 'utf8')
const result = ts.transpileModule(source, { reportDiagnostics: true, compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true } })
assert.equal((result.diagnostics || []).length, 0)
const exportsObject = {}
vm.runInNewContext(result.outputText, { exports: exportsObject, require: id => {
  if (id === '@/config/axios') return request
  if (id === 'axios') return { isAxiosError: v => !!v?.isAxiosError }
  throw new Error('Unexpected import: ' + id)
}, FormData, Date, Number, Error }, { filename: 'workflow/api.ts' })
const api = exportsObject
const check = (name, fn) => { fn(); checks++; console.log('PASS ' + name) }
const id = '12345678-1234-4234-8234-123456789012'
const second = '87654321-1234-4234-8234-123456789012'
const file = { id, displayName: 'test.pdf', mediaType: 'application/pdf', sizeBytes: 1024, pageCount: 3, status: 'READY', failureCode: null, createdAt: '2026-10-23T00:00:00Z', expiresAt: '2026-10-23T01:00:00Z', contentAvailable: true }
const snapshot = { schemaVersion: 1, mode: 'DEMO', currency: 'CNY', aggregationPolicy: 'PER_FILE_ALL_COPIES', fileId: id, fileSha256: 'a'.repeat(64), priceProfileId: second, priceProfileKey: 'TEST', priceProfileVersion: 'v1', pagesPerCopy: 3, side: 'DUPLEX', copies: '2', sheetsPerCopy: '2', totalSheets: '4', simplexPricePerSheet: '0.123', duplexPricePerSheet: '0.123', tier: null, pricePerSheet: '0.123', totalAmount: '9007199254740993.123', tierApplied: false, appliedTierThreshold: null, createdAtMs: 1792713600000, expiresAtMs: 1792717200000 }
const order = { id, orderNo: 'PD123', source: 'ADMIN_DEMO', simulation: true, currency: 'CNY', totalAmount: '0.369', paymentState: 'UNPAID', printState: 'NOT_CREATED' }
const receipt = { id: second, orderId: id, provider: 'SIMULATED', state: 'SUCCEEDED', simulation: true, currency: 'CNY', amount: '0.369', actorType: 'STAFF', actorId: 1, createdAtMs: 1792713600000, jobIds: [id] }
async function fails(name, kind, fn) {
  let error
  try { await fn() } catch (v) { error = v }
  check(name, () => { assert.ok(error); assert.equal(api.classifyWorkflowError(error).kind, kind) })
}
async function main() {
  check('missing deployment profile remains absent', () => assert.equal(api.configuredProfile(undefined), null))
  check('invalid profile has no fallback', () => assert.equal(api.configuredProfile('demo'), null))
  check('profile requires lowercase canonical UUID', () => assert.equal(api.configuredProfile('ABCDEFAB-1234-4234-8234-123456789012'), null))
  check('valid deployment UUID accepted', () => assert.equal(api.configuredProfile(id), id))
  for (const value of ['0', '-1', '1.5', '01', '1e3', '9007199254740992', ' 2']) check('reject copies ' + value, () => assert.equal(api.parseCopies(value), null))
  check('safe maximum copies preserved', () => assert.equal(api.parseCopies('9007199254740991'), Number.MAX_SAFE_INTEGER))
  response = { code: 0, data: file }
  const metadata = await api.getFileMetadata(id)
  check('metadata uses ISO times and server pages', () => { assert.equal(metadata.createdAt, file.createdAt); assert.equal(metadata.pageCount, 3) })
  check('metadata read has no owner/store/query', () => { assert.equal(options.params, undefined); assert.equal(options.url, '/print/files/' + id) })
  response.data = { ...file, expiresAt: 1792717200000 }
  const milliseconds = await api.getFileMetadata(id)
  check('metadata milliseconds normalized to existing ISO model', () => assert.equal(milliseconds.expiresAt, new Date(1792717200000).toISOString()))
  // Real Jackson Instant wire shape, through JSON response decoding and the original upload/read methods.
  const createdMs = Date.parse(file.createdAt), expiresMs = createdMs + 3600000
  const uploadWire = { ...file, status: 'UPLOADED', pageCount: null, contentAvailable: false, createdAt: createdMs, expiresAt: expiresMs }
  response = { code: 0, data: uploadWire }
  const beforeUpload = requestCount
  const uploaded = await api.uploadPdf(new Blob(['%PDF-1.4'], { type: 'application/pdf' }), 'upload-wire-key')
  check('numeric wire upload accepted once with original operation key', () => {
    assert.equal(requestCount, beforeUpload + 1); assert.equal(options.headers['Idempotency-Key'], 'upload-wire-key')
    assert.equal(options.url, '/print/files'); assert.equal(uploaded.id, id); assert.equal(uploaded.status, 'UPLOADED')
    assert.equal(uploaded.pageCount, null); assert.equal(uploaded.contentAvailable, false)
  })
  check('numeric wire times preserve exact server expiry and input', () => {
    assert.equal(Date.parse(uploaded.createdAt), createdMs); assert.equal(Date.parse(uploaded.expiresAt), expiresMs)
    assert.equal(Date.parse(uploaded.expiresAt) - Date.parse(uploaded.createdAt), 3600000)
    assert.equal(uploadWire.createdAt, createdMs); assert.equal(uploadWire.expiresAt, expiresMs)
  })
  response.data = { ...uploadWire, status: 'READY', pageCount: 3, contentAvailable: true }
  const parsedFile = await api.getFileMetadata(id)
  check('numeric wire manual refresh reaches READY with three server pages', () => {
    assert.equal(options.method, 'get'); assert.equal(options.url, '/print/files/' + id)
    assert.equal(parsedFile.status, 'READY'); assert.equal(parsedFile.pageCount, 3); assert.equal(parsedFile.contentAvailable, true)
    assert.equal(parsedFile.expiresAt, uploaded.expiresAt)
  })
  check('numeric expired time is preserved rather than extended', () => assert.equal(Date.parse(api.parseFile({ ...file, expiresAt: 1 }).expiresAt), 1))
  for (const value of [0, -1, 8640000000000000, -8640000000000000]) {
    check('legal safe epoch-ms boundary ' + value, () => assert.equal(Date.parse(api.parseFile({ ...file, createdAt: value }).createdAt), value))
  }
  for (const [label, value] of [['numeric-string', String(createdMs)], ['NaN', NaN], ['positive-infinity', Infinity], ['negative-infinity', -Infinity], ['unsafe', Number.MAX_SAFE_INTEGER + 1], ['date-overflow', 8640000000000001], ['negative-date-overflow', -8640000000000001], ['fractional', createdMs + 0.5], ['null', null], ['timezone-less', '2026-10-23T00:00:00']]) {
    for (const field of ['createdAt', 'expiresAt']) {
      await fails('reject invalid direct file time ' + field + ':' + label, 'invalid-response', () => api.parseFile({ ...file, [field]: value }))
    }
  }
  response.data = { ...file, expiresAt: String(expiresMs) }
  await fails('wire numeric string does not coerce to a timestamp', 'invalid-response', () => api.getFileMetadata(id))
  response.data = { ...file, status: 'READY', pageCount: null }
  await fails('ready without server page count rejected', 'invalid-response', () => api.getFileMetadata(id))
  response = { code: 0, data: { id, status: 'VALID', snapshot } }
  const input = { fileId: id, priceProfileId: second, copies: 2, side: 'DUPLEX', amount: '1', storeId: 9 }
  const quote = await api.createQuote(input, 'same-key')
  check('quote money remains exact string', () => assert.equal(quote.snapshot.totalAmount, '9007199254740993.123'))
  check('quote sends exactly frozen body fields', () => assert.equal(JSON.stringify(options.data), JSON.stringify({ fileId: id, priceProfileId: second, copies: 2, side: 'DUPLEX' })))
  check('quote operation key forwarded verbatim', () => assert.equal(options.headers['Idempotency-Key'], 'same-key'))
  response.data = { id, status: 'VALID', snapshot: { ...snapshot, totalAmount: 0.369 } }
  await fails('numeric quote money rejected', 'invalid-response', () => api.createQuote(input, 'same-key'))
  response.data = { id, status: 'VALID', snapshot: { ...snapshot, fileId: second } }
  await fails('quote must match requested file', 'invalid-response', () => api.createQuote(input, 'same-key'))
  response = { code: 0, data: order }
  await api.createOrder(id, 'order-key')
  check('order sends quoteId only', () => assert.equal(JSON.stringify(options.data), JSON.stringify({ quoteId: id })))
  response = { code: 0, data: receipt }
  const paid = await api.simulatePayment(id, 'payment-key')
  check('simulation receipt retains original exact money and job ID', () => { assert.equal(paid.amount, '0.369'); assert.equal(paid.jobIds[0], id) })
  check('payment sends empty body and original key', () => { assert.equal(JSON.stringify(options.data), '{}'); assert.equal(options.headers['Idempotency-Key'], 'payment-key') })
  await api.getPayment(id)
  check('receipt query has no owner/store or pagination', () => { assert.equal(options.method, 'get'); assert.equal(options.params, undefined) })
  response.data = { ...receipt, orderId: second }
  await fails('receipt from different order rejected', 'invalid-response', () => api.simulatePayment(id, 'payment-key'))
  response.data = { ...receipt, simulation: false }
  await fails('non-simulated receipt rejected', 'invalid-response', () => api.simulatePayment(id, 'payment-key'))
  response = { code: 1510020007 }
  await fails('expired quote distinct', 'quote-expired', () => api.createQuote(input, 'same-key'))
  response = { code: 1510040000 }
  await fails('disabled distinct from success', 'disabled', () => api.simulatePayment(id, 'payment-key'))
  response = { code: 403 }
  await fails('HTTP200 business403 is forbidden', 'forbidden', () => api.getFileMetadata(id))
  check('401 keeps shared token refresh path', () => assert.equal(api.readWorkflowResponse('{"code":401}').code, 401))
  check('network result remains uncertain', () => { const e = api.classifyWorkflowError({ isAxiosError: true }); assert.equal(e.kind, 'network'); assert.equal(e.uncertain, true) })
  await fails('bad key rejected before request', 'invalid-input', () => api.createOrder(id, 'has space'))
  const component = fs.readFileSync('src/print-admin/workflow/DemoWorkflow.vue', 'utf8')
  const parsed = parse(component, { filename: 'DemoWorkflow.vue' })
  check('workflow SFC parses', () => assert.equal(parsed.errors.length, 0))
  const compiled = compileScript(parsed.descriptor, { id: 'workflow-static' })
  const template = compileTemplate({ source: parsed.descriptor.template.content, filename: 'DemoWorkflow.vue', id: 'workflow-static', compilerOptions: { bindingMetadata: compiled.bindings } })
  check('workflow template compiles', () => assert.equal(template.errors.length, 0))
  check('unknown outcome retains key and immutable payload', () => {
    assert.ok(component.includes('if (!orderOperation) orderOperation ='))
    assert.ok(component.includes('createOrder(orderOperation.quoteId, orderOperation.key)'))
    assert.ok(component.includes('simulatePayment(paymentOperation.orderId, paymentOperation.key)'))
    assert.ok(component.includes("pending.value === 'order' || (!pending.value && !quoteExpired.value)"))
  })
  check('pending navigation protected within current tab', () => assert.ok(component.includes('onBeforeRouteLeave(() => !pending.value && !busy.value)')))
  check('queued display does not claim paper output', () => assert.ok(component.includes('等待打印（排队中）。终端回执尚未实现。')))
  check('no rounding or client-side amount calculation', () => { assert.ok(!source.includes('toFixed')); assert.ok(!component.includes('toFixed')); assert.ok(component.includes('{{ quote.snapshot.totalAmount }} 元')) })
  check('53-entry original menu not used as workflow completion', () => { const routes = fs.readFileSync('src/print-admin/routes.ts', 'utf8'); assert.ok(routes.includes("path: 'demo-workflow'")); assert.ok(routes.includes("'PrintDemoWorkflow'")) })
  console.log('PASS ' + checks + ' isolated workflow checks; NOT real HTTP/browser/build acceptance.')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
