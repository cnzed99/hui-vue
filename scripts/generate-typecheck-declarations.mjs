import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'
import { createServer } from 'vite'

const require = createRequire(import.meta.url)
const ts = require('typescript')
const root = process.cwd()
const files = ts.sys.readDirectory(path.join(root, 'src'), ['.ts', '.tsx', '.js', '.jsx', '.vue'], ['**/*.d.ts'])
const declarations = ['src/types/auto-imports.d.ts', 'src/types/auto-components.d.ts']
for (const file of declarations) {
  if (fs.existsSync(file)) throw new Error(`Refuse to overwrite pre-existing declaration: ${file}`)
}
// command=serve uses the project's real AutoImport/Components plugins with dts enabled.
// The server never listens; each source is transformed by the real Vite plugin pipeline.
process.argv[3] = 'dev' // Existing vite.config.ts reads the dev mode from argv.
const server = await createServer({
  mode: 'dev',
  cacheDir: path.join(process.env.TMPDIR || '.', 'vite-type-declarations-cache'),
  server: { host: '127.0.0.1', open: false, watch: null },
  optimizeDeps: { noDiscovery: true, include: [] },
})
try {
  for (const file of files.sort()) {
    const result = await server.transformRequest('/' + path.relative(root, file).split(path.sep).join('/'))
    if (!result) throw new Error(`No transform result: ${file}`)
  }
} finally {
  await server.close()
}
const hashes = Object.fromEntries(declarations.map(file => {
  const data = fs.readFileSync(file)
  if (!data.length) throw new Error(`Empty declaration: ${file}`)
  return [file, crypto.createHash('sha256').update(data).digest('hex')]
}))
console.log(JSON.stringify({ status: 'GENERATED_BY_REAL_VITE_SERVE_PLUGINS', transformedSourceCount: files.length, declarations: hashes }))
