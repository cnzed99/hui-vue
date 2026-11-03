import { parseDemoFileEnvelope } from '@/api/demoFiles'
import type { DemoFileMetadata } from '@/api/demoFiles'
import type { DemoSession } from '@/api/demoSession'

export const MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
export const ARCHIVE_DIGEST = /^[0-9a-f]{64}$/
const MAX_ENTRIES = 20

export interface ArchiveInput {
  name: string
  size: number
  file?: File
  path?: string
}

export interface ArchiveCandidate {
  ordinal: number
  displayName: string
  declaredSizeBytes: number
}

export interface ArchiveInspection {
  archiveSha256: string
  expiresAtMs: number
  candidates: ArchiveCandidate[]
}

export type ArchiveReply<T> = { kind: 'ok'; data: T } | { kind: 'rejected' | 'unknown' }

function parseInspection(envelope: unknown): ArchiveInspection | null {
  const result = envelope as { code?: unknown; data?: Partial<ArchiveInspection> } | null
  const data = result?.data
  if (result?.code !== 0 || !data || typeof data.archiveSha256 !== 'string'
    || !ARCHIVE_DIGEST.test(data.archiveSha256) || !Number.isSafeInteger(data.expiresAtMs)
    || !Array.isArray(data.candidates) || data.candidates.length > MAX_ENTRIES) return null
  const ordinals = new Set<number>()
  for (const candidate of data.candidates) {
    if (!candidate || !Number.isInteger(candidate.ordinal) || candidate.ordinal < 0
      || candidate.ordinal >= MAX_ENTRIES || ordinals.has(candidate.ordinal)
      || typeof candidate.displayName !== 'string' || !candidate.displayName
      || !Number.isSafeInteger(candidate.declaredSizeBytes) || candidate.declaredSizeBytes < 0
      || candidate.declaredSizeBytes > MAX_ARCHIVE_BYTES) return null
    ordinals.add(candidate.ordinal)
  }
  return data as ArchiveInspection
}

function decode<T>(status: number, body: string, parse: (value: unknown) => T | null): ArchiveReply<T> {
  if (status !== 200) return { kind: status >= 400 && status < 500 ? 'rejected' : 'unknown' }
  try {
    const envelope = JSON.parse(body) as { code?: unknown } | null
    const data = parse(envelope)
    if (data) return { kind: 'ok', data }
    if (typeof envelope?.code === 'number' && envelope.code !== 0) return { kind: 'rejected' }
  } catch {
    return { kind: 'unknown' }
  }
  return { kind: 'unknown' }
}

async function send<T>(endpoint: 'archive-inspections' | 'archive-files', session: DemoSession,
  input: ArchiveInput, parse: (value: unknown) => T | null, fields?: Record<string, string>,
  key?: string): Promise<ArchiveReply<T>> {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  const suffix = '/print/miniapp-demo/' + endpoint
  const header: Record<string, string> = { Authorization: 'Bearer ' + session.accessToken }
  if (key) header['Idempotency-Key'] = key

  // #ifdef H5
  if (!input.file) return { kind: 'rejected' }
  const form = new FormData()
  form.append('archive', input.file, input.name)
  for (const [name, value] of Object.entries(fields || {})) form.append(name, value)
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20000)
  try {
    const response = await fetch((configured || '/app-api') + suffix, {
      method: 'POST', headers: header, body: form, signal: controller.signal, redirect: 'error'
    })
    return decode(response.status, await response.text(), parse)
  } catch {
    return { kind: 'unknown' }
  } finally {
    clearTimeout(timeout)
  }
  // #endif

  // #ifdef MP-WEIXIN
  if (!configured?.startsWith('https://') || !input.path) return { kind: 'rejected' }
  const filePath = input.path
  return new Promise(resolve => {
    uni.uploadFile({
      url: configured + suffix, filePath, name: 'archive', header, formData: fields, timeout: 20000,
      success: response => resolve(decode(response.statusCode, response.data, parse)),
      fail: () => resolve({ kind: 'unknown' })
    })
  })
  // #endif

  return { kind: 'rejected' }
}

export function inspectDemoArchive(session: DemoSession, input: ArchiveInput) {
  return send('archive-inspections', session, input, parseInspection)
}

export function selectDemoArchiveFile(session: DemoSession, input: ArchiveInput,
  archiveSha256: string, ordinal: number, key: string): Promise<ArchiveReply<DemoFileMetadata>> {
  return send('archive-files', session, input, parseDemoFileEnvelope, { archiveSha256, ordinal: String(ordinal) }, key)
}
