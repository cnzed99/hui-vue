import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig({
  base: process.env.UNI_PLATFORM === 'h5' ? '/miniapp/' : '/',
  plugins: [uni()]
})
