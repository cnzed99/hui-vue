import type { DemoSession } from '@/api/demoSession'
import { parseDemoOrderEnvelope } from '@/api/demoOrders'
import type { DemoOrder } from '@/api/demoOrders'

export type CancellationOutcome = 'CANCELLED_UNPAID' | 'CANCELLED_REFUNDED' | 'MANUAL_REVIEW_REQUIRED'
export interface CancellationResult {
  id: string; orderId: string; outcome: CancellationOutcome; reason: string
  orderVersionBefore: number; orderVersionAfter: number; taskId: string | null
  taskStateBefore: string | null; taskStateAfter: string | null
  taskVersionBefore: number | null; taskVersionAfter: number | null
  taskFenceBefore: number | null; taskFenceAfter: number | null; createdAtMs: number; simulation: true
}
export interface DemoRefundReceipt {
  id: string; cancellationId: string; paymentReceiptId: string; orderId: string
  provider: 'SIMULATED'; mode: 'DEMO'; state: 'SUCCEEDED'; simulation: true
  currency: 'CNY'; amount: string; createdAtMs: number
  debit: { side: 'DEBIT'; account: 'DEMO_STORE'; currency: 'CNY'; amount: string }
  credit: { side: 'CREDIT'; account: 'DEMO_CUSTOMER'; currency: 'CNY'; amount: string }
}
export interface DemoReviewSummary {
  cancellationId: string; status: 'PENDING'; taskId: string; taskStateAtRequest: string
  taskVersionAtRequest: number; fenceAtRequest: number; createdAtMs: number
}
export interface CancellationAction {
  originalResult: CancellationResult; currentOrder: DemoOrder
  refundReceipt: DemoRefundReceipt | null; reviewSummary: DemoReviewSummary | null
}
export interface CancellationRead {
  currentOrder: DemoOrder
  capability: { action: 'CANCEL_UNPAID' | 'CANCEL_AND_REFUND' | 'REQUEST_REVIEW' | 'NONE'; reason: string; expectedVersion: number }
  cancellation: CancellationAction | null
}
export interface PendingCancellation { schemaVersion: 1; principalId: string; orderId: string; expectedVersion: number; idempotencyKey: string }
export type CancellationWrite = { kind: 'ok'; result: CancellationAction }
  | { kind: 'rejected'; reason: 'STALE_VERSION' | 'ALREADY_RECORDED' | 'DISABLED' | 'INVALID_INPUT' | 'IDEMPOTENCY_CONFLICT' }
  | { kind: 'unknown' }

const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const nonNegative = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const taskStates = ['QUEUED', 'CLAIMED', 'ACCEPTED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN']
const outcomes = ['CANCELLED_UNPAID', 'CANCELLED_REFUNDED', 'MANUAL_REVIEW_REQUIRED']
const reasons = ['UNPAID', 'NEVER_CLAIMED', 'REQUIRES_MANUAL_REVIEW']
const nullable = <T>(value: unknown, test: (value: unknown) => value is T): value is T | null => value === null || test(value)

