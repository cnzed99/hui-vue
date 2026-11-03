import type { DemoSession } from '@/api/demoSession'
import { listDemoOrders } from '@/api/demoOrders'
import { getDemoPayment } from '@/api/demoPayments'
import { getDemoCancellation } from '@/api/demoCancellations'

export interface DemoMemberActivity {
  id: string
  orderId: string
  orderNo: string
  kind: 'SIMULATED_PAYMENT' | 'SIMULATED_REFUND'
  amount: string
  createdAtMs: number
}

export interface DemoMemberActivityPage {
  list: DemoMemberActivity[]
  pageNo: number
  hasMore: boolean
  totalOrders: number
}

export type DemoMemberActivityResult =
  | { kind: 'ok'; page: DemoMemberActivityPage }
  | { kind: 'unknown' }

export async function loadDemoMemberActivityPage(session: DemoSession, pageNo: number): Promise<DemoMemberActivityResult> {
  const orders = await listDemoOrders(session, pageNo, 20)
  if (orders.kind !== 'ok') return { kind: 'unknown' }
  const paid = orders.page.list.filter(order => order.paymentState === 'SUCCEEDED')
  const grouped = await Promise.all(paid.map(async order => {
    const payment = await getDemoPayment(session, order.id)
    if (!payment || payment.orderId !== order.id || payment.amount !== order.totalAmount
      || !Number.isSafeInteger(payment.createdAtMs) || payment.createdAtMs <= 0) return null
    const rows: DemoMemberActivity[] = [{
      id: `payment:${payment.id}`,
      orderId: order.id,
      orderNo: order.orderNo,
      kind: 'SIMULATED_PAYMENT',
      amount: payment.amount,
      createdAtMs: payment.createdAtMs
    }]
    if (order.refundState === 'SUCCEEDED') {
      const cancellation = await getDemoCancellation(session, order.id)
      const refund = cancellation.kind === 'ok' ? cancellation.value.cancellation?.refundReceipt : null
      if (!refund || refund.orderId !== order.id || refund.amount !== order.totalAmount
        || refund.paymentReceiptId !== payment.id
        || !Number.isSafeInteger(refund.createdAtMs) || refund.createdAtMs <= 0) return null
      rows.push({
        id: `refund:${refund.id}`,
        orderId: order.id,
        orderNo: order.orderNo,
        kind: 'SIMULATED_REFUND',
        amount: refund.amount,
        createdAtMs: refund.createdAtMs
      })
    }
    return rows
  }))
  if (grouped.some(rows => rows === null)) return { kind: 'unknown' }
  const list = grouped.flatMap(rows => rows || []).sort((a, b) => b.createdAtMs - a.createdAtMs || a.id.localeCompare(b.id))
  return { kind: 'ok', page: { list, pageNo, hasMore: orders.page.hasMore, totalOrders: orders.page.total } }
}
