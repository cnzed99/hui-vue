export type DraftSource = 'LOCAL_SELECTION' | 'FIXTURE'
export type PrintColor = 'BLACK_WHITE' | 'COLOR'
export type PrintSide = 'SIMPLEX' | 'DUPLEX'

export interface PrintDraft {
  schemaVersion: 1
  source: DraftSource
  fileName: string
  sizeBytes: number
  pages: number | null
  paper: 'A4'
  color: PrintColor
  side: PrintSide
  copies: number
}

const STORAGE_KEY = 'miniapp-print-draft-v1'

export function createDraft(source: DraftSource, fileName: string, sizeBytes: number, pages: number | null): PrintDraft {
  return {
    schemaVersion: 1,
    source,
    fileName,
    sizeBytes,
    pages,
    paper: 'A4',
    color: 'BLACK_WHITE',
    side: 'SIMPLEX',
    copies: 1
  }
}

export function saveDraft(draft: PrintDraft) {
  uni.setStorageSync(STORAGE_KEY, draft)
}

export function loadDraft(): PrintDraft | null {
  const value = uni.getStorageSync(STORAGE_KEY) as Partial<PrintDraft> | null
  if (!value || value.schemaVersion !== 1 || typeof value.fileName !== 'string' || !value.fileName
    || !Number.isSafeInteger(value.sizeBytes) || Number(value.sizeBytes) <= 0
    || (value.pages !== null && (!Number.isSafeInteger(value.pages) || Number(value.pages) <= 0))
    || value.paper !== 'A4' || !['BLACK_WHITE', 'COLOR'].includes(value.color || '')
    || !['SIMPLEX', 'DUPLEX'].includes(value.side || '')
    || !Number.isSafeInteger(value.copies) || Number(value.copies) < 1 || Number(value.copies) > 99
    || !['LOCAL_SELECTION', 'FIXTURE'].includes(value.source || '')) return null
  return value as PrintDraft
}
