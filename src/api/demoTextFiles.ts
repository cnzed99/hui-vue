import type { DemoSession } from '@/api/demoSession'
import type { DemoFileMetadata } from '@/api/demoFiles'

export type TextFileFailure = 'unavailable' | 'network' | 'invalid' | 'too-large' | 'unsupported'
  | 'busy' | 'conflict' | 'session' | 'file-limit' | 'capacity' | 'not-found'
export type TextFileResult = { kind: 'ok'; file: DemoFileMetadata } | { kind: 'error'; reason: TextFileFailure }

function apiUrl(path: 'text-files' | 'file'): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/${path}`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/${path}`
  // #endif
  return null
}

const fields = ['id', 'displayName', 'sizeBytes', 'pageCount', 'status', 'failureCode', 'createdAtMs', 'expiresAtMs', 'ready']
function parseEnvelope(value: unknown): DemoFileMetadata | null {
  if (!value || typeof value !== 'object') return null
  const envelope = value as { code?: unknown; data?: unknown }
  if (envelope.code !== 0 || !envelope.data || typeof envelope.data !== 'object' || Array.isArray(envelope.data)) return null
  const item = envelope.data as Record<string, unknown>
  if (Object.keys(item).length !== fields.length || fields.some(field => !Object.prototype.hasOwnProperty.call(item, field))
    || typeof item.id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(item.id)
    || typeof item.displayName !== 'string' || item.displayName.length < 1 || item.displayName.length > 400
    || /[\u0000-\u001f\u007f]/.test(item.displayName)
    || !Number.isSafeInteger(item.sizeBytes) || Number(item.sizeBytes) < 1 || Number(item.sizeBytes) > 2 * 1024 * 1024
    || (item.pageCount !== null && (!Number.isSafeInteger(item.pageCount) || Number(item.pageCount) < 1 || Number(item.pageCount) > 20))
    || typeof item.status !== 'string' || !['UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'DELETED'].includes(item.status)
    || (item.failureCode !== null && (typeof item.failureCode !== 'string' || !/^[A-Z0-9_]{1,80}$/.test(item.failureCode)))
    || !Number.isSafeInteger(item.createdAtMs) || Number(item.createdAtMs) <= 0
    || !Number.isSafeInteger(item.expiresAtMs) || Number(item.expiresAtMs) <= Number(item.createdAtMs)
    || typeof item.ready !== 'boolean') return null
  if (item.status === 'READY') {
    if (item.pageCount === null || item.failureCode !== null || !item.ready || Number(item.expiresAtMs) <= Date.now()) return null
  } else if (item.ready) return null
  return item as unknown as DemoFileMetadata
}

function failure(status: number, value: unknown): TextFileFailure {
  const code = value && typeof value === 'object' ? (value as { code?: unknown }).code : null
  if (code === 1_932_004) return 'unsupported'
  if (code === 1_932_007) return 'too-large'
  if (code === 1_927_005) return 'file-limit'
  if (code === 1_927_006) return 'capacity'
  if (status === 401 || status === 403) return 'session'
  if (status === 409) return 'conflict'
  if (status === 413) return 'too-large'
  if (status === 429) return 'busy'
  if (status === 400) return 'invalid'
  if (status === 404) return 'not-found'
  return 'unavailable'
}

export function createDemoTextFile(session: DemoSession, content: string, idempotencyKey: string): Promise<TextFileResult> {
  const url = apiUrl('text-files')
  if (!url || !content || !/^[!-~]{1,128}$/.test(idempotencyKey)) return Promise.resolve({ kind: 'error', reason: 'unavailable' })
  return new Promise(resolve => {
    uni.request({
      url,
      method: 'POST',
      timeout: 16000,
      header: { Authorization: `Bearer ${session.accessToken}`, 'Idempotency-Key': idempotencyKey, 'Content-Type': 'application/json' },
      data: { content },
      success: response => {
        const file = response.statusCode === 200 ? parseEnvelope(response.data) : null
        resolve(file ? { kind: 'ok', file } : { kind: 'error', reason: failure(response.statusCode, response.data) })
      },
      fail: () => resolve({ kind: 'error', reason: 'network' })
    })
  })
}

export function getDemoTextFile(session: DemoSession, id: string): Promise<TextFileResult> {
  const base = apiUrl('file')
  if (!base || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) return Promise.resolve({ kind: 'error', reason: 'invalid' })
  return new Promise(resolve => {
    uni.request({
      url: `${base}?id=${encodeURIComponent(id)}`,
      method: 'GET',
      timeout: 4000,
      header: { Authorization: `Bearer ${session.accessToken}` },
      success: response => {
        const file = response.statusCode === 200 ? parseEnvelope(response.data) : null
        resolve(file && file.id === id ? { kind: 'ok', file } : { kind: 'error', reason: failure(response.statusCode, response.data) })
      },
      fail: () => resolve({ kind: 'error', reason: 'network' })
    })
  })
}
