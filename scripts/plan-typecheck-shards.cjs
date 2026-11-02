const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { createRequire } = require('module')
const ts = require('typescript')
const vueRequire = createRequire(require.resolve('vue-tsc/package.json'))
const sfc = vueRequire('@vue/compiler-sfc')

const [rootArg, graphPath, outputArg] = process.argv.slice(2)
if (!rootArg || !graphPath || !outputArg) throw Error('usage: planner root list-files-output output-dir')
const root = fs.realpathSync(rootArg)
const output = path.resolve(outputArg)
const configPath = path.join(root, 'tsconfig.json')
const raw = ts.readConfigFile(configPath, ts.sys.readFile)
if (raw.error) throw Error(ts.flattenDiagnosticMessageText(raw.error.messageText, '\n'))
const parsed = ts.parseJsonConfigFileContent(raw.config, ts.sys, root, {}, configPath, undefined,
  [{ extension: '.vue', isMixedContent: true, scriptKind: ts.ScriptKind.Deferred }])
if (parsed.errors.length) throw Error(ts.formatDiagnosticsWithColorAndContext(parsed.errors, {
  getCanonicalFileName: x => x, getCurrentDirectory: () => root, getNewLine: () => '\n'
}))
const normalize = file => path.resolve(file).replaceAll('\\', '/')
const roots = [...new Set(parsed.fileNames.map(normalize))].sort()
if (!roots.length || !roots.some(file => file.endsWith('.vue'))) throw Error('Missing Vue root coverage')
const graph = [...new Set(fs.readFileSync(graphPath, 'utf8').split(/\r?\n/).filter(line => path.isAbsolute(line) && fs.existsSync(line)).map(normalize))].sort()
const missing = roots.filter(file => !graph.includes(file))
if (missing.length) throw Error('vue-tsc dependency graph omitted roots: ' + missing.slice(0, 10).join(','))
const reasons = {}
function classify(file) {
  const text = fs.readFileSync(file, 'utf8')
  let units = [{ text, implicitModule: false }]
  if (file.endsWith('.vue')) {
    const result = sfc.parse(text, { filename: file })
    if (result.errors.length) return ['UNCLASSIFIED_VUE_PARSE_ERROR']
    if (result.descriptor.script?.src) return ['EXTERNAL_VUE_SCRIPT_UNCLASSIFIED']
    units = [result.descriptor.script && { text: result.descriptor.script.content, implicitModule: false },
      result.descriptor.scriptSetup && { text: result.descriptor.scriptSetup.content, implicitModule: true }].filter(Boolean)
    // script setup is always a component module; ordinary script needs classification.
  }
  const found = new Set()
  for (const unit of units) {
    const ast = ts.createSourceFile(file.endsWith('.vue') ? file + '.ts' : file, unit.text, ts.ScriptTarget.Latest, true)
    if (ast.parseDiagnostics.length) found.add('UNCLASSIFIED_PARSE_DIAGNOSTIC')
    if (!unit.implicitModule && !ts.isExternalModule(ast)) found.add('GLOBAL_SCRIPT_OR_AMBIENT_DECLARATION')
    function visit(node) {
      if (ts.isModuleDeclaration(node)) found.add('GLOBAL_AMBIENT_OR_MODULE_AUGMENTATION')
      ts.forEachChild(node, visit)
    }
    visit(ast)
  }
  return [...found]
}
for (const file of graph) {
  const found = classify(file)
  if (found.length) reasons[file] = found
}
const shared = Object.keys(reasons).sort()
const ordinary = roots.filter(file => !reasons[file])
const representative = ordinary.filter(file => file === normalize(path.join(root, 'src/main.ts')) || file.startsWith(normalize(path.join(root, 'src/router')) + '/'))
const representativeSet = new Set(representative)
const remaining = ordinary.filter(file => !representativeSet.has(file))
const shards = [{ id: 'representative-main-router', roots: representative }]
for (let i = 0; i < remaining.length; i += 100) shards.push({ id: `ordinary-${String(i / 100).padStart(3, '0')}`, roots: remaining.slice(i, i + 100) })
const assigned = shards.flatMap(shard => shard.roots)
if (new Set(assigned).size !== assigned.length) throw Error('Duplicate ordinary root assignment')
const covered = new Set([...assigned, ...shared.filter(file => roots.includes(file))])
if (covered.size !== roots.length || roots.some(file => !covered.has(file))) throw Error('Root coverage mismatch')
fs.mkdirSync(output, { recursive: true })
for (const shard of shards) {
  shard.config = path.join(output, `${shard.id}.json`)
  fs.writeFileSync(shard.config, JSON.stringify({ extends: configPath, files: [...new Set([...shared, ...shard.roots])], include: [], exclude: [] }, null, 2) + '\n')
}
const declarations = Object.fromEntries(['auto-imports.d.ts', 'auto-components.d.ts'].map(name => {
  const file = path.join(root, 'src/types', name)
  return [file, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]
}))
const manifest = { status: 'PLANNED_ONLY_NOT_CHECKED', root, configPath, rootCount: roots.length, vueRootCount: roots.filter(x => x.endsWith('.vue')).length,
  graphFileCount: graph.length, sharedFileCount: shared.length, ordinaryRootCount: ordinary.length, roots, graph, sharedReasons: reasons, shards,
  coverage: { ordinaryExactlyOnce: true, rootUnionEqualsOriginal: true }, declarations,
  semanticBoundary: 'Real vue-tsc listFilesOnly dependency graph plus conservative global/module augmentation classification. Sharded success alone is not a claim of equivalence to the original single-process program.' }
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
console.log(JSON.stringify({ rootCount: roots.length, graphFileCount: graph.length, sharedFileCount: shared.length, shards: shards.length }))
