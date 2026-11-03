import { shallowRef, watch } from 'vue'

export interface DemoSession {
  accessToken: string
  sessionId: string
  principalId: string
  expiresAtMs: number
  actorType: 'MINIAPP_DEMO'
}

const memorySession = shallowRef<DemoSession | null>(null)
let issuing: Promise<DemoSession | null> | null = null
const storageKey = 'miniapp-demo-session-v1'

function issueUrl(): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/sessions`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/sessions`
  // #endif
  return null
}

function parseSession(value: unknown): DemoSession | null {
  const envelope = value as { code?: number; data?: Partial<DemoSession> }
  const data = envelope?.data
  if (envelope?.code !== 0 || !data
    || typeof data.accessToken !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(data.accessToken)
    || typeof data.sessionId !== 'string' || typeof data.principalId !== 'string'
    || !Number.isSafeInteger(data.expiresAtMs) || Number(data.expiresAtMs) <= Date.now()
    || data.actorType !== 'MINIAPP_DEMO') return null
  return data as DemoSession
}

export function getActiveDemoSession(): DemoSession | null {
  if (!memorySession.value) {
    const stored = parseSession({ code: 0, data: uni.getStorageSync(storageKey) })
    if (stored) memorySession.value = stored
    else uni.removeStorageSync(storageKey)
  }
  const current = memorySession.value
  return current && current.expiresAtMs > Date.now() ? current : null
}

export function watchDemoSession(onChange: () => void) {
  return watch(memorySession, onChange)
}

export function ensureDemoSession(): Promise<DemoSession | null> {
  const active = getActiveDemoSession()
  if (active && active.expiresAtMs > Date.now() + 30_000) return Promise.resolve(active)
  const url = issueUrl()
  if (!url) return Promise.resolve(null)
  if (issuing) return issuing
  issuing = new Promise<DemoSession | null>(resolve => {
    uni.request({
      url,
      method: 'POST',
      timeout: 2500,
      success: response => {
        const session = response.statusCode === 200 ? parseSession(response.data) : null
        if (session) {
          try { uni.setStorageSync(storageKey, session) }
          catch { resolve(null); return }
          memorySession.value = session
        }
        resolve(session)
      },
      fail: () => resolve(null)
    })
  }).finally(() => { issuing = null })
  return issuing
}

export function demoAuthorization(): string | null {
  const current = memorySession.value
  return current && current.expiresAtMs > Date.now() ? `Bearer ${current.accessToken}` : null
}
