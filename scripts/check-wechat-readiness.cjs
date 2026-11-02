const { writeFileSync, mkdirSync } = require('node:fs')
const { dirname, resolve } = require('node:path')
const { loadEnv } = require('vite')
const { evaluate } = require('./wechat-readiness.cjs')

const args = process.argv.slice(2)
let configOnly = false
let output
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--config-only') configOnly = true
  else if (args[i] === '--output' && args[i + 1] && !args[i + 1].startsWith('--')) output = args[++i]
  else {
    console.error('Usage: node scripts/check-wechat-readiness.cjs [--config-only] [--output path]')
    process.exit(2)
  }
}
const env = { ...loadEnv('wechat-integration', process.cwd(), ''), ...process.env }
const report = evaluate(env)
const json = JSON.stringify(report, null, 2) + '\n'
if (output) {
  const path = resolve(output)
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, json)
}
process.stdout.write(json)
process.exitCode = configOnly && report.configStatus === 'READY_FOR_COMPILE' ? 0 : 1
