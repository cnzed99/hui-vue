const { mkdtempSync, cpSync, readFileSync, writeFileSync, rmSync, mkdirSync } = require('node:fs')
const { resolve, join } = require('node:path')
const { tmpdir } = require('node:os')
const { spawnSync } = require('node:child_process')
const { loadEnv } = require('vite')
const { evaluate, assertIntegration } = require('./wechat-readiness.cjs')

const root = resolve(__dirname, '..')
const env = { ...loadEnv('wechat-integration', root, ''), ...process.env }
const output = join(root, 'dist/integration/mp-weixin')
const reportPath = join(root, 'dist/integration/readiness.json')
// This directory belongs exclusively to this command. A failed gate cannot leave
// a stale integration package that looks like the current build.
rmSync(output, { recursive: true, force: true })
mkdirSync(dirnameOf(reportPath), { recursive: true })
const report = evaluate(env)
report.build = { status: 'NOT_RUN', output: 'dist/integration/mp-weixin' }
const save = () => writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n')
save()
try { assertIntegration(env) }
catch (error) {
  console.error(error.message)
  process.exit(1)
}
const scratch = mkdtempSync(join(tmpdir(), 'print-miniapp-wechat-integration-'))
let signal
const cleanup = () => rmSync(scratch, { recursive: true, force: true })
for (const name of ['SIGINT', 'SIGTERM']) process.on(name, () => {
  signal = name
  cleanup()
  process.exit(name === 'SIGINT' ? 130 : 143)
})
try {
  const input = join(scratch, 'src')
  cpSync(join(root, 'src'), input, { recursive: true })
  const manifestPath = join(input, 'manifest.json')
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
  manifest['mp-weixin'].appid = env.VITE_WECHAT_APP_ID
  manifest['mp-weixin'].setting.urlCheck = true
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n')
  assertIntegration(env, manifest)
  const result = spawnSync(process.execPath, [join(root, 'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'),
    'build', '-p', 'mp-weixin', '--mode', 'wechat-integration'], {
    cwd: root, stdio: 'inherit',
    env: { ...env, UNI_INPUT_DIR: input, UNI_OUTPUT_DIR: output }
  })
  if (result.error) throw new Error('Compiler could not start')
  if (result.status !== 0 || result.signal || signal) throw new Error('Compiler failed')
  const project = JSON.parse(readFileSync(join(output, 'project.config.json'), 'utf8'))
  if (project.appid !== env.VITE_WECHAT_APP_ID || project.setting?.urlCheck !== true) {
    throw new Error('Generated project does not match checked AppID/urlCheck')
  }
  report.build.status = 'COMPILE_ONLY'
  save()
  console.log('COMPILE_ONLY: integration package compiled; release NOT_READY; device NOT_RUN')
} catch (error) {
  report.build.status = 'FAILED'
  save()
  rmSync(output, { recursive: true, force: true })
  console.error('NOT_READY: ' + error.message)
  process.exitCode = 1
} finally { cleanup() }

function dirnameOf(path) { return require('node:path').dirname(path) }
