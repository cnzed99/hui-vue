import { parseDemoFileEnvelope } from '@/api/demoFiles'
import type { DemoFileMetadata } from '@/api/demoFiles'
import { getActiveDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { photoSpecifications } from '@/domain/photoSelection'
import type { PhotoSpecification } from '@/domain/photoSelection'

export interface PhotoUploadRecord {
  schemaVersion: 1
  owner: { sessionId: string; principalId: string }
  key: string
  variant: PhotoSpecification
  paths: string[]
  copies: number
  createdAtMs: number
  uploadId: string | null
}
export interface PhotoUploadView {
  uploadId: string; state: 'OPEN' | 'FINALIZING' | 'COMPLETE'; variant: PhotoSpecification
  fileCount: number; uploadedIndexes: number[]; expiresAtMs: number; result: DemoFileMetadata | null
}
export type PhotoUploadReply = { kind: 'ok'; view: PhotoUploadView } | { kind: 'rejected' | 'unknown' }
const KEY = 'miniapp-photo-upload-v1'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export function photoOwnerMatches(record: PhotoUploadRecord, session: DemoSession | null) {
  return !!session && session.sessionId === record.owner.sessionId && session.principalId === record.owner.principalId
}
export function savePhotoUpload(record: PhotoUploadRecord) { uni.setStorageSync(KEY, record) }
export function clearPhotoUpload(record: PhotoUploadRecord) {
  const stored = uni.getStorageSync(KEY)
  if (stored?.key === record.key) uni.removeStorageSync(KEY)
}
export function loadPhotoUpload(session = getActiveDemoSession()): PhotoUploadRecord | null {
  const raw = uni.getStorageSync(KEY) as PhotoUploadRecord
  if (!raw || raw.schemaVersion !== 1 || !raw.owner || !photoOwnerMatches(raw, session)
    || typeof raw.key !== 'string' || !/^miniapp-photo-upload-v1-[a-z0-9-]+$/.test(raw.key)
    || !Object.prototype.hasOwnProperty.call(photoSpecifications, raw.variant)
    || !Array.isArray(raw.paths) || raw.paths.length < 1 || raw.paths.length > (raw.variant === 'PHOTO_6IN_COLLAGE' ? 9 : 30)
    || raw.paths.some(path => typeof path !== 'string' || !path)
    || !Number.isSafeInteger(raw.copies) || raw.copies < 1 || raw.copies > 99
    || !Number.isSafeInteger(raw.createdAtMs) || raw.createdAtMs > Date.now()
    || (raw.uploadId !== null && (typeof raw.uploadId !== 'string' || !UUID.test(raw.uploadId)))) return null
  return raw
}
export function newPhotoUpload(session: DemoSession, variant: PhotoSpecification, paths: string[], copies: number): PhotoUploadRecord {
  const record: PhotoUploadRecord = { schemaVersion: 1, owner: { sessionId: session.sessionId, principalId: session.principalId },
    key: `miniapp-photo-upload-v1-${Date.now()}-${Math.random().toString(36).slice(2, 14)}`,
    variant, paths: [...paths], copies, createdAtMs: Date.now(), uploadId: null }
  savePhotoUpload(record)
  return record
}
function endpoint(): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/photo-upload-sessions`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/photo-upload-sessions`
  // #endif
  return null
}
export function parsePhotoUpload(status: number, body: unknown, record: PhotoUploadRecord): PhotoUploadReply {
  if (status >= 400 && status < 500 && status !== 408 && status !== 429) return { kind: 'rejected' }
  try {
    const envelope = typeof body === 'string' ? JSON.parse(body) : body
    const view = envelope?.data as PhotoUploadView
    if (status !== 200 || envelope?.code !== 0 || !view || !UUID.test(view.uploadId)
      || (record.uploadId && view.uploadId !== record.uploadId) || view.variant !== record.variant
      || view.fileCount !== record.paths.length || !['OPEN', 'FINALIZING', 'COMPLETE'].includes(view.state)
      || !Number.isSafeInteger(view.expiresAtMs) || view.expiresAtMs <= Date.now()
      || !Array.isArray(view.uploadedIndexes) || new Set(view.uploadedIndexes).size !== view.uploadedIndexes.length
      || view.uploadedIndexes.some(index => !Number.isSafeInteger(index) || index < 0 || index >= view.fileCount)) return { kind: 'unknown' }
    const file = view.state === 'COMPLETE' ? parseDemoFileEnvelope({ code: 0, data: view.result }) : null
    if ((view.state === 'COMPLETE' && !file) || (view.state !== 'COMPLETE' && view.result != null)) return { kind: 'unknown' }
    return { kind: 'ok', view: { ...view, result: file } }
  } catch { return { kind: 'unknown' } }
}
function request(session: DemoSession, record: PhotoUploadRecord, method: 'GET' | 'POST', suffix: string, data?: object): Promise<PhotoUploadReply> {
  const base = endpoint()
  if (!base || !photoOwnerMatches(record, session) || !photoOwnerMatches(record, getActiveDemoSession())) return Promise.resolve({ kind: 'rejected' })
  return new Promise(resolve => {
    uni.request({ url: `${base}${suffix}`, method, timeout: 30000,
      header: { Authorization: `Bearer ${session.accessToken}`, 'Idempotency-Key': record.key }, ...(data ? { data } : {}),
      success: response => resolve(parsePhotoUpload(response.statusCode, response.data, record)), fail: () => resolve({ kind: 'unknown' }) })
  })
}
async function uploadPart(session: DemoSession, record: PhotoUploadRecord, index: number, isCurrent: () => boolean): Promise<PhotoUploadReply> {
  const base = endpoint()
  if (!base || !record.uploadId) return { kind: 'rejected' }
  const url = `${base}/${record.uploadId}/parts/${index}`
  const header = { Authorization: `Bearer ${session.accessToken}`, 'Idempotency-Key': record.key }
  // #ifdef H5
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 30000)
  try {
    const local = await fetch(record.paths[index], { signal: controller.signal })
    if (!local.ok) return { kind: 'rejected' }
    const blob = await local.blob()
    if (blob.size < 1 || blob.size > 2 * 1024 * 1024 || !['image/jpeg', 'image/png'].includes(blob.type.toLowerCase())) return { kind: 'rejected' }
    if (!isCurrent() || !photoOwnerMatches(record, getActiveDemoSession())) return { kind: 'unknown' }
    const form = new FormData(); form.append('file', blob, blob.type.toLowerCase() === 'image/png' ? 'photo.png' : 'photo.jpg')
    const response = await fetch(url, { method: 'POST', headers: header, body: form, signal: controller.signal, redirect: 'error' })
    return parsePhotoUpload(response.status, await response.text(), record)
  } catch { return { kind: 'unknown' } }
  finally { clearTimeout(timer) }
  // #endif
  // #ifdef MP-WEIXIN
  return new Promise(resolve => {
    uni.uploadFile({ url, filePath: record.paths[index], name: 'file', header, timeout: 30000,
      success: response => resolve(parsePhotoUpload(response.statusCode, response.data, record)), fail: () => resolve({ kind: 'unknown' }) })
  })
  // #endif
  return { kind: 'rejected' }
}
/** Every retry queries the original upload domain; unknown never creates a replacement session. */
export async function continuePhotoUpload(session: DemoSession, record: PhotoUploadRecord, isCurrent: () => boolean,
  progress: (view: PhotoUploadView) => void): Promise<PhotoUploadReply> {
  const current = () => isCurrent() && photoOwnerMatches(record, getActiveDemoSession())
  if (!current()) return { kind: 'unknown' }
  let reply = record.uploadId
    ? await request(session, record, 'GET', `/${record.uploadId}`)
    : await request(session, record, 'POST', '', { variant: record.variant, fileCount: record.paths.length })
  const accept = (value: PhotoUploadReply) => {
    if (value.kind !== 'ok') return
    record.uploadId = value.view.uploadId
    // Retain identity even when a page hides after a request was committed.
    if (photoOwnerMatches(record, getActiveDemoSession())) savePhotoUpload(record)
    if (current()) progress(value.view)
  }
  accept(reply)
  if (!current() || reply.kind !== 'ok') return current() ? reply : { kind: 'unknown' }
  if (reply.view.state === 'COMPLETE') return reply
  if (reply.view.state === 'OPEN') {
    for (let index = 0; index < record.paths.length; index++) {
      if (reply.view.uploadedIndexes.includes(index)) continue
      if (!current()) return { kind: 'unknown' }
      reply = await uploadPart(session, record, index, current)
      accept(reply)
      if (!current() || reply.kind !== 'ok') return current() ? reply : { kind: 'unknown' }
      if (reply.view.state === 'COMPLETE') return reply
      if (reply.view.state !== 'OPEN' || !reply.view.uploadedIndexes.includes(index)) return { kind: 'unknown' }
    }
  }
  if (!current()) return { kind: 'unknown' }
  reply = await request(session, record, 'POST', `/${record.uploadId}/complete`)
  accept(reply)
  return current() ? reply : { kind: 'unknown' }
}
