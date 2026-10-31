import type { DemoSession } from '@/api/demoSession'
import type { PrintColor, PrintSide } from '@/domain/printDraft'

export interface DemoQuoteSnapshot {
  schemaVersion: 1
  mode: 'DEMO'
  currency: 'CNY'
  aggregationPolicy: 'PER_FILE_ALL_COPIES'
  fileId: string
  fileSha256: string
  paper: 'A4'
  color: 'BLACK_WHITE'
  priceProfileId: string
  priceProfileKey: string
  priceProfileVersion: string
  pagesPerCopy: number
  side: PrintSide
  copies: string
  sheetsPerCopy: string
  totalSheets: string
  simplexPricePerSheet: string
  duplexPricePerSheet: string
  tier: null | { strictlyMoreThanSheets: string; simplexPricePerSheet: string; duplexPricePerSheet: string }
  pricePerSheet: string
  totalAmount: string
  tierApplied: boolean
  appliedTierThreshold: string | null
  createdAtMs: number
  expiresAtMs: number
}

export interface DemoQuote {
  id: string
  status: 'VALID' | 'EXPIRED'
  snapshot: DemoQuoteSnapshot
}

export type DemoQuoteCreateResult =
  | { kind: 'ok'; quote: DemoQuote }
  | { kind: 'expired' }
  | { kind: 'failed' }

function apiUrl(path: 'quotes' | 'quote'): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/${path}`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/${path}`
  // #endif
  return null
}

function parseEnvelope(value: unknown): DemoQuote | null {
  const envelope = value as { code?: number; data?: Partial<DemoQuote> }
  const quote = envelope?.data
  const snapshot = quote?.snapshot as Partial<DemoQuoteSnapshot> | undefined
  const decimal = /^(0|[1-9][0-9]*)(\.[0-9]+)?$/
  if (envelope?.code !== 0 || !quote || typeof quote.id !== 'string'
    || !['VALID', 'EXPIRED'].includes(quote.status || '') || !snapshot
    || snapshot.schemaVersion !== 1 || snapshot.mode !== 'DEMO' || snapshot.currency !== 'CNY'
    || snapshot.aggregationPolicy !== 'PER_FILE_ALL_COPIES' || snapshot.paper !== 'A4'
    || snapshot.color !== 'BLACK_WHITE' || !['SIMPLEX', 'DUPLEX'].includes(snapshot.side || '')
    || !Number.isSafeInteger(snapshot.pagesPerCopy) || Number(snapshot.pagesPerCopy) < 1
    || typeof snapshot.totalSheets !== 'string' || !/^[1-9][0-9]*$/.test(snapshot.totalSheets)
    || typeof snapshot.pricePerSheet !== 'string' || !decimal.test(snapshot.pricePerSheet)
    || typeof snapshot.totalAmount !== 'string' || !decimal.test(snapshot.totalAmount)
    || !Number.isSafeInteger(snapshot.expiresAtMs)) return null
  return quote as DemoQuote
}

export function createDemoQuote(session: DemoSession, fileId: string, paper: 'A4', color: PrintColor,
  copies: number, side: PrintSide, round: number): Promise<DemoQuoteCreateResult> {
  const url = apiUrl('quotes')
  if (!url || !fileId || paper !== 'A4' || color !== 'BLACK_WHITE'
    || !Number.isSafeInteger(copies) || copies < 1 || copies > 99
    || !Number.isSafeInteger(round) || round < 1 || round > 9999) return Promise.resolve({ kind: 'failed' })
  const idempotencyKey = `miniapp-quote-v1-${fileId}-${paper}-${color}-${copies}-${side}-${round}`
  return new Promise(resolve => {
    uni.request({
      url,
      method: 'POST',
      timeout: 3500,
      header: { Authorization: `Bearer ${session.accessToken}`, 'Idempotency-Key': idempotencyKey },
      data: { fileId, paper, color, copies, side },
      success: response => {
        const quote = response.statusCode === 200 ? parseEnvelope(response.data) : null
        if (quote) return resolve({ kind: 'ok', quote })
        const error = response.data as { code?: number } | null
        return resolve(response.statusCode === 410 && error?.code === 1_928_009
          ? { kind: 'expired' }
          : { kind: 'failed' })
      },
      fail: () => resolve({ kind: 'failed' })
    })
  })
}

export function getDemoQuote(session: DemoSession, id: string): Promise<DemoQuote | null> {
  const base = apiUrl('quote')
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
