import type { DemoSession } from '@/api/demoSession'

export type DemoPrintState = 'QUEUED' | 'CLAIMED' | 'ACCEPTED' | 'EXECUTING' |
  'SUCCEEDED' | 'FAILED' | 'RESULT_UNKNOWN'
export interface DemoPrintReceipt {
  id: string; receiptSeq: 1; result: 'SIMULATED_SUCCESS' | 'SIMULATED_FAILED'; reason: string | null
  plannedCopies: string; plannedSheets: string; completedCopies: string; completedSheets: string
  simulation: true; createdAtMs: number
}
export interface DemoPrintTask {
  id: string; jobId: string; orderId: string; orderItemId: string; state: DemoPrintState
  version: number; fence: number; leaseId: string | null; leaseUntilMs: number | null
  plannedCopies: string; plannedSheets: string; fileId: string; fileSha256: string
  eventSeq: number; createdAtMs: number; updatedAtMs: number; receipt: DemoPrintReceipt | null
}
export type DemoPrintResult = { kind: 'ok'; task: DemoPrintTask | null } | { kind: 'unknown' }

function url(): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/print-result`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/print-result`
  // #endif
  return null
}
const uuid = (value: unknown): value is string => typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const nonNegative = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const count = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const completed = (value: unknown): value is string => typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)
const time = (value: unknown): value is number => positive(value) && value <= 8640000000000000
function receipt(value: unknown): DemoPrintReceipt | null | undefined {
  if (value == null) return null
  const row = value as Partial<DemoPrintReceipt>
  if (!uuid(row.id) || row.receiptSeq !== 1 || !['SIMULATED_SUCCESS', 'SIMULATED_FAILED'].includes(String(row.result)) ||
      !(row.reason == null || typeof row.reason === 'string') || !count(row.plannedCopies) || !count(row.plannedSheets) ||
      !completed(row.completedCopies) || !completed(row.completedSheets) || row.simulation !== true || !time(row.createdAtMs)) return undefined
  return row as DemoPrintReceipt
}
function task(value: unknown): DemoPrintTask | null | undefined {
  if (value == null) return null
  const row = value as Partial<DemoPrintTask>
  const parsedReceipt = receipt(row.receipt)
  if (!uuid(row.id) || !/^PJ[0-9A-F]{32}$/.test(String(row.jobId)) || !uuid(row.orderId) || !uuid(row.orderItemId) ||
      !['QUEUED', 'CLAIMED', 'ACCEPTED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN'].includes(String(row.state)) ||
      !positive(row.version) || !nonNegative(row.fence) || !(row.leaseId == null || uuid(row.leaseId)) ||
      !(row.leaseUntilMs == null || time(row.leaseUntilMs)) || !count(row.plannedCopies) || !count(row.plannedSheets) ||
      !uuid(row.fileId) || !/^[0-9a-f]{64}$/.test(String(row.fileSha256)) || !positive(row.eventSeq) ||
      !time(row.createdAtMs) || !time(row.updatedAtMs) || parsedReceipt === undefined) return undefined
  return { ...row, receipt: parsedReceipt } as DemoPrintTask
}

export function getDemoPrintResult(session: DemoSession, orderId: string): Promise<DemoPrintResult> {
  const endpoint = url()
  if (!endpoint || !uuid(orderId)) return Promise.resolve({ kind: 'unknown' })
  return new Promise(resolve => {
    uni.request({
      url: `${endpoint}?orderId=${encodeURIComponent(orderId)}`,
      method: 'GET', timeout: 2500,
      header: { Authorization: `Bearer ${session.accessToken}` },
      success: response => {
        const envelope = response.data as { code?: number; data?: unknown } | null
        if (response.statusCode !== 200 || envelope?.code !== 0) return resolve({ kind: 'unknown' })
        const parsed = task(envelope.data)
        return resolve(parsed === undefined ? { kind: 'unknown' } : { kind: 'ok', task: parsed })
      },
      fail: () => resolve({ kind: 'unknown' })
    })
  })
}
