const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

let source = fs.readFileSync(path.join(__dirname, 'api.ts'), 'utf8')
source = source.replace("import request from '@/config/axios'", 'const request = {}')
source = source.replace("import { isAxiosError } from 'axios'", 'const isAxiosError = () => false')
source += '\nexport const parseFixtureDetail = miniDetail; export const parseFixtureSummary = miniSummary\n'
const output = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText
const sandbox = { exports: {} }
vm.runInNewContext(output, sandbox, { filename: 'm8-api-contract.js' })
const parse = sandbox.exports.parseFixtureDetail
const summary = sandbox.exports.parseFixtureSummary
const id = number => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const clone = value => JSON.parse(JSON.stringify(value))
const now = 1700000000000
const money = '0.20'
const receipt = { id: id(5), provider: 'SIMULATED', mode: 'DEMO', state: 'SUCCEEDED', simulation: true,
  currency: 'CNY', amount: money, createdAtMs: now }
const order = {
  id: id(1), orderNo: 'MO-FIXTURE', source: 'MINIAPP_DEMO', simulation: true,
  currency: 'CNY', totalAmount: money, orderState: 'OPEN', paymentState: 'UNPAID',
  refundState: 'NONE', printState: 'NOT_CREATED', version: 1, principalId: id(2),
  createdAtMs: now, updatedAtMs: now, cancellationOutcome: null,
  item: { id: id(3), quoteId: id(4), fileId: id(6), fileSha256: 'a'.repeat(64), amount: money,
    quoteSnapshot: { schemaVersion: 1, mode: 'DEMO', currency: 'CNY', aggregationPolicy: 'PER_FILE',
      fileId: id(6), fileSha256: 'a'.repeat(64), priceProfileId: id(7), priceProfileKey: 'fixture',
      priceProfileVersion: '1', pagesPerCopy: 1, side: 'SIMPLEX', copies: '1', sheetsPerCopy: '1',
      totalSheets: '1', simplexPricePerSheet: money, duplexPricePerSheet: money, pricePerSheet: money,
      totalAmount: money, tierApplied: false, appliedTierThreshold: null, createdAtMs: now, expiresAtMs: now + 1000 } },
  payment: null, printTask: null, events: [], cancellation: null
}
const original = {
  id: id(8), orderId: order.id, outcome: 'CANCELLED_UNPAID', reason: 'UNPAID_CANCELLED',
  orderVersionBefore: 1, orderVersionAfter: 2, taskId: null, taskStateBefore: null, taskStateAfter: null,
  taskVersionBefore: null, taskVersionAfter: null, taskFenceBefore: null, taskFenceAfter: null,
  simulation: true, createdAtMs: now
}
function record(value, result, refundReceipt = null, reviewSummary = null) {
  value.cancellationOutcome = result.outcome
  value.cancellation = { originalResult: result, currentOrder: clone(value), refundReceipt, reviewSummary }
  return value
}
const unpaid = record({ ...clone(order), orderState: 'CANCELLED', version: 2 }, clone(original))
const refund = { id: id(9), cancellationId: original.id, paymentReceiptId: receipt.id, orderId: order.id,
  provider: 'SIMULATED', mode: 'DEMO', state: 'SUCCEEDED', simulation: true, currency: 'CNY', amount: money, createdAtMs: now,
  debit: { side: 'DEBIT', account: 'DEMO_STORE', currency: 'CNY', amount: money },
  credit: { side: 'CREDIT', account: 'DEMO_CUSTOMER', currency: 'CNY', amount: money } }
const refunded = record({ ...clone(order), orderState: 'CANCELLED', paymentState: 'SUCCEEDED', refundState: 'SUCCEEDED', version: 3, payment: receipt },
  { ...clone(original), outcome: 'CANCELLED_REFUNDED', orderVersionBefore: 2, orderVersionAfter: 3 }, refund)
const task = { id: id(10), jobId: 'PJ' + 'A'.repeat(32), orderId: order.id, orderItemId: order.item.id,
  state: 'EXECUTING', version: 4, fence: 1, leaseId: id(11), leaseUntilMs: now + 1000,
  plannedCopies: '1', plannedSheets: '1', fileId: order.item.fileId, fileSha256: order.item.fileSha256,
  eventSeq: 4, createdAtMs: now, updatedAtMs: now, receipt: null }
const manualOriginal = { ...clone(original), outcome: 'MANUAL_REVIEW_REQUIRED', orderVersionBefore: 4, orderVersionAfter: 4,
  taskId: task.id, taskStateBefore: 'CLAIMED', taskStateAfter: 'CLAIMED', taskVersionBefore: 2, taskVersionAfter: 2,
  taskFenceBefore: 1, taskFenceAfter: 1 }
const manual = record({ ...clone(order), paymentState: 'SUCCEEDED', printState: 'EXECUTING', version: 6, payment: receipt, printTask: task },
  manualOriginal, null, { cancellationId: original.id, status: 'PENDING', taskId: task.id, taskStateAtRequest: 'CLAIMED',
    taskVersionAtRequest: 2, fenceAtRequest: 1, createdAtMs: now })
const cancelledTask = record({ ...clone(refunded), printState: 'CANCELLED', version: 4,
  printTask: { ...clone(task), state: 'CANCELLED', version: 2, eventSeq: 2, fence: 0, leaseId: null, leaseUntilMs: null } },
  { ...clone(original), outcome: 'CANCELLED_REFUNDED', orderVersionBefore: 3, orderVersionAfter: 4,
    taskId: task.id, taskStateBefore: 'QUEUED', taskStateAfter: 'CANCELLED', taskVersionBefore: 1,
    taskVersionAfter: 2, taskFenceBefore: 0, taskFenceAfter: 0 }, refund)
const cases = [
  ['legacy_open', order, true],
  ['unpaid_cancelled', unpaid, true],
  ['paid_refunded_without_task', refunded, true],
  ['cancelled_unclaimed_task_without_receipt', cancelledTask, true],
  ['manual_snapshot_survives_live_task_progress', manual, true],
  ['refund_wrong_amount', (() => { const value = clone(refunded); value.cancellation.refundReceipt.amount = '99'; return value })(), false],
  ['refund_wrong_payment_binding', (() => { const value = clone(refunded); value.cancellation.refundReceipt.paymentReceiptId = id(99); return value })(), false],
  ['refund_unbalanced_journal', (() => { const value = clone(refunded); value.cancellation.refundReceipt.credit.amount = '0.10'; return value })(), false],
  ['cancelled_missing_record', { ...clone(unpaid), cancellation: null }, false],
  ['manual_forged_refund', (() => { const value = clone(manual); value.cancellation.refundReceipt = refund; return value })(), false],
  ['cancelled_task_with_fence', (() => { const value = clone(cancelledTask); value.printTask.fence = 1; return value })(), false],
  ['torn_current_order', (() => { const value = clone(refunded); value.cancellation.currentOrder.version = 2; return value })(), false]
]
for (const [name, value, accepted] of cases) {
  if (accepted) assert.doesNotThrow(() => parse(value), name)
  else assert.throws(() => parse(value), name)
  console.log(name + '=PASS')
}
assert.throws(() => summary({ ...clone(order), cancellationOutcome: 'ARBITRARY' }))
console.log('unknown_list_outcome_rejected=PASS')
assert.throws(() => summary({ ...clone(unpaid), cancellationOutcome: 'MANUAL_REVIEW_REQUIRED' }))
console.log('mismatched_list_outcome_rejected=PASS')
console.log(`m8_admin_contract=${cases.length + 2}/${cases.length + 2}_PASS`)
