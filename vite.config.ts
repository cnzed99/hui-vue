import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { assertIntegration } from './scripts/wechat-readiness.cjs'

export default defineConfig(({ mode }) => {
  if (mode === 'wechat-integration') {
    if (process.env.UNI_PLATFORM !== 'mp-weixin') throw new Error('NOT_READY: wechat-integration is MP-WEIXIN only')
    const env = { ...loadEnv(mode, process.cwd(), ''), ...process.env }
    const input = process.env.UNI_INPUT_DIR || resolve(process.cwd(), 'src')
    assertIntegration(env, JSON.parse(readFileSync(resolve(input, 'manifest.json'), 'utf8')))
  }
  return {
    base: process.env.UNI_PLATFORM === 'h5' ? '/miniapp/' : '/',
    plugins: [uni()]
  }
})
