import type { DemoSession } from '@/api/demoSession'
import type { DemoFileMetadata } from '@/api/demoFiles'
import type { TextFileFailure, TextFileResult } from '@/api/demoTextFiles'

export interface LibraryEntry {
  id: string
  title: string
  category: string
  source: string
  pages: number
  views: number
  prints: number
  contentVersion: string
}
export type LibrarySort = 'DEFAULT' | 'TITLE_ASC' | 'PAGES_ASC'
export interface LibraryPage {
  catalogVersion: 'DEMO_LIBRARY_V1'
  scope: 'PUBLIC_DEMO_ONLY'
  items: LibraryEntry[]
  total: number
  pageNo: number
  pageSize: number
  categories: string[]
}
export interface LibraryDetail {
  catalogVersion: 'DEMO_LIBRARY_V1'
  scope: 'PUBLIC_DEMO_ONLY'
  entry: LibraryEntry
  previewPages: { pageNo: number; lines: string[] }[]
  capabilities: {
    favorite: 'RESERVED_WECHAT_IDENTITY'
    share: 'RESERVED_WECHAT_APPID'
    print: 'DEMO_FILE_GENERATION'
  }
}
export type LibraryResult<T> = { kind: 'ok'; data: T } | { kind: 'error'; reason: TextFileFailure }

function apiUrl(path: 'library' | 'library-entry' | 'library-files'): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/${path}`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/${path}`
  // #endif
  return null
}

