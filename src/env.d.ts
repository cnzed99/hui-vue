/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

interface ImportMetaEnv {
 readonly VITE_APP_API_BASE?: string
 readonly VITE_WECHAT_APP_ID?: string
 readonly VITE_WECHAT_LOGIN_ENABLED?: string
 readonly VITE_WECHAT_PAY_ENABLED?: string
 readonly VITE_PRINT_TERMINAL_PROVIDER?: string
}
interface ImportMeta { readonly env: ImportMetaEnv }