function endpoint(path: 'cancellation' | 'cancellations'): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/${path}`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/${path}`
  // #endif
  return null
}
function parseAction(value: unknown, expectedOrderId: string): CancellationAction | null {
  const action = value as Partial<CancellationAction> | null
  const original = action?.originalResult
  const order = parseDemoOrderEnvelope({ code: 0, data: action?.currentOrder })
  if (!action || !original || !order || order.id !== expectedOrderId || original.orderId !== expectedOrderId
    || !uuid(original.id) || !outcomes.includes(original.outcome) || !reasons.includes(original.reason)
    || !positive(original.orderVersionBefore) || !positive(original.orderVersionAfter)
    || !positive(original.createdAtMs) || original.simulation !== true || !nullable(original.taskId, uuid)
    || !nullable(original.taskVersionBefore, positive) || !nullable(original.taskVersionAfter, positive)
    || !nullable(original.taskFenceBefore, nonNegative) || !nullable(original.taskFenceAfter, nonNegative)) return null
  const noTask = original.taskId === null && original.taskStateBefore === null && original.taskStateAfter === null
    && original.taskVersionBefore === null && original.taskVersionAfter === null
    && original.taskFenceBefore === null && original.taskFenceAfter === null
  if (order.cancellationOutcome !== original.outcome) return null
  if (original.outcome === 'CANCELLED_UNPAID') {
    if (!noTask || original.reason !== 'UNPAID' || original.orderVersionBefore !== 1 || original.orderVersionAfter !== 2
      || order.orderState !== 'CANCELLED' || order.paymentState !== 'UNPAID' || action.refundReceipt !== null || action.reviewSummary !== null) return null
  } else if (original.outcome === 'CANCELLED_REFUNDED') {
    const queued = uuid(original.taskId) && original.taskStateBefore === 'QUEUED' && original.taskStateAfter === 'CANCELLED'
      && original.taskVersionBefore === 1 && original.taskVersionAfter === 2 && original.taskFenceBefore === 0 && original.taskFenceAfter === 0
      && original.orderVersionBefore === 3 && original.orderVersionAfter === 4
    if (!(noTask && original.orderVersionBefore === 2 && original.orderVersionAfter === 3 || queued)
      || original.reason !== 'NEVER_CLAIMED' || order.orderState !== 'CANCELLED' || order.refundState !== 'SUCCEEDED' || action.reviewSummary !== null) return null
    const refund = action.refundReceipt
    if (!refund || !uuid(refund.id) || !uuid(refund.paymentReceiptId) || refund.cancellationId !== original.id
      || refund.orderId !== expectedOrderId || refund.provider !== 'SIMULATED' || refund.mode !== 'DEMO'
      || refund.state !== 'SUCCEEDED' || refund.simulation !== true || refund.currency !== 'CNY'
      || refund.amount !== order.totalAmount || !positive(refund.createdAtMs)
      || refund.debit?.side !== 'DEBIT' || refund.debit.account !== 'DEMO_STORE'
      || refund.credit?.side !== 'CREDIT' || refund.credit.account !== 'DEMO_CUSTOMER'
      || refund.debit.currency !== refund.currency || refund.credit.currency !== refund.currency
      || refund.debit.amount !== refund.amount || refund.credit.amount !== refund.amount) return null
  } else {
    const review = action.reviewSummary
    if (original.reason !== 'REQUIRES_MANUAL_REVIEW' || !uuid(original.taskId)
      || !positive(original.taskVersionBefore) || !nonNegative(original.taskFenceBefore)
      || !taskStates.includes(original.taskStateBefore || '') || original.taskStateBefore !== original.taskStateAfter
      || original.taskVersionBefore !== original.taskVersionAfter || original.taskFenceBefore !== original.taskFenceAfter
      || original.orderVersionBefore !== original.orderVersionAfter || action.refundReceipt !== null
      || order.orderState !== 'OPEN' || !review || review.cancellationId !== original.id || review.status !== 'PENDING'
      || review.taskId !== original.taskId || review.taskStateAtRequest !== original.taskStateBefore
      || review.taskVersionAtRequest !== original.taskVersionBefore || review.fenceAtRequest !== original.taskFenceBefore
      || review.createdAtMs !== original.createdAtMs) return null
  }
  return { ...action, currentOrder: order } as CancellationAction
}
export function parseCancellationRead(value: unknown, orderId: string): CancellationRead | null {
  const row = value as Partial<CancellationRead> | null
  const order = parseDemoOrderEnvelope({ code: 0, data: row?.currentOrder })
  const capability = row?.capability
  if (!row || !order || order.id !== orderId || !capability || !positive(capability.expectedVersion)
    || capability.expectedVersion !== order.version || !['CANCEL_UNPAID', 'CANCEL_AND_REFUND', 'REQUEST_REVIEW', 'NONE'].includes(capability.action)
    || ![...reasons, 'WRITE_DISABLED', 'ALREADY_RECORDED'].includes(capability.reason)) return null
  const cancellation = row.cancellation === null ? null : parseAction(row.cancellation, orderId)
  if (row.cancellation !== null && !cancellation || cancellation && capability.action !== 'NONE'
    || cancellation === null && order.cancellationOutcome != null) return null
  const pair = `${capability.action}:${capability.reason}`
  if (!['CANCEL_UNPAID:UNPAID', 'CANCEL_AND_REFUND:NEVER_CLAIMED', 'REQUEST_REVIEW:REQUIRES_MANUAL_REVIEW',
    'NONE:WRITE_DISABLED', 'NONE:ALREADY_RECORDED'].includes(pair)
    || cancellation && capability.reason !== 'ALREADY_RECORDED'
    || !cancellation && capability.reason === 'ALREADY_RECORDED') return null
  return { currentOrder: order, capability, cancellation }
}
export function getDemoCancellation(session: DemoSession, orderId: string): Promise<{ kind: 'ok'; value: CancellationRead } | { kind: 'unknown' }> {
  const url = endpoint('cancellation')
  if (!url || !uuid(orderId)) return Promise.resolve({ kind: 'unknown' })
  return new Promise(resolve => uni.request({
    url: `${url}?orderId=${encodeURIComponent(orderId)}`, method: 'GET', timeout: 3500,
    header: { Authorization: `Bearer ${session.accessToken}` },
    success: response => {
      const envelope = response.data as { code?: number; data?: unknown } | null
      const value = response.statusCode === 200 && envelope?.code === 0 ? parseCancellationRead(envelope.data, orderId) : null
      resolve(value ? { kind: 'ok', value } : { kind: 'unknown' })
    }, fail: () => resolve({ kind: 'unknown' })
  }))
}
export function postDemoCancellation(session: DemoSession, pending: PendingCancellation): Promise<CancellationWrite> {
  const url = endpoint('cancellations')
  if (!url || pending.principalId !== session.principalId) return Promise.resolve({ kind: 'unknown' })
  return new Promise(resolve => uni.request({
    url, method: 'POST', timeout: 6000, header: { Authorization: `Bearer ${session.accessToken}`, 'Idempotency-Key': pending.idempotencyKey },
    data: { orderId: pending.orderId, expectedVersion: pending.expectedVersion },
    success: response => {
      const envelope = response.data as { code?: number; data?: unknown } | null
      const result = response.statusCode === 200 && envelope?.code === 0 ? parseAction(envelope.data, pending.orderId) : null
      if (result) { resolve({ kind: 'ok', result }); return }
      const rejected: Record<number, Extract<CancellationWrite, { kind: 'rejected' }>['reason']> = {
        1934001: 'DISABLED', 1934002: 'INVALID_INPUT', 1934004: 'STALE_VERSION',
        1934005: 'IDEMPOTENCY_CONFLICT', 1934006: 'ALREADY_RECORDED'
      }
      const reason = typeof envelope?.code === 'number' ? rejected[envelope.code] : undefined
      resolve(reason ? { kind: 'rejected', reason } : { kind: 'unknown' })
    }, fail: () => resolve({ kind: 'unknown' })
  }))
}
const pendingKey = (principal: string, order: string) => `miniapp-cancellation-pending-v1:${principal}:${order}`
export function loadPendingCancellation(principalId: string, orderId: string): PendingCancellation | null {
  const row = uni.getStorageSync(pendingKey(principalId, orderId)) as Partial<PendingCancellation> | null
  return row?.schemaVersion === 1 && row.principalId === principalId && row.orderId === orderId && positive(row.expectedVersion)
    && typeof row.idempotencyKey === 'string' && /^[!-~]{1,128}$/.test(row.idempotencyKey) ? row as PendingCancellation : null
}
export function savePendingCancellation(value: PendingCancellation) { uni.setStorageSync(pendingKey(value.principalId, value.orderId), value) }
export function clearPendingCancellation(principalId: string, orderId: string) { uni.removeStorageSync(pendingKey(principalId, orderId)) }