function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}
function exact(value: Record<string, unknown>, fields: string[]) {
  return Object.keys(value).length === fields.length && fields.every(field => Object.prototype.hasOwnProperty.call(value, field))
}
function safeText(value: unknown, maximum: number, allowEmpty = false): value is string {
  return typeof value === 'string' && (allowEmpty || value.length > 0) && value.length <= maximum && !/[\u0000-\u001f\u007f-\u009f]/.test(value)
}
export function validLibraryId(value: unknown): value is string { return typeof value === 'string' && /^[a-z0-9-]{1,80}$/.test(value) }
function parseEntry(value: unknown): LibraryEntry | null {
  if (!record(value) || !exact(value, ['id', 'title', 'category', 'source', 'pages', 'views', 'prints', 'contentVersion'])
    || !validLibraryId(value.id) || !safeText(value.title, 160) || !safeText(value.category, 80)
    || value.source !== '原创演示文库' || !Number.isSafeInteger(value.pages) || Number(value.pages) < 1 || Number(value.pages) > 3
    || value.views !== 0 || value.prints !== 0 || typeof value.contentVersion !== 'string' || !/^[A-Za-z0-9_.-]{1,40}$/.test(value.contentVersion)) return null
  return value as unknown as LibraryEntry
}
function data(value: unknown): unknown {
  return record(value) && value.code === 0 ? value.data : null
}
function parsePage(value: unknown): LibraryPage | null {
  const item = data(value)
  if (!record(item) || !exact(item, ['catalogVersion', 'scope', 'items', 'total', 'pageNo', 'pageSize', 'categories'])
    || item.catalogVersion !== 'DEMO_LIBRARY_V1' || item.scope !== 'PUBLIC_DEMO_ONLY' || !Array.isArray(item.items) || item.items.length > 20
    || !Number.isSafeInteger(item.total) || Number(item.total) < 0 || Number(item.total) > 1000
    || !Number.isSafeInteger(item.pageNo) || Number(item.pageNo) < 1 || !Number.isSafeInteger(item.pageSize) || Number(item.pageSize) < 1 || Number(item.pageSize) > 20
    || !Array.isArray(item.categories) || item.categories.length < 1 || item.categories.length > 20 || !item.categories.every(category => safeText(category, 80))) return null
  const entries = item.items.map(parseEntry)
  if (entries.some(entry => !entry) || new Set(entries.map(entry => entry?.id)).size !== entries.length) return null
  return { ...item, items: entries } as unknown as LibraryPage
}
function parseDetail(value: unknown): LibraryDetail | null {
  const item = data(value)
  if (!record(item) || !exact(item, ['catalogVersion', 'scope', 'entry', 'previewPages', 'capabilities'])
    || item.catalogVersion !== 'DEMO_LIBRARY_V1' || item.scope !== 'PUBLIC_DEMO_ONLY') return null
  const entry = parseEntry(item.entry)
  const pages = item.previewPages
  const capabilities = item.capabilities
  if (!entry || !Array.isArray(pages) || pages.length !== entry.pages || !record(capabilities)
    || !exact(capabilities, ['favorite', 'share', 'print']) || capabilities.favorite !== 'RESERVED_WECHAT_IDENTITY'
    || capabilities.share !== 'RESERVED_WECHAT_APPID' || capabilities.print !== 'DEMO_FILE_GENERATION') return null
  for (let i = 0; i < pages.length; i += 1) {
    const page = pages[i]
    if (!record(page) || !exact(page, ['pageNo', 'lines']) || page.pageNo !== i + 1 || !Array.isArray(page.lines)
      || page.lines.length < 1 || page.lines.length > 42 || !page.lines.every(line => safeText(line, 200, true))) return null
  }
  return item as unknown as LibraryDetail
}
function parseFile(value: unknown): DemoFileMetadata | null {
  const item = data(value)
  if (!record(item) || !exact(item, ['id', 'displayName', 'sizeBytes', 'pageCount', 'status', 'failureCode', 'createdAtMs', 'expiresAtMs', 'ready'])
    || typeof item.id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(item.id)
    || !safeText(item.displayName, 400) || !Number.isSafeInteger(item.sizeBytes) || Number(item.sizeBytes) < 1 || Number(item.sizeBytes) > 2 * 1024 * 1024
    || (item.pageCount !== null && (!Number.isSafeInteger(item.pageCount) || Number(item.pageCount) < 1 || Number(item.pageCount) > 20))
    || typeof item.status !== 'string' || !['UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'DELETED'].includes(item.status)
    || (item.failureCode !== null && (typeof item.failureCode !== 'string' || !/^[A-Z0-9_]{1,80}$/.test(item.failureCode)))
    || !Number.isSafeInteger(item.createdAtMs) || Number(item.createdAtMs) < 1 || !Number.isSafeInteger(item.expiresAtMs)
    || Number(item.expiresAtMs) <= Number(item.createdAtMs) || typeof item.ready !== 'boolean') return null
  if (item.status === 'READY' ? !item.ready || item.pageCount === null || item.failureCode !== null || Number(item.expiresAtMs) <= Date.now() : item.ready) return null
  return item as unknown as DemoFileMetadata
}
function failure(status: number, value: unknown): TextFileFailure {
  const code = record(value) ? value.code : null
  if (code === 1_927_005) return 'file-limit'
  if (code === 1_927_006 || status === 507) return 'capacity'
  if (status === 401 || status === 403) return 'session'
  if (status === 409) return 'conflict'
  if (status === 413) return 'too-large'
  if (status === 429) return 'busy'
  if (status === 400) return 'invalid'
  if (status === 404) return 'not-found'
  return 'unavailable'
}
function get<T>(url: string | null, parser: (value: unknown) => T | null): Promise<LibraryResult<T>> {
  if (!url) return Promise.resolve({ kind: 'error', reason: 'unavailable' })
  return new Promise(resolve => uni.request({
    url, method: 'GET', timeout: 5000,
    success: response => {
      const parsed = response.statusCode === 200 ? parser(response.data) : null
      resolve(parsed ? { kind: 'ok', data: parsed } : { kind: 'error', reason: failure(response.statusCode, response.data) })
    },
    fail: () => resolve({ kind: 'error', reason: 'network' })
  }))
}
export function getDemoLibrary(query: { keyword: string; category: string; sort: LibrarySort }): Promise<LibraryResult<LibraryPage>> {
  const base = apiUrl('library')
  const suffix = `?pageNo=1&pageSize=20&keyword=${encodeURIComponent(query.keyword)}&category=${encodeURIComponent(query.category)}&sort=${query.sort}`
  return get(base ? base + suffix : null, parsePage)
}
export async function getDemoLibraryEntry(id: string): Promise<LibraryResult<LibraryDetail>> {
  if (!validLibraryId(id)) return { kind: 'error', reason: 'invalid' }
  const base = apiUrl('library-entry')
  const result = await get(base ? `${base}?id=${encodeURIComponent(id)}` : null, parseDetail)
  return result.kind === 'ok' && result.data.entry.id !== id ? { kind: 'error', reason: 'invalid' } : result
}
export function createDemoLibraryFile(session: DemoSession, entryId: string, idempotencyKey: string): Promise<TextFileResult> {
  const url = apiUrl('library-files')
  if (!url || !validLibraryId(entryId) || !/^[!-~]{1,128}$/.test(idempotencyKey)) return Promise.resolve({ kind: 'error', reason: 'invalid' })
  return new Promise(resolve => uni.request({
    url, method: 'POST', timeout: 16000,
    header: { Authorization: `Bearer ${session.accessToken}`, 'Idempotency-Key': idempotencyKey, 'Content-Type': 'application/json' },
    data: { entryId },
    success: response => {
      const file = response.statusCode === 200 ? parseFile(response.data) : null
      resolve(file ? { kind: 'ok', file } : { kind: 'error', reason: failure(response.statusCode, response.data) })
    },
    fail: () => resolve({ kind: 'error', reason: 'network' })
  }))
}
