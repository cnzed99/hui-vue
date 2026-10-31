const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')

const sourcePath = path.resolve(__dirname, '../src/api/catalog.ts')
const source = fs.readFileSync(sourcePath, 'utf8').replaceAll('import.meta.env', '{}')
const output = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  reportDiagnostics: true
})
assert.equal((output.diagnostics || []).filter(item => item.category === ts.DiagnosticCategory.Error).length, 0)
const moduleExports = {}
new Function('exports', output.outputText)(moduleExports)

const valid = { code: 0, data: moduleExports.localCatalog }
assert.equal(moduleExports.parseMiniappCatalogEnvelope(valid), moduleExports.localCatalog)
assert.equal(moduleExports.parseMiniappCatalogEnvelope({ ...valid, data: { ...valid.data, methods: [null] } }), null)
assert.equal(moduleExports.parseMiniappCatalogEnvelope({ ...valid, data: { ...valid.data, demoLimits: {} } }), null)
assert.equal(moduleExports.parseMiniappCatalogEnvelope({ ...valid, data: { ...valid.data, demoLimits: { maxFileBytes: -1, maxPages: 20, retentionMinutes: 60 } } }), null)
console.log(JSON.stringify({ status: 'PASS_MINIAPP_CATALOG_CONTRACT', valid: 1, invalidFallback: 3 }))
