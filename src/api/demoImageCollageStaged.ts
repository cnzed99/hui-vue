import type { DemoSession } from '@/api/demoSession'
import type { DemoFileMetadata } from '@/api/demoFiles'
import type { CollageUploadHandle } from '@/api/demoImageCollages'

const RENDER_VERSION = 'IMAGE_COLLAGE_A4_PORTRAIT_GRID_V1'
const MAX_IMAGE = 2 * 1024 * 1024
const MAX_TOTAL = 12 * 1024 * 1024
interface ManifestItem { index: number; sizeBytes: number; sha256: string }
interface UploadView {
  uploadId: string
  state: 'OPEN' | 'COMMITTING' | 'COMPLETE'
  fileCount: number
  uploadedIndexes: number[]
  expiresAtMs: number
  result: DemoFileMetadata | null
}
interface Task {
  abort?: () => void
  onProgressUpdate?: (callback: (event: { progress: number }) => void) => void
}
type ParseMetadata = (value: unknown) => DemoFileMetadata | null

function parseView(value: unknown, count: number, metadata: ParseMetadata): UploadView | null {
  let envelope: { code?: number; data?: Partial<UploadView> }
  try { envelope = typeof value === 'string' ? JSON.parse(value) : value as typeof envelope }
  catch { return null }
  const view = envelope?.data
  if (envelope?.code !== 0 || !view || typeof view.uploadId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(view.uploadId)
    || !['OPEN', 'COMMITTING', 'COMPLETE'].includes(view.state || '')
    || view.fileCount !== count || !Array.isArray(view.uploadedIndexes)
    || view.uploadedIndexes.some(index => !Number.isSafeInteger(index) || index < 0 || index >= count)
    || new Set(view.uploadedIndexes).size !== view.uploadedIndexes.length
    || !Number.isSafeInteger(view.expiresAtMs) || Number(view.expiresAtMs) <= Date.now()) return null
  const result = view.state === 'COMPLETE' ? metadata({ code: 0, data: view.result }) : null
  if (view.state === 'COMPLETE' && !result) return null
  if (view.state !== 'COMPLETE' && view.result != null) return null
  return { ...view, result } as UploadView
}

/** Native WeChat adapter. H5 keeps its single multipart request in demoImageCollages. */
export function uploadStagedImageCollage(
  url: string, session: DemoSession, paths: string[], key: string,
  progress: (value: number) => void, metadata: ParseMetadata
): CollageUploadHandle {
  let aborted = false
  let currentTask: Task | null = null
  let uploadId = ''
  let cancelSent = false
  let settle: (value: DemoFileMetadata | null) => void = () => undefined
  const promise = new Promise<DemoFileMetadata | null>(resolve => { settle = resolve })
  const headers = { Authorization: `Bearer ${session.accessToken}`, 'Idempotency-Key': key }
  const cancelled = () => aborted || session.expiresAtMs <= Date.now()
  const cancelRemote = () => {
    if (!uploadId || cancelSent) return
    cancelSent = true
    // Best effort only; server TTL and startup cleanup cover network loss.
    try {
      uni.request({ url: `${url}/${uploadId}`, method: 'DELETE', header: headers,
        timeout: 5000, success: () => undefined, fail: () => undefined })
    } catch { /* TTL owns cleanup if the platform cannot send cancellation. */ }
  }
  const request = (requestUrl: string, data?: object): Promise<UploadView | null> =>
    new Promise(resolve => {
      if (cancelled()) return resolve(null)
      const expectedId = requestUrl === url ? null : uploadId
      currentTask = uni.request({
        url: requestUrl, method: 'POST', header: headers, timeout: 25000,
        ...(data ? { data } : {}),
        success: response => {
          const view = response.statusCode === 200 ? parseView(response.data, paths.length, metadata) : null
          if (view && expectedId && view.uploadId !== expectedId) return resolve(null)
          // A delayed create response can still identify the session to cancel.
          if (view) uploadId = view.uploadId
          if (cancelled()) { cancelRemote(); resolve(null) }
          else resolve(view)
        },
        fail: () => resolve(null)
      }) as unknown as Task
    })
  const digest = (path: string, index: number): Promise<ManifestItem | null> =>
    new Promise(resolve => {
      if (cancelled()) return resolve(null)
      try {
        uni.getFileSystemManager().getFileInfo({
          filePath: path, digestAlgorithm: 'sha256',
          success: result => {
            if (cancelled() || !Number.isSafeInteger(result.size) || result.size < 1 || result.size > MAX_IMAGE
              || !/^[0-9a-f]{64}$/.test(result.digest || '')) return resolve(null)
            resolve({ index, sizeBytes: result.size, sha256: result.digest })
          },
          fail: () => resolve(null)
        })
      } catch { resolve(null) }
    })
  const uploadPart = (index: number, completed: number): Promise<UploadView | null> =>
    new Promise(resolve => {
      if (cancelled()) return resolve(null)
      const partId = uploadId
      currentTask = uni.uploadFile({
        url: `${url}/${partId}/parts/${index}`, filePath: paths[index], name: 'file',
        header: headers, timeout: 25000,
        success: response => {
          if (cancelled()) return resolve(null)
          const view = response.statusCode === 200 ? parseView(response.data, paths.length, metadata) : null
          resolve(view?.uploadId === partId && view.state === 'OPEN'
            && view.uploadedIndexes.includes(index) ? view : null)
        },
        fail: () => resolve(null)
      }) as unknown as Task
      currentTask?.onProgressUpdate?.(event => {
        if (!cancelled()) progress(Math.round(100 * (completed + Math.max(0, Math.min(100, Number(event.progress) || 0)) / 100) / paths.length))
      })
    })
  const run = async () => {
    if (!url.startsWith('https://') || paths.length < 1 || paths.length > 9 || !key) return null
    const files: ManifestItem[] = []
    let total = 0
    for (let index = 0; index < paths.length; index += 1) {
      const item = await digest(paths[index], index)
      if (!item || cancelled()) return null
      total += item.sizeBytes
      if (total > MAX_TOTAL) return null
      files.push(item)
    }
    // Recompute on every explicit retry: changed bytes under the same path must conflict.
    let view = await request(url, { renderVersion: RENDER_VERSION, files })
    if (!view || cancelled()) return null
    if (view.state === 'COMPLETE') return view.result
    if (view.state !== 'OPEN') return null
    const confirmed = new Set(view.uploadedIndexes)
    progress(Math.round(confirmed.size * 100 / paths.length))
    for (let index = 0; index < paths.length; index += 1) {
      if (confirmed.has(index)) continue
      view = await uploadPart(index, confirmed.size)
      if (!view || cancelled()) return null
      // Server may acknowledge additional identical retries; only trust validated indexes.
      for (const uploaded of view.uploadedIndexes) confirmed.add(uploaded)
      progress(Math.round(confirmed.size * 100 / paths.length))
    }
    if (confirmed.size !== paths.length || cancelled()) return null
    const completed = await request(`${url}/${uploadId}/complete`)
    return !cancelled() && completed?.state === 'COMPLETE' ? completed.result : null
  }
  // Network/429/COMMITTING failure preserves server state and the caller's original key.
  void run().then(value => settle(cancelled() ? null : value)).catch(() => settle(null))
  return {
    promise,
    abort: () => {
      aborted = true
      currentTask?.abort?.()
      cancelRemote()
      settle(null)
    }
  }
}
