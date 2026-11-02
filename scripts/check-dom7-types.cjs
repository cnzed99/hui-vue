const fs = require('fs')
const path = require('path')
const ts = require('typescript')

const root = process.cwd()
const localPlaceholder = path.join(root, 'types/dom7.d.ts')
if (fs.existsSync(localPlaceholder)) throw Error('Local dom7 ambient placeholder still exists')
const config = ts.readConfigFile(path.join(root, 'tsconfig.json'), ts.sys.readFile)
if (config.error) throw Error(ts.flattenDiagnosticMessageText(config.error.messageText, '\n'))
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root)
const resolved = ts.resolveModuleName('dom7', path.join(root, 'src/views/bpm/model/form/PrintTemplate/module/utils/dom.ts'), parsed.options, ts.sys).resolvedModule
const packageJson = require.resolve('dom7/package.json')
const metadata = JSON.parse(fs.readFileSync(packageJson, 'utf8'))
const officialDeclaration = fs.realpathSync(path.join(path.dirname(packageJson), metadata.types))
if (metadata.version !== '4.0.6' || !resolved || fs.realpathSync(resolved.resolvedFileName) !== officialDeclaration) {
  throw Error('dom7 did not resolve to locked official declarations')
}
const directory = fs.mkdtempSync(path.join(process.env.TMPDIR, 'dom7-official-types-'))
fs.symlinkSync(path.join(root, 'node_modules'), path.join(directory, 'node_modules'), 'dir')
const fixture = path.join(directory, 'invalid-length.ts')
fs.writeFileSync(fixture, "import type { Dom7Array } from 'dom7';\ndeclare const items: Dom7Array;\nitems.length = 'invalid';\n")
const program = ts.createProgram([fixture], { ...parsed.options, noEmit: true, types: [] })
const diagnostics = ts.getPreEmitDiagnostics(program)
const expected = diagnostics.filter(d => d.code === 2322 && d.file?.fileName === fixture)
if (expected.length !== 1 || diagnostics.length !== 1) {
  throw Error('Official type fixture did not produce exactly TS2322: ' + diagnostics.map(d => d.code).join(','))
}
console.log(JSON.stringify({ status: 'PASS', packageVersion: metadata.version, officialDeclaration,
  resolvedDeclaration: resolved.resolvedFileName, diagnostic: { code: expected[0].code, text: ts.flattenDiagnosticMessageText(expected[0].messageText, '\n') } }))
