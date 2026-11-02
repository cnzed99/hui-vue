import type { DemoSession } from '@/api/demoSession'
import type { DemoFileMetadata } from '@/api/demoFiles'
import { uploadStagedImageCollage } from '@/api/demoImageCollageStaged'

export interface CollageUploadHandle {
  promise: Promise<DemoFileMetadata | null>
  abort: () => void
}

function apiUrl(): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/image-collages`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/image-collages`
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

export function newCollageIdempotencyKey(): string {
  return `miniapp-collage-${Date.now()}-${Math.random().toString(36).slice(2, 14)}`
}

export function uploadDemoImageCollage(
  session: DemoSession,
  filePaths: string[],
  idempotencyKey: string,
  progress: (percentage: number) => void
): CollageUploadHandle {
  const url = apiUrl()
  let task: { abort?: () => void; onProgressUpdate?: (callback: (event: { progress: number }) => void) => void } | null = null
  if (!url || filePaths.length < 1 || filePaths.length > 9 || !idempotencyKey) {
    return { promise: Promise.resolve(null), abort: () => undefined }
  }
  // #ifdef MP-WEIXIN
  return uploadStagedImageCollage(url.replace(/\/image-collages$/, '/image-collage-upload-sessions'),
    session, filePaths, idempotencyKey, progress, parseEnvelope)
  // #endif
  // #ifdef H5
  const promise = new Promise<DemoFileMetadata | null>(resolve => {
    task = uni.uploadFile({
      url,
      files: filePaths.map(uri => ({ name: 'files', uri })),
      header: {
        Authorization: `Bearer ${session.accessToken}`,
        'Idempotency-Key': idempotencyKey
      },
      success: (response: { statusCode: number; data: string | object }) => {
        if (response.statusCode !== 200 || typeof response.data !== 'string') return resolve(null)
        try { resolve(parseEnvelope(JSON.parse(response.data))) }
        catch { resolve(null) }
      },
      fail: () => resolve(null)
    } as never) as unknown as typeof task
    task?.onProgressUpdate?.(event => progress(Math.max(0, Math.min(100, Number(event.progress) || 0))))
  })
  return { promise, abort: () => task?.abort?.() }
  // #endif
  // Other platforms have no enabled adapter in this project.
  return { promise: Promise.resolve(null), abort: () => undefined }
}
