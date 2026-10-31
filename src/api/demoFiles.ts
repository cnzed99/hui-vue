import type { DemoSession } from '@/api/demoSession'

export interface DemoFileMetadata {
  id: string
  displayName: string
  sizeBytes: number
  pageCount: number | null
  status: 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED' | 'DELETED'
  failureCode: string | null
  createdAtMs: number
  expiresAtMs: number
  ready: boolean
}

function apiUrl(path: 'files' | 'file'): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/${path}`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/${path}`
  // #endif
  return null
}

function parseEnvelope(value: unknown): DemoFileMetadata | null {
  const envelope = value as { code?: number; data?: Partial<DemoFileMetadata> }
  const item = envelope?.data
  if (envelope?.code !== 0 || !item || typeof item.id !== 'string'
    || typeof item.displayName !== 'string' || !Number.isSafeInteger(item.sizeBytes) || Number(item.sizeBytes) <= 0
    || (item.pageCount !== null && (!Number.isSafeInteger(item.pageCount) || Number(item.pageCount) <= 0))
    || !['UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'DELETED'].includes(item.status || '')
    || (item.failureCode !== null && typeof item.failureCode !== 'string')
    || !Number.isSafeInteger(item.createdAtMs) || !Number.isSafeInteger(item.expiresAtMs)
    || typeof item.ready !== 'boolean') return null
  return item as DemoFileMetadata
}

export function uploadDemoPdf(session: DemoSession, filePath: string): Promise<DemoFileMetadata | null> {
  const url = apiUrl('files')
  if (!url || !filePath) return Promise.resolve(null)
  const idempotencyKey = `miniapp-upload-${Date.now()}-${Math.random().toString(36).slice(2, 14)}`
  return new Promise(resolve => {
    uni.uploadFile({
      url,
      filePath,
      name: 'file',
      header: {
        Authorization: `Bearer ${session.accessToken}`,
        'Idempotency-Key': idempotencyKey
      },
      success: response => {
        if (response.statusCode !== 200 || typeof response.data !== 'string') return resolve(null)
        try { resolve(parseEnvelope(JSON.parse(response.data))) }
        catch { resolve(null) }
      },
      fail: () => resolve(null)
    })
  })
}

export function getDemoFile(session: DemoSession, id: string): Promise<DemoFileMetadata | null> {
  const base = apiUrl('file')
  if (!base || !id) return Promise.resolve(null)
  return new Promise(resolve => {
    uni.request({
      url: `${base}?id=${encodeURIComponent(id)}`,
      method: 'GET',
      timeout: 2500,
      header: { Authorization: `Bearer ${session.accessToken}` },
      success: response => resolve(response.statusCode === 200 ? parseEnvelope(response.data) : null),
      fail: () => resolve(null)
    })
  })
}

export async function waitForDemoFile(session: DemoSession, initial: DemoFileMetadata): Promise<DemoFileMetadata | null> {
  let current: DemoFileMetadata | null = initial
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (!current || ['READY', 'FAILED', 'DELETED'].includes(current.status)) return current
    await new Promise(resolve => setTimeout(resolve, 750))
    current = await getDemoFile(session, initial.id)
  }
  return current
}
