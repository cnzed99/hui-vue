import { getActiveDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'

export type DraftSource = 'LOCAL_SELECTION' | 'FIXTURE' | 'SERVER_UPLOAD'
export type PrintColor = 'BLACK_WHITE' | 'COLOR'
export type PrintSide = 'SIMPLEX' | 'DUPLEX'

export interface PrintDraft {
  schemaVersion: 1
  source: DraftSource
  fileName: string
  sizeBytes: number
  pages: number | null
  fileId: string | null
  archiveOwner?: Pick<DemoSession, 'sessionId' | 'principalId'>
  paper: 'A4' | 'PHOTO_6IN'
  color: PrintColor
  side: PrintSide
  copies: number
}

const STORAGE_KEY = 'miniapp-print-draft-v1'

export function createDraft(source: DraftSource, fileName: string, sizeBytes: number, pages: number | null, fileId: string | null = null): PrintDraft {
  return {
    schemaVersion: 1,
    source,
    fileName,
    sizeBytes,
    pages,
    fileId,
    paper: 'A4',
    color: 'BLACK_WHITE',
    side: 'SIMPLEX',
    copies: 1
  }
}

export function saveDraft(draft: PrintDraft) {
  uni.setStorageSync(STORAGE_KEY, draft)
}

export function archiveDraftMatchesSession(draft: Partial<PrintDraft>, session: DemoSession | null) {
  if (draft.archiveOwner === undefined) return true
  const owner = draft.archiveOwner
  return !!owner && !!session && draft.source === 'SERVER_UPLOAD'
    && owner.sessionId === session.sessionId && owner.principalId === session.principalId
}

export function loadDraft(): PrintDraft | null {
  const value = uni.getStorageSync(STORAGE_KEY) as Partial<PrintDraft> | null
  if (value?.archiveOwner !== undefined && !archiveDraftMatchesSession(value, getActiveDemoSession())) {
    uni.removeStorageSync(STORAGE_KEY)
    return null
  }
  if (!value || value.schemaVersion !== 1 || typeof value.fileName !== 'string' || !value.fileName
    || !Number.isSafeInteger(value.sizeBytes) || Number(value.sizeBytes) <= 0
    || (value.pages !== null && (!Number.isSafeInteger(value.pages) || Number(value.pages) <= 0))
    || (value.fileId !== null && (typeof value.fileId !== 'string' || !/^[0-9a-f-]{36}$/.test(value.fileId)))
    || !['A4', 'PHOTO_6IN'].includes(value.paper || '') || !['BLACK_WHITE', 'COLOR'].includes(value.color || '')
    || (value.paper === 'PHOTO_6IN' && (value.color !== 'COLOR' || value.side !== 'SIMPLEX' || !value.archiveOwner || value.source !== 'SERVER_UPLOAD'))
    || !['SIMPLEX', 'DUPLEX'].includes(value.side || '')
    || !Number.isSafeInteger(value.copies) || Number(value.copies) < 1 || Number(value.copies) > 99
    || !['LOCAL_SELECTION', 'FIXTURE', 'SERVER_UPLOAD'].includes(value.source || '')
    || (value.source === 'SERVER_UPLOAD' && (!value.fileId || value.pages === null))) return null
  return value as PrintDraft
}

export function createPhotoDraft(file: { id: string; displayName: string; sizeBytes: number; pageCount: number | null; status: string }, session: DemoSession, copies: number): PrintDraft | null {
  if (file.status !== 'READY' || !Number.isSafeInteger(file.pageCount) || Number(file.pageCount) < 1 || Number(file.pageCount) > 30
    || !Number.isSafeInteger(copies) || copies < 1 || copies > 99 || !archiveDraftMatchesSession({ source: 'SERVER_UPLOAD', archiveOwner: session }, getActiveDemoSession())) return null
  return { ...createDraft('SERVER_UPLOAD', file.displayName, file.sizeBytes, file.pageCount, file.id),
    paper: 'PHOTO_6IN', color: 'COLOR', side: 'SIMPLEX', copies,
    archiveOwner: { sessionId: session.sessionId, principalId: session.principalId } }
}
