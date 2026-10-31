import type { DemoSession } from '@/api/demoSession'

export interface DemoPayment {
  id: string
  orderId: string
  provider: 'SIMULATED'
  mode: 'DEMO'
  state: 'SUCCEEDED'
  simulation: true
  currency: 'CNY'
  amount: string
  createdAtMs: number
  debit: { side: 'DEBIT'; account: 'DEMO_CUSTOMER'; currency: 'CNY'; amount: string }
  credit: { side: 'CREDIT'; account: 'DEMO_STORE'; currency: 'CNY'; amount: string }
}

function apiUrl(path: 'payments' | 'payment'): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/demo/${path}`
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/demo/${path}`
  // #endif
  return null
}

function parseEnvelope(value: unknown): DemoPayment | null {
  const envelope = value as { code?: number; data?: Partial<DemoPayment> }
  const receipt = envelope?.data
  const decimal = /^(0|[1-9][0-9]*)(\.[0-9]+)?$/
  if (envelope?.code !== 0 || !receipt || typeof receipt.id !== 'string'
    || typeof receipt.orderId !== 'string' || receipt.provider !== 'SIMULATED' || receipt.mode !== 'DEMO'
    || receipt.state !== 'SUCCEEDED' || receipt.simulation !== true || receipt.currency !== 'CNY'
    || typeof receipt.amount !== 'string' || !decimal.test(receipt.amount)
    || receipt.debit?.side !== 'DEBIT' || receipt.debit?.account !== 'DEMO_CUSTOMER'
    || receipt.credit?.side !== 'CREDIT' || receipt.credit?.account !== 'DEMO_STORE'
    || receipt.debit.amount !== receipt.amount || receipt.credit.amount !== receipt.amount
    || receipt.debit.currency !== 'CNY' || receipt.credit.currency !== 'CNY') return null
  return receipt as DemoPayment
}

export function createDemoPayment(session: DemoSession, orderId: string): Promise<DemoPayment | null> {
  const url = apiUrl('payments')
  if (!url || !orderId) return Promise.resolve(null)
  return new Promise(resolve => {
    uni.request({
      url,
      method: 'POST',
      timeout: 3500,
      header: {
        Authorization: `Bearer ${session.accessToken}`,
        'Idempotency-Key': `miniapp-payment-v1-${orderId}`
      },
      data: { orderId, mode: 'DEMO' },
      success: response => resolve(response.statusCode === 200 ? parseEnvelope(response.data) : null),
      fail: () => resolve(null)
    })
  })
}

export function getDemoPayment(session: DemoSession, orderId: string): Promise<DemoPayment | null> {
  const base = apiUrl('payment')
  if (!base || !orderId) return Promise.resolve(null)
  return new Promise(resolve => {
    uni.request({
      url: `${base}?orderId=${encodeURIComponent(orderId)}`,
      method: 'GET',
      timeout: 2500,
      header: { Authorization: `Bearer ${session.accessToken}` },
      success: response => resolve(response.statusCode === 200 ? parseEnvelope(response.data) : null),
      fail: () => resolve(null)
    })
  })
}
