'use strict'
const fs = require('fs')
const path = require('path')
const esbuild = require('../../src/miniapp/node_modules/esbuild')

const root = path.resolve(__dirname, '../..')
const source = path.join(root, 'src/miniapp/src/api/demoOrders.ts')
const output = path.join(root, 'runtime/build-tmp/m4-demo-orders-parser.cjs')
fs.mkdirSync(path.dirname(output), { recursive: true })
esbuild.buildSync({ entryPoints: [source], outfile: output, bundle: true, platform: 'node', format: 'cjs',
  define: { 'import.meta.env.VITE_APP_API_BASE': 'undefined' }, logLevel: 'silent' })
try {
  delete require.cache[require.resolve(output)]
  const { parseDemoOrderEnvelope } = require(output)
  const base = {
    id: '11111111-1111-1111-1111-111111111111', orderNo: 'PMTEST', source: 'MINIAPP_DEMO',
    simulation: true, currency: 'CNY', totalAmount: '0.20', orderState: 'OPEN',
    refundState: 'NONE', createdAtMs: 1, updatedAtMs: 1,
    item: { id: '22222222-2222-2222-2222-222222222222',
      quoteId: '33333333-3333-3333-3333-333333333333',
      fileId: '44444444-4444-4444-4444-444444444444', fileSha256: 'a'.repeat(64), amount: '0.20',
      quoteSnapshot: { mode: 'DEMO', totalAmount: '0.20' } }
  }
  const accepts = [
    ['NOT_CREATED', 'UNPAID', 1], ['NOT_CREATED', 'SUCCEEDED', 2],
    ['QUEUED', 'SUCCEEDED', 3], ['CLAIMED', 'SUCCEEDED', 4], ['ACCEPTED', 'SUCCEEDED', 5],
    ['EXECUTING', 'SUCCEEDED', 6], ['SUCCEEDED', 'SUCCEEDED', 7],
    ['FAILED', 'SUCCEEDED', 7], ['RESULT_UNKNOWN', 'SUCCEEDED', 7]
  ]
  const rejects = [
    ['NOT_CREATED', 'SUCCEEDED', 3], ['QUEUED', 'UNPAID', 3],
    ['QUEUED', 'SUCCEEDED', 2], ['PRINTED', 'SUCCEEDED', 3]
  ]
  for (const [printState, paymentState, version] of accepts) {
    const parsed = parseDemoOrderEnvelope({ code: 0, data: { ...base, printState, paymentState, version } })
    if (!parsed) throw new Error(`expected_accept:${printState}:${paymentState}:${version}`)
  }
  for (const [printState, paymentState, version] of rejects) {
    const parsed = parseDemoOrderEnvelope({ code: 0, data: { ...base, printState, paymentState, version } })
    if (parsed) throw new Error(`expected_reject:${printState}:${paymentState}:${version}`)
  }
  process.stdout.write(JSON.stringify({ passed: true, accepted: accepts.length, rejected: rejects.length }) + '\n')
} finally {
  fs.rmSync(output, { force: true })
}
