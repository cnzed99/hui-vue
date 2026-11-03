import { parseDemoFileEnvelope } from '@/api/demoFiles'
import type { DemoFileMetadata } from '@/api/demoFiles'
import type { DemoSession } from '@/api/demoSession'

export type IdPhotoSpecification = 'ONE_INCH' | 'TWO_INCH' | 'THREE_INCH'
export type IdPhotoUploadReply = { kind: 'ok'; data: DemoFileMetadata }
  | { kind: 'rejected' }
  | { kind: 'unknown' }

const MAX_INPUT_BYTES = 2 * 1024 * 1024

function endpoint(): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/id-photo-files`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/id-photo-files`
  // #endif
  return null
}

function decode(status: number, value: unknown): IdPhotoUploadReply {
  if (status !== 200) return { kind: status >= 400 && status < 500 ? 'rejected' : 'unknown' }
  const data = parseDemoFileEnvelope(value)
  return data ? { kind: 'ok', data } : { kind: 'unknown' }
}

export async function uploadIdPhotoFile(session: DemoSession, filePath: string,
  specification: IdPhotoSpecification, idempotencyKey: string): Promise<IdPhotoUploadReply> {
  const url = endpoint()
  if (!url || !filePath || !idempotencyKey) return { kind: 'rejected' }
  const target = url
  const headers = {
    Authorization: `Bearer ${session.accessToken}`,
    'Idempotency-Key': idempotencyKey
  }

  // #ifdef H5
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20000)
  try {
    const local = await fetch(filePath, { signal: controller.signal })
    if (!local.ok) return { kind: 'rejected' }
    const blob = await local.blob()
    if (blob.size < 1 || blob.size > MAX_INPUT_BYTES
      || !['image/jpeg', 'image/png'].includes(blob.type.toLowerCase())) return { kind: 'rejected' }
    const form = new FormData()
    form.append('file', blob, blob.type.toLowerCase() === 'image/png' ? 'id-photo.png' : 'id-photo.jpg')
    form.append('specification', specification)
    const response = await fetch(target, {
      method: 'POST', headers, body: form, signal: controller.signal, redirect: 'error'
    })
    let body: unknown
    try { body = JSON.parse(await response.text()) }
    catch { return { kind: 'unknown' } }
    return decode(response.status, body)
  } catch {
    return { kind: 'unknown' }
  } finally {
    clearTimeout(timeout)
  }
  // #endif

  // #ifdef MP-WEIXIN
  if (!target.startsWith('https://')) return { kind: 'rejected' }
  return new Promise(resolve => {
    uni.uploadFile({
      url: target, filePath, name: 'file', header: headers,
      formData: { specification }, timeout: 20000,
      success: response => {
        let body: unknown
        try { body = JSON.parse(response.data) }
        catch { resolve({ kind: 'unknown' }); return }
        resolve(decode(response.statusCode, body))
      },
      fail: () => resolve({ kind: 'unknown' })
    })
  })
  // #endif

  return { kind: 'rejected' }
}
