const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')
const source = fs.readFileSync(path.join(root, 'src/print-admin/library/settingsModel.ts'), 'utf8')
const exportsModel = {}
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText, { exports: exportsModel })
const model = exportsModel
let count = 0
const ok = (test) => { test(); count++ }
const configured = { version: '9007199254740993', configured: true, name: '演示文库店', description: '文库描述', miniProgramPathStatus: 'NOT_CONFIGURED', miniProgramPath: null }
ok(() => assert.equal(model.parseLibrarySettings(configured).version, configured.version))
ok(() => assert.equal(model.validateSettings('演示文库店', '描述'), ''))
ok(() => assert.notEqual(model.validateSettings('短', '描述'), ''))
ok(() => assert.notEqual(model.validateSettings('演示文库店', '字'.repeat(31)), ''))
ok(() => assert.equal(model.validateSettings('😀😀😀', '描述'), ''))
ok(() => assert.throws(() => model.parseLibrarySettings({ ...configured, miniProgramPath: '/forged' })))
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc')
const filename = path.join(root, 'src/print-admin/library/LibrarySettings.vue')
const parsed = parse(fs.readFileSync(filename, 'utf8'), { filename })
ok(() => assert.deepEqual(parsed.errors, []))
ok(() => assert.ok(compileScript(parsed.descriptor, { id: 'p14' }).content.includes('validateSettings')))
ok(() => assert.deepEqual(compileTemplate({ source: parsed.descriptor.template.content, filename, id: 'p14' }).errors, []))
console.log(JSON.stringify({ kind: 'P14_FRONTEND_SOURCE_MODEL', checksPassed: count, httpExecuted: false, browserExecuted: false }))
