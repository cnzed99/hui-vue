// Isolated frontend contract checks. No network, build output or database access.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const ts = require('typescript')
let response
let requestOptions
let checks = 0
const request = { get: async options => {
  requestOptions = options
  const parsed = options.transformResponse[0](JSON.stringify(response))
  return parsed.data
} }
function load(file, imports) {
  const output = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true }
  }).outputText
  const exports = {}
  vm.runInNewContext(output, { exports, require: id => {
    if (!(id in imports)) throw new Error('Unexpected import: ' + id)
    return imports[id]
  }, Intl, Number, Error }, { filename: file })
  return exports
}
const api = load('src/print-admin/orders/api.ts', {
  '@/config/axios': request, axios: { isAxiosError: error => !!error?.isAxiosError }
})
const view = load('src/print-admin/orders/view.ts', {
  vue: { computed: fn => ({ get value() { return fn() } }) },
  '@/store/modules/user': {}, '@/directives/permission/hasPermi': {}, '../contextStore': {}, './api': api
})
const check = (name, fn) => { fn(); checks++; console.log('PASS ' + name) }
const id = '12345678-1234-4234-8234-123456789012'
const summary = { id, orderNo: 'PD12345678123442348234123456789012', source: 'ADMIN_DEMO', simulation: true,
  currency: 'CNY', totalAmount: '9007199254740993.01', orderState: 'OPEN', paymentState: 'UNPAID',
  refundState: 'NONE', deliveryMode: 'NONE', deliveryState: 'NOT_APPLICABLE', printState: 'NOT_CREATED',
  createdByActorType: 'STAFF', createdByActorId: 1, createdAtMs: 1792742400000 }
const snapshot = { schemaVersion: 1, mode: 'DEMO', currency: 'CNY', aggregationPolicy: 'PER_FILE',
  fileId: 'file-1', fileSha256: 'abc123', priceProfileId: 'profile-1', priceProfileKey: 'A4', priceProfileVersion: '1',
  pagesPerCopy: 3, side: 'DUPLEX', copies: '2', sheetsPerCopy: '2', totalSheets: '4', simplexPricePerSheet: '0.10',
  duplexPricePerSheet: '0.20', tier: null, pricePerSheet: '0.20', totalAmount: '0.80', tierApplied: false,
  appliedTierThreshold: null, createdAtMs: 1792742400000, expiresAtMs: 1792746000000 }
