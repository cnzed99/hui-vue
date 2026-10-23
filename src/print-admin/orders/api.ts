import request from '@/config/axios'
import { isAxiosError } from 'axios'

export interface OrderSummary {
  id: string
  orderNo: string
  source: 'ADMIN_DEMO'
  simulation: true
  currency: 'CNY'
  totalAmount: string
  orderState: string
  paymentState: string
  refundState: string
  deliveryMode: string
  deliveryState: string
  printState: string
  createdByActorType: 'STAFF'
  createdByActorId: number
  createdAtMs: number
}
export interface QuoteSnapshot {
  schemaVersion: number
  mode: string
  currency: string
  aggregationPolicy: string
  fileId: string
  fileSha256: string
  priceProfileId: string
  priceProfileKey: string
  priceProfileVersion: string
  pagesPerCopy: number
  side: string
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
export interface OrderItem {
  id: string
  quoteId: string
  fileId: string
  fileSha256: string
  amount: string
  quoteSnapshot: QuoteSnapshot
}
export interface OrderDetail extends OrderSummary { items: OrderItem[] }
export interface OrderEvent {
  eventId: string
  orderId: string
  type: string
  actorType: 'STAFF'
  actorId: number
  simulation: true
  aggregateVersion: number
  createdAtMs: number
}
export interface OrderPage<T> { list: T[]; total: number; pageNo: number; pageSize: number }
export type OrderFailureKind = 'disabled' | 'forbidden' | 'not-found' | 'invalid-input' |
  'session' | 'network' | 'service' | 'invalid-response'
export interface OrderFailure { kind: string; title: string; message: string }
const failures: Record<OrderFailureKind, Omit<OrderFailure, 'kind'>> = {
  disabled: { title: '演示订单尚未启用', message: '当前环境未开放演示订单查询，请等待管理员启用后重试。' },
  forbidden: { title: '没有订单查看权限', message: '当前账号无法查看这些订单，请联系管理员检查门店和订单权限。' },
  'not-found': { title: '订单不存在或不可访问', message: '请返回列表查看当前账号可访问的订单。' },
  'invalid-input': { title: '查询参数无效', message: '请返回列表第一页重新查询。' },
  session: { title: '登录状态已失效', message: '请重新登录后查看订单。' },
  network: { title: '连接订单服务失败', message: '请检查网络连接后重试。' },
  service: { title: '订单服务暂不可用', message: '本次未能获取订单信息，请稍后重试。' },
  'invalid-response': { title: '订单信息无法识别', message: '服务返回的数据不完整或超出支持范围，请联系管理员检查。' }
}
export const orderFailure = (kind: OrderFailureKind): OrderFailure => ({ kind, ...failures[kind] })
class OrderApiError extends Error {
  constructor(readonly kind: OrderFailureKind) { super(failures[kind].message); this.name = 'OrderApiError' }
}
const businessKind = (code: number): OrderFailureKind => {
  if (code === 401 || code === 1510000001) return 'session'
  if (code === 403 || (code >= 1510000002 && code <= 1510000007) || code === 1510030011) return 'forbidden'
  if (code === 1510030000) return 'disabled'
  if (code === 1510030001) return 'invalid-input'
  if (code === 1510030009) return 'not-found'
  return 'service'
}
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const text = (value: unknown): value is string => typeof value === 'string' && value.length > 0
const money = (value: unknown): value is string => typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value)
const count = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const timestamp = (value: unknown): value is number => positive(value) && value <= 8640000000000000
export const validOrderId = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const invalid = (): never => { throw new OrderApiError('invalid-response') }
// Preserve business errors before the shared interceptor reduces them to a string.
// Framework 401 continues through the normal token-refresh/relogin path.
const readResponse = (raw: unknown): unknown => {
  let result: unknown
  try { result = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { return invalid() }
  if (!object(result) || typeof result.code !== 'number') return invalid()
  if (result.code !== 0 && result.code !== 200 && result.code !== 401) throw new OrderApiError(businessKind(result.code))
  return result
}
const summary = (data: unknown): OrderSummary => {
  if (!object(data) || !validOrderId(data.id) || !text(data.orderNo) || data.source !== 'ADMIN_DEMO' ||
      data.simulation !== true || data.currency !== 'CNY' || !money(data.totalAmount) ||
      !['orderState', 'paymentState', 'refundState', 'deliveryMode', 'deliveryState', 'printState'].every(k => text(data[k])) ||
      data.createdByActorType !== 'STAFF' || !positive(data.createdByActorId) || !timestamp(data.createdAtMs)) return invalid()
  return data as unknown as OrderSummary
}
const snapshot = (data: unknown): QuoteSnapshot => {
  if (!object(data) || !positive(data.schemaVersion) || !positive(data.pagesPerCopy) ||
      !['mode', 'currency', 'aggregationPolicy', 'fileId', 'fileSha256', 'priceProfileId', 'priceProfileKey', 'priceProfileVersion', 'side'].every(k => text(data[k])) ||
      !['copies', 'sheetsPerCopy', 'totalSheets'].every(k => count(data[k])) ||
      !['simplexPricePerSheet', 'duplexPricePerSheet', 'pricePerSheet', 'totalAmount'].every(k => money(data[k])) ||
      typeof data.tierApplied !== 'boolean' || !(data.appliedTierThreshold == null || money(data.appliedTierThreshold)) ||
      !timestamp(data.createdAtMs) || !timestamp(data.expiresAtMs)) return invalid()
  if (data.tier != null && (!object(data.tier) || !money(data.tier.strictlyMoreThanSheets) ||
      !money(data.tier.simplexPricePerSheet) || !money(data.tier.duplexPricePerSheet))) return invalid()
  return data as unknown as QuoteSnapshot
}
const detail = (data: unknown): OrderDetail => {
  const base = summary(data)
  if (!object(data) || !Array.isArray(data.items) || data.items.length === 0) return invalid()
  const items = data.items.map(item => {
    if (!object(item) || !['id', 'quoteId', 'fileId', 'fileSha256'].every(k => text(item[k])) || !money(item.amount)) return invalid()
    return { ...item, quoteSnapshot: snapshot(item.quoteSnapshot) } as unknown as OrderItem
  })
  return { ...base, items }
}
const event = (data: unknown): OrderEvent => {
  if (!object(data) || !text(data.eventId) || !validOrderId(data.orderId) || !text(data.type) ||
      data.actorType !== 'STAFF' || !positive(data.actorId) || data.simulation !== true ||
      !positive(data.aggregateVersion) || !timestamp(data.createdAtMs)) return invalid()
  return data as unknown as OrderEvent
}
const page = <T>(data: unknown, parse: (value: unknown) => T, pageNo: number, pageSize: number): OrderPage<T> => {
  if (!object(data) || !Array.isArray(data.list) || typeof data.total !== 'number' ||
      !Number.isSafeInteger(data.total) || data.total < 0 || data.pageNo !== pageNo || data.pageSize !== pageSize ||
      data.list.length > pageSize || data.total < data.list.length) return invalid()
  return { list: data.list.map(parse), total: data.total, pageNo, pageSize }
}
export const getOrders = async (pageNo: number, pageSize: number): Promise<OrderPage<OrderSummary>> =>
  page(await request.get<unknown>({ url: '/print/orders', params: { pageNo, pageSize }, transformResponse: [readResponse] }), summary, pageNo, pageSize)
export const getOrder = async (id: string): Promise<OrderDetail> => {
  if (!validOrderId(id)) throw new OrderApiError('invalid-input')
  const data = detail(await request.get<unknown>({ url: `/print/orders/${encodeURIComponent(id)}`, transformResponse: [readResponse] }))
  if (data.id !== id) return invalid()
  return data
}
export const getOrderEvents = async (id: string, pageNo: number, pageSize: number): Promise<OrderPage<OrderEvent>> => {
  if (!validOrderId(id)) throw new OrderApiError('invalid-input')
  const data = page(await request.get<unknown>({ url: `/print/orders/${encodeURIComponent(id)}/events`, params: { pageNo, pageSize }, transformResponse: [readResponse] }), event, pageNo, pageSize)
  if (data.list.some(item => item.orderId !== id)) return invalid()
  return data
}
export const classifyOrderError = (error: unknown): OrderFailure => {
  if (error instanceof OrderApiError) return orderFailure(error.kind)
  if (isAxiosError(error)) {
    if (error.response?.status === 401) return orderFailure('session')
    if (error.response?.status === 403) return orderFailure('forbidden')
    if (error.response?.status === 404) return orderFailure('not-found')
    return orderFailure(error.response ? 'service' : 'network')
  }
  return orderFailure(typeof error === 'string' ? 'session' : 'service')
}
