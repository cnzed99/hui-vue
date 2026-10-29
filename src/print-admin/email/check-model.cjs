const assert = require('node:assert/strict')
const fs = require('node:fs')
const ts = require('typescript')
const vm = require('node:vm')
const source = fs.readFileSync(__dirname + '/model.ts', 'utf8')
const mod = { exports: {} }
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText,
  { exports: mod.exports, module: mod, BigInt, Error, TextEncoder })
const m = mod.exports
const settings = { version: '1', configured: true, username: 'printer@example.test', smtpHost: 'smtp.example.test', smtpPort: 465,
  passwordConfigured: true, sendingStatus: 'SIMULATED_NOT_SENT', tlsStatus: 'UNCONFIRMED_NOT_CONFIGURED' }
assert.equal(m.parseEmailSettings(settings).passwordConfigured, true)
assert.equal(m.validateEmailDraft({ username: 'printer@example.test', password: '', smtpHost: 'smtp.example.test', smtpPort: '465' }, false), '')
assert.notEqual(m.validateEmailDraft({ username: 'printer@example.test', password: '', smtpHost: 'smtp.example.test', smtpPort: '70000' }, true), '')
assert.throws(() => m.parseEmailSettings({ ...settings, password: 'secret' }))
assert.throws(() => m.parseEmailSettings({ ...settings, sendingStatus: 'SENT' }))
const vue = fs.readFileSync(__dirname + '/EmailSettings.vue', 'utf8')
assert.ok(vue.includes("permitted('print:store:query')") && vue.includes("permitted('print:store:update')"))
assert.ok(vue.includes('type="password"') && vue.includes('真实邮件发送及TLS/SSL选项尚未接入'))
assert.ok(vue.includes('if (ticket !== generation) return') && vue.includes('needsReload.value = true'))
console.log(JSON.stringify({ kind: 'P35_FRONTEND_CONTRACT', result: 'PASS', browser: false, http: false, smtp: false }))
