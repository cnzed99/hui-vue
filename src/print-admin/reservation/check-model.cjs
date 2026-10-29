const assert = require('node:assert/strict')
const fs = require('node:fs')
const ts = require('typescript')
const vm = require('node:vm')
const source = fs.readFileSync(__dirname + '/model.ts', 'utf8')
const mod = { exports: {} }
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText,
  { exports: mod.exports, module: mod, BigInt, Error })
const m = mod.exports
const settings = { version: '1', configured: true, pickupEnabled: true, timedDeliveryEnabled: false, vipDeliveryEnabled: true,
  pickupCodePrefix: 'A1', automaticPrintEnabled: true, automaticPrintExecutionStatus: 'SIMULATED_CONFIG_ONLY', storeSettingsLinkStatus: 'PENDING_CONFIRMATION' }
assert.equal(m.parseReservationSettings(settings).pickupCodePrefix, 'A1')
assert.equal(m.validatePrefix(''), '')
assert.notEqual(m.validatePrefix('中A'), '')
assert.throws(() => m.parseReservationSettings({ ...settings, automaticPrintExecutionStatus: 'PRINTED' }))
assert.throws(() => m.parseReservationSettings({ ...settings, configured: false }))
const vue = fs.readFileSync(__dirname + '/ReservationSettings.vue', 'utf8')
assert.ok(vue.includes("permitted('print:store:query')") && vue.includes("permitted('print:store:update')"))
assert.ok(vue.includes(':disabled="!canUpdate || busy || needsReload"') && vue.includes('v-if="canUpdate"'))
assert.ok(vue.includes('if (ticket !== generation) return') && vue.includes('needsReload.value = true'))
console.log(JSON.stringify({ kind: 'P06_FRONTEND_CONTRACT', result: 'PASS', browser: false, http: false }))
