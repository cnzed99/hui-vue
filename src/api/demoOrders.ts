import type { DemoSession } from '@/api/demoSession'
import type { DemoQuoteSnapshot } from '@/api/demoQuotes'

export interface DemoOrder {
  id: string
  orderNo: string
  source: 'MINIAPP_DEMO'
  simulation: true
  currency: 'CNY'
  totalAmount: string
  orderState: 'OPEN'
  paymentState: 'UNPAID' | 'SUCCEEDED'
  refundState: 'NONE'
  printState: 'NOT_CREATED' | 'QUEUED' | 'CLAIMED' | 'ACCEPTED' | 'EXECUTING' |
    'SUCCEEDED' | 'FAILED' | 'RESULT_UNKNOWN'
  version: number
  createdAtMs: number
  updatedAtMs: number
  item: {
    id: string
    quoteId: string
    fileId: string
    fileSha256: string
    amount: string
    quoteSnapshot: DemoQuoteSnapshot
  }
}

export type DemoOrderCreateResult =
  | { kind: 'ok'; order: DemoOrder }
  | { kind: 'rejected' }
  | { kind: 'unknown' }

export interface DemoOrderPage {
  list: DemoOrder[]
  pageNo: number
  pageSize: number
  total: number
  hasMore: boolean
}

export type DemoOrderPageResult = { kind: 'ok'; page: DemoOrderPage } | { kind: 'unknown' }

function apiUrl(path: 'orders' | 'order'): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/${path}`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/${path}`
  // #endif
  return null
}

export function parseDemoOrderEnvelope(value: unknown): DemoOrder | null {
  const envelope = value as { code?: number; data?: Partial<DemoOrder> }
  const order = envelope?.data
  const item = order?.item
  const decimal = /^(0|[1-9][0-9]*)(\.[0-9]+)?$/
  const version = Number(order?.version)
  const stateVersionValid = order?.printState === 'NOT_CREATED'
    ? (order.paymentState === 'UNPAID' && version === 1) || (order.paymentState === 'SUCCEEDED' && version === 2)
    : ['QUEUED', 'CLAIMED', 'ACCEPTED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN'].includes(order?.printState || '')
      && order?.paymentState === 'SUCCEEDED' && Number.isSafeInteger(version) && version >= 3
  if (envelope?.code !== 0 || !order || typeof order.id !== 'string'
    || typeof order.orderNo !== 'string' || order.source !== 'MINIAPP_DEMO' || order.simulation !== true
    || order.currency !== 'CNY' || typeof order.totalAmount !== 'string' || !decimal.test(order.totalAmount)
    || order.orderState !== 'OPEN' || !['UNPAID', 'SUCCEEDED'].includes(order.paymentState || '')
    || order.refundState !== 'NONE' || !stateVersionValid || !item || typeof item.id !== 'string'
    || typeof item.quoteId !== 'string' || typeof item.fileId !== 'string'
    || typeof item.amount !== 'string' || item.amount !== order.totalAmount
    || item.quoteSnapshot?.mode !== 'DEMO' || item.quoteSnapshot?.totalAmount !== order.totalAmount) return null
  return order as DemoOrder
}

export function createDemoOrder(session: DemoSession, quoteId: string): Promise<DemoOrderCreateResult> {
  const url = apiUrl('orders')
  if (!url || !quoteId) return Promise.resolve({ kind: 'rejected' })
  return new Promise(resolve => {
    uni.request({
      url,
      method: 'POST',
      timeout: 3500,
      header: {
        Authorization: `Bearer ${session.accessToken}`,
        'Idempotency-Key': `miniapp-order-v1-${quoteId}`
      },
      data: { quoteId },
      success: response => {
        const order = response.statusCode === 200 ? parseDemoOrderEnvelope(response.data) : null
        if (order) return resolve({ kind: 'ok', order })
        const code = Number((response.data as { code?: number } | null)?.code)
        const provesNoOrder = [1_929_002, 1_929_004, 1_929_005, 1_929_007,
          1_929_008, 1_929_012].includes(code)
        return resolve(provesNoOrder ? { kind: 'rejected' } : { kind: 'unknown' })
      },
      fail: () => resolve({ kind: 'unknown' })
    })
  })
}

export function getDemoOrder(session: DemoSession, id: string): Promise<DemoOrder | null> {
  const base = apiUrl('order')
  if (!base || !id) return Promise.resolve(null)
  return new Promise(resolve => {
    uni.request({
      url: `${base}?id=${encodeURIComponent(id)}`,
      method: 'GET',
      timeout: 2500,
      header: { Authorization: `Bearer ${session.accessToken}` },
      success: response => resolve(response.statusCode === 200 ? parseDemoOrderEnvelope(response.data) : null),
      fail: () => resolve(null)
    })
  })
}

function parseDemoOrderPageEnvelope(value: unknown): DemoOrderPage | null {
  const envelope = value as { code?: number; data?: Partial<DemoOrderPage> }
  const page = envelope?.data
  if (envelope?.code !== 0 || !page || !Array.isArray(page.list)
    || !Number.isSafeInteger(page.pageNo) || Number(page.pageNo) < 1
    || !Number.isSafeInteger(page.pageSize) || Number(page.pageSize) < 1 || Number(page.pageSize) > 20
    || !Number.isSafeInteger(page.total) || Number(page.total) < 0
    || typeof page.hasMore !== 'boolean' || page.list.length > Number(page.pageSize)) return null
  const list = page.list.map(item => parseDemoOrderEnvelope({ code: 0, data: item }))
  if (list.some(item => item === null)) return null
  return { ...page, list: list as DemoOrder[] } as DemoOrderPage
}

export function listDemoOrders(session: DemoSession, pageNo: number, pageSize = 20): Promise<DemoOrderPageResult> {
  const base = apiUrl('orders')
  if (!base || !Number.isSafeInteger(pageNo) || pageNo < 1 || pageNo > 10_000
    || !Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 20) return Promise.resolve({ kind: 'unknown' })
  return new Promise(resolve => {
    uni.request({
      url: `${base}?pageNo=${pageNo}&pageSize=${pageSize}`,
      method: 'GET',
      timeout: 2500,
      header: { Authorization: `Bearer ${session.accessToken}` },
      success: response => {
        const page = response.statusCode === 200 ? parseDemoOrderPageEnvelope(response.data) : null
        resolve(page && page.pageNo === pageNo && page.pageSize === pageSize
          ? { kind: 'ok', page }
          : { kind: 'unknown' })
      },
      fail: () => resolve({ kind: 'unknown' })
    })
  })
}
