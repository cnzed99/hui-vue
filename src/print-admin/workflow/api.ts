import request from '@/config/axios'
import { isAxiosError } from 'axios'
import type { QuoteSnapshot } from '../orders/api'

export interface FileMetadata {
  id: string; displayName: string; mediaType: 'application/pdf'; sizeBytes: number
  pageCount: number | null; status: string; failureCode: string | null
  createdAt: string; expiresAt: string; contentAvailable: boolean
}
export interface DemoQuote { id: string; status: 'VALID' | 'EXPIRED'; snapshot: QuoteSnapshot }
export interface WorkflowOrder {
  id: string; orderNo: string; source: 'ADMIN_DEMO'; simulation: true; currency: 'CNY'
  totalAmount: string; paymentState: string; printState: string
}
export interface PaymentReceipt {
  id: string; orderId: string; provider: 'SIMULATED'; state: 'SUCCEEDED'; simulation: true
  currency: 'CNY'; amount: string; actorType: 'STAFF'; actorId: number; createdAtMs: number; jobIds: string[]
}
export interface QuoteInput { fileId: string; priceProfileId: string; copies: number; side: 'SIMPLEX' | 'DUPLEX' }
export interface WorkflowFailure { kind: string; title: string; message: string; uncertain: boolean }
export class WorkflowApiError extends Error {
  constructor(readonly code: number, readonly kind: string) { super(kind); this.name = 'WorkflowApiError' }
}
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown): v is string => typeof v === 'string' && v.length > 0
const positive = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
const timestamp = (v: unknown): v is number => positive(v) && v <= 8640000000000000
const money = (v: unknown): v is string => typeof v === 'string' && /^\d+(?:\.\d+)?$/.test(v)
const count = (v: unknown): v is string => typeof v === 'string' && /^[1-9]\d*$/.test(v)
const uint = (v: unknown): v is string => typeof v === 'string' && /^\d+$/.test(v)
export const validUuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
export const configuredProfile = (v: unknown): string | null => validUuid(v) ? v : null
export const parseCopies = (v: string): number | null => {
  if (!/^[1-9]\d*$/.test(v)) return null
  const value = Number(v)
  return positive(value) ? value : null
}
const invalid = (): never => { throw new WorkflowApiError(0, 'invalid-response') }
const iso = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
// File Instants use epoch milliseconds on the wire; keep the existing ISO view model.
const fileTime = (v: unknown): string => {
  if (typeof v === 'number' && Number.isSafeInteger(v) && Math.abs(v) <= 8640000000000000) return new Date(v).toISOString()
  if (iso(v)) return v
  return invalid()
}
const kindForCode = (code: number): string => {
  if (code === 401 || code === 1510000001) return 'session'
  if (code === 403 || (code >= 1510000002 && code <= 1510000007) || [1510020011, 1510030011, 1510040002, 1510040011, 1510040012].includes(code)) return 'forbidden'
  if ([1510010000, 1510020000, 1510030000, 1510040000].includes(code)) return 'disabled'
  if ([1510020007, 1510030003].includes(code)) return 'quote-expired'
  if ([1510010006, 1510020005, 1510030006, 1510040008].includes(code)) return 'file-unavailable'
  if (code === 1510020002) return 'profile-missing'
  if ([1510010005, 1510020006, 1510030008, 1510040004].includes(code)) return 'conflict'
  if (code === 1510040005) return 'already-paid'
  if (code === 1510030004) return 'quote-consumed'
  if ([1510010001, 1510010002, 1510020001, 1510030001, 1510040001].includes(code)) return 'invalid-input'
  if ([1510010004, 1510020004, 1510020008, 1510030002, 1510030005, 1510030009, 1510040003, 1510040007, 1510040013].includes(code)) return 'not-found'
  return 'service'
}
// Keep 401 in the existing shared login/refresh path; preserve all other business codes.
export const readWorkflowResponse = (raw: unknown): unknown => {
  let value: unknown
  try { value = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { return invalid() }
  if (!object(value) || typeof value.code !== 'number') return invalid()
  if (value.code !== 0 && value.code !== 200 && value.code !== 401) throw new WorkflowApiError(value.code, kindForCode(value.code))
  return value
}
export const parseFile = (v: unknown): FileMetadata => {
  if (!object(v) || !validUuid(v.id) || !text(v.displayName) || v.mediaType !== 'application/pdf' || !positive(v.sizeBytes) ||
      !(v.pageCount === null || positive(v.pageCount)) || !text(v.status) || !(v.failureCode === null || text(v.failureCode)) ||
      typeof v.contentAvailable !== 'boolean') return invalid()
  if (v.status === 'READY' && !positive(v.pageCount)) return invalid()
  return { ...v, createdAt: fileTime(v.createdAt), expiresAt: fileTime(v.expiresAt) } as unknown as FileMetadata
}
export const parseQuote = (v: unknown): DemoQuote => {
  if (!object(v) || !validUuid(v.id) || !['VALID', 'EXPIRED'].includes(String(v.status)) || !object(v.snapshot)) return invalid()
  const s = v.snapshot
  if (s.schemaVersion !== 1 || s.mode !== 'DEMO' || s.currency !== 'CNY' || s.aggregationPolicy !== 'PER_FILE_ALL_COPIES' ||
      !validUuid(s.fileId) || !validUuid(s.priceProfileId) || typeof s.fileSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(s.fileSha256) ||
      !text(s.priceProfileKey) || !text(s.priceProfileVersion) || !positive(s.pagesPerCopy) || !['SIMPLEX', 'DUPLEX'].includes(String(s.side)) ||
      !['copies', 'sheetsPerCopy', 'totalSheets'].every(k => count(s[k])) ||
      !['simplexPricePerSheet', 'duplexPricePerSheet', 'pricePerSheet', 'totalAmount'].every(k => money(s[k])) ||
      typeof s.tierApplied !== 'boolean' || !(s.appliedTierThreshold === null || uint(s.appliedTierThreshold)) ||
      !timestamp(s.createdAtMs) || !timestamp(s.expiresAtMs)) return invalid()
  if (s.tier !== null && (!object(s.tier) || !uint(s.tier.strictlyMoreThanSheets) || !money(s.tier.simplexPricePerSheet) || !money(s.tier.duplexPricePerSheet))) return invalid()
  return v as unknown as DemoQuote
}
export const parseCreatedOrder = (v: unknown): WorkflowOrder => {
  if (!object(v) || !validUuid(v.id) || !text(v.orderNo) || v.source !== 'ADMIN_DEMO' || v.simulation !== true || v.currency !== 'CNY' ||
      !money(v.totalAmount) || !text(v.paymentState) || !text(v.printState)) return invalid()
  return v as unknown as WorkflowOrder
}
export const parseReceipt = (v: unknown, orderId: string): PaymentReceipt => {
  if (!object(v) || !validUuid(v.id) || v.orderId !== orderId || v.provider !== 'SIMULATED' || v.state !== 'SUCCEEDED' ||
      v.simulation !== true || v.currency !== 'CNY' || !money(v.amount) || v.actorType !== 'STAFF' || !positive(v.actorId) ||
      !timestamp(v.createdAtMs) || !Array.isArray(v.jobIds) || v.jobIds.length !== 1 || !v.jobIds.every(validUuid)) return invalid()
  return v as unknown as PaymentReceipt
}
const headers = (key: string) => {
  if (!/^[\x21-\x7e]{1,128}$/.test(key)) throw new WorkflowApiError(1510030001, 'invalid-input')
  return { 'Idempotency-Key': key }
}
const requireId = (id: string) => { if (!validUuid(id)) throw new WorkflowApiError(1510030001, 'invalid-input') }
export const uploadPdf = async (file: File, key: string): Promise<FileMetadata> => {
  const body = new FormData(); body.append('file', file)
  return parseFile(await request.post<unknown>({ url: '/print/files', data: body, headersType: 'multipart/form-data', headers: headers(key), transformResponse: [readWorkflowResponse] }))
}
export const getFileMetadata = async (id: string): Promise<FileMetadata> => {
  requireId(id)
  const result = parseFile(await request.get<unknown>({ url: `/print/files/${id}`, transformResponse: [readWorkflowResponse] }))
  if (result.id !== id) return invalid()
  return result
}
export const createQuote = async (input: QuoteInput, key: string): Promise<DemoQuote> => {
  requireId(input.fileId); requireId(input.priceProfileId)
  if (!positive(input.copies) || !['SIMPLEX', 'DUPLEX'].includes(input.side)) throw new WorkflowApiError(1510020001, 'invalid-input')
  const data = { fileId: input.fileId, priceProfileId: input.priceProfileId, copies: input.copies, side: input.side }
  const result = parseQuote(await request.post<unknown>({ url: '/print/quotes', data, headers: headers(key), transformResponse: [readWorkflowResponse] }))
  if (result.snapshot.fileId !== data.fileId || result.snapshot.priceProfileId !== data.priceProfileId || result.snapshot.copies !== String(data.copies) || result.snapshot.side !== data.side) return invalid()
  return result
}
export const createOrder = async (quoteId: string, key: string): Promise<WorkflowOrder> => {
  requireId(quoteId)
  return parseCreatedOrder(await request.post<unknown>({ url: '/print/orders', data: { quoteId }, headers: headers(key), transformResponse: [readWorkflowResponse] }))
}
export const simulatePayment = async (orderId: string, key: string): Promise<PaymentReceipt> => {
  requireId(orderId)
  return parseReceipt(await request.post<unknown>({ url: `/print/orders/${orderId}/simulate-payment`, data: {}, headers: headers(key), transformResponse: [readWorkflowResponse] }), orderId)
}
export const getPayment = async (orderId: string): Promise<PaymentReceipt> => {
  requireId(orderId)
  return parseReceipt(await request.get<unknown>({ url: `/print/orders/${orderId}/payment`, transformResponse: [readWorkflowResponse] }), orderId)
}
const messages: Record<string, [string, string]> = {
  session: ['登录状态已失效', '请按登录提示重新登录，服务端将重新检查当前身份。'],
  forbidden: ['没有操作权限', '当前账号、门店或演示环境不允许本次操作，请联系管理员。'],
  disabled: ['演示功能尚未启用', '服务端开关关闭；当前操作没有获得成功结果。'],
  'quote-expired': ['报价已过期', '请显式重新获取服务端报价后再创建订单。'],
  'file-unavailable': ['文件尚未就绪或已过期', '请刷新文件状态；不能用旧页数或旧报价继续。'],
  'profile-missing': ['测试价格版本不存在', '请管理员检查部署的测试价格 UUID 与当前门店记录。'],
  conflict: ['操作记录冲突', '当前操作键对应其他输入，请停止并联系管理员核查。'],
  'already-paid': ['订单已有模拟收款', '请读取收款记录和订单状态确认，不要再次创建收款操作。'],
  'quote-consumed': ['报价已被订单使用', '请到演示订单列表确认已有订单，不要重复下单。'],
  'invalid-input': ['输入不符合合同', '请检查 PDF、份数和测试配置；服务端未返回成功结果。'],
  'not-found': ['记录不存在或不可访问', '记录可能不属于当前账号，或已失效；请核查当前登录账号。'],
  network: ['连接服务失败', '请检查网络。写操作结果可能尚未确认，请保留本页并使用原操作键确认。'],
  service: ['服务暂不可用', '服务端未返回可确认结果，请保留本页核查本次操作。'],
  'invalid-response': ['服务响应无法识别', '没有将此响应作为成功结果，请保留本页核查本次操作。']
}
export const classifyWorkflowError = (error: unknown): WorkflowFailure => {
  let kind = 'service'
  if (error instanceof WorkflowApiError) kind = error.kind
  else if (isAxiosError(error)) {
    const status = error.response?.status
    kind = status === 401 ? 'session' : status === 403 ? 'forbidden' : status === 404 ? 'not-found' : status === 503 ? 'service' : error.response ? 'service' : 'network'
  } else if (typeof error === 'string') kind = 'session'
  const [title, message] = messages[kind] || messages.service
  return { kind, title, message, uncertain: ['network', 'service', 'invalid-response', 'session'].includes(kind) }
}
