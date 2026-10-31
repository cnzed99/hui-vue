export interface DemoSession {
  accessToken: string
  sessionId: string
  principalId: string
  expiresAtMs: number
  actorType: 'MINIAPP_DEMO'
}

let memorySession: DemoSession | null = null

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

export function ensureDemoSession(): Promise<DemoSession | null> {
  if (memorySession && memorySession.expiresAtMs > Date.now() + 30_000) return Promise.resolve(memorySession)
  const url = issueUrl()
  if (!url) return Promise.resolve(null)
  return new Promise(resolve => {
    uni.request({
      url,
      method: 'POST',
      timeout: 2500,
      success: response => {
        const session = response.statusCode === 200 ? parseSession(response.data) : null
        memorySession = session
        resolve(session)
      },
      fail: () => resolve(null)
    })
  })
}

export function demoAuthorization(): string | null {
  return memorySession && memorySession.expiresAtMs > Date.now() ? `Bearer ${memorySession.accessToken}` : null
}