async function failure(name, expected, run) {
  let caught
  try { await run() } catch (error) { caught = error }
  check(name, () => { assert.ok(caught); assert.equal(api.classifyOrderError(caught).kind, expected) })
}
async function main() {
  check('default page', () => assert.equal(view.parsePage(undefined, 1, 1000000), 1))
  check('maximum page', () => assert.equal(view.parsePage('1000000', 1, 1000000), 1000000))
  for (const value of ['0', '-1', '1.5', '1000001', ['2', '3'], null]) {
    check('reject invalid page ' + JSON.stringify(value), () => assert.equal(view.parsePage(value, 1, 1000000), null))
  }
  check('page size maximum', () => assert.equal(view.parsePage('100', 20, 100), 100))
  check('page size overflow', () => assert.equal(view.parsePage('101', 20, 100), null))
  response = { code: 0, data: { list: [summary], total: 1, pageNo: 3, pageSize: 10 } }
  let result = await api.getOrders(3, 10)
  check('exact money string survives beyond float precision', () => assert.equal(result.list[0].totalAmount, '9007199254740993.01'))
  check('only page parameters sent', () => assert.equal(JSON.stringify(requestOptions.params), '{"pageNo":3,"pageSize":10}'))
  response = { code: 1510030000, msg: 'disabled' }
  await failure('disabled is an error, not empty success', 'disabled', () => api.getOrders(1, 20))
  response = { code: 403, msg: 'forbidden' }
  await failure('HTTP200/CommonResult403 is forbidden', 'forbidden', () => api.getOrders(1, 20))
  const raw = { code: 401, msg: 'refresh required' }
  check('401 remains for shared refresh interceptor', () => assert.equal(requestOptions.transformResponse[0](JSON.stringify(raw)).code, 401))
  response = { code: 1510030009, msg: 'not found' }
  await failure('missing order', 'not-found', () => api.getOrder(id))
  response = { code: 1510030010, msg: 'storage unavailable' }
  await failure('storage unavailable', 'service', () => api.getOrder(id))
  response = { code: 0, data: { list: [{ ...summary, createdByActorId: 9007199254740992 }], total: 1, pageNo: 1, pageSize: 20 } }
  await failure('unsafe actor ID rejected', 'invalid-response', () => api.getOrders(1, 20))
  response.data.list = [{ ...summary, totalAmount: 1.65 }]
  await failure('numeric money rejected', 'invalid-response', () => api.getOrders(1, 20))
  response.data.list = [{ ...summary, source: 'P21' }]
  await failure('non-demo source not mislabeled', 'invalid-response', () => api.getOrders(1, 20))
  response = { code: 0, data: { ...summary, items: [{ id: 'item-1', quoteId: 'quote-1', fileId: 'file-1', fileSha256: 'abc123', amount: '0.80', quoteSnapshot: snapshot }] } }
  result = await api.getOrder(id)
  check('snapshot strings and null tier preserved', () => { assert.equal(result.items[0].quoteSnapshot.totalSheets, '4'); assert.equal(result.items[0].amount, '0.80'); assert.equal(result.items[0].quoteSnapshot.tier, null) })
  check('detail endpoint receives no pagination params', () => assert.equal(requestOptions.params, undefined))
  await failure('invalid detail ID rejected', 'invalid-input', () => api.getOrder('../orders'))
  response = { code: 0, data: { list: [{ eventId: 'event-1', orderId: id, type: 'ORDER_CREATED', actorType: 'STAFF', actorId: 1, simulation: true, aggregateVersion: 1, createdAtMs: 1792742400000 }], total: 1, pageNo: 1, pageSize: 20 } }
  result = await api.getOrderEvents(id, 1, 20)
  check('event query keeps simulation provenance', () => assert.equal(result.list[0].simulation, true))
  response.data.list[0].orderId = '87654321-1234-4234-8234-123456789012'
  await failure('events from another order rejected', 'invalid-response', () => api.getOrderEvents(id, 1, 20))
  check('unknown state is not translated as paid/printed', () => assert.equal(view.statusLabel('NEW_UNSUPPORTED_STATE'), '未知状态（NEW_UNSUPPORTED_STATE）'))
  // Batch B extends, rather than replaces, the 26 existing checks above.
  check('payment success explicitly means simulated receipt', () => assert.equal(view.paymentStatusLabel('SUCCEEDED'), '模拟收款成功'))
  check('unpaid label remains unpaid', () => assert.equal(view.paymentStatusLabel('UNPAID'), '未支付'))
  check('queued printing means waiting, not completion', () => assert.equal(view.printStatusLabel('QUEUED'), '等待打印（排队中）'))
  check('print success does not reuse payment success', () => assert.equal(view.printStatusLabel('SUCCEEDED'), '未知打印状态（SUCCEEDED）'))
  check('unknown payment is not silently success', () => assert.equal(view.paymentStatusLabel('NEW_STATE'), '未知支付状态（NEW_STATE）'))
  check('payment event explicitly simulated', () => assert.equal(view.eventTypeLabel('PAYMENT_SIMULATED'), '模拟收款已记录'))
  check('old create event label retained', () => assert.equal(view.eventTypeLabel('ORDER_CREATED'), '订单已创建'))
  const paid = { ...summary, paymentState: 'SUCCEEDED', printState: 'QUEUED' }
  response = { code: 0, data: { list: [paid], total: 1, pageNo: 1, pageSize: 20 } }
  result = await api.getOrders(1, 20)
  check('paid queued response retains independent states and exact amount', () => {
    assert.equal(result.list[0].paymentState, 'SUCCEEDED'); assert.equal(result.list[0].printState, 'QUEUED')
    assert.equal(result.list[0].totalAmount, '9007199254740993.01'); assert.equal(result.list[0].simulation, true)
  })
  response = { code: 0, data: { list: [{ eventId: 'event-2', orderId: id, type: 'PAYMENT_SIMULATED', actorType: 'STAFF', actorId: 1, simulation: true, aggregateVersion: 2, createdAtMs: 1792742401000 }], total: 1, pageNo: 1, pageSize: 20 } }
  result = await api.getOrderEvents(id, 1, 20)
  check('payment event v2 accepted with simulation provenance', () => {
    assert.equal(result.list[0].type, 'PAYMENT_SIMULATED'); assert.equal(result.list[0].aggregateVersion, 2); assert.equal(result.list[0].simulation, true)
  })
  for (const [name, object] of [['DemoOrders.vue', 'order'], ['DemoOrderDetail.vue', 'data']]) {
    const source = fs.readFileSync('src/print-admin/orders/' + name, 'utf8')
    check(name + ' binds payment and print through distinct formatters', () => {
      assert.ok(source.includes('paymentStatusLabel(' + object + '.paymentState)'))
      assert.ok(source.includes('printStatusLabel(' + object + '.printState)'))
      assert.ok(!source.includes('statusLabel(' + object + '.paymentState)'))
      assert.ok(!source.includes('statusLabel(' + object + '.printState)'))
    })
  }
  check('detail binds event type through event formatter', () => assert.ok(fs.readFileSync('src/print-admin/orders/DemoOrderDetail.vue', 'utf8').includes('eventTypeLabel(event.type)')))
  console.log('PASS ' + checks + ' isolated frontend contract checks; no real HTTP/browser/build acceptance.')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
