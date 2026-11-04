import request from '@/config/axios'
import { isAxiosError } from 'axios'

export interface PhotoPrice {
  profileId: string
  profileKey: 'MINIAPP_DEMO_PHOTO_6IN'
  profileVersion: string
  revision: string
  currency: 'CNY'
  pricePerSheet: string
  quoteEnabled: boolean
}
export interface PhotoPriceUpdate {
  expectedRevision: string
  pricePerSheet: string
}
export type PhotoPriceFailureKind =
  | 'conflict'
  | 'unavailable'
  | 'forbidden'
  | 'session'
  | 'invalid-input'
  | 'invalid-response'
  | 'network'
  | 'service'
export interface PhotoPriceFailure {
  kind: PhotoPriceFailureKind
  message: string
}
const messages: Record<PhotoPriceFailureKind, string> = {
  conflict: '价格版本已变化。请刷新当前价格，核对后重新提交。',
  unavailable: '当前门店的照片价格配置尚不可用，请稍后刷新。',
  forbidden: '当前账号没有照片价格操作权限，请联系门店管理员。',
  session: '登录状态已失效，请重新登录。',
  'invalid-input': '价格须为 0.01 至 999.00 元，最多两位小数。',
  'invalid-response': '服务返回的照片价格无法识别，请刷新并联系管理员检查。',
  network: '连接照片价格服务失败。',
  service: '照片价格服务暂不可用。'
}
class PhotoPriceError extends Error {
  constructor(readonly kind: PhotoPriceFailureKind) {
    super(messages[kind])
  }
}
export const normalizePhotoPrice = (value: string): string | null => {
  if (!/^(?:0|[1-9]\d{0,2})(?:\.\d{1,2})?$/.test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  return cents >= 1 && cents <= 99900 ? `${whole}.${fraction.padEnd(2, '0')}` : null
}
const revision = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const object = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)
const invalid = (): never => {
  throw new PhotoPriceError('invalid-response')
}
const businessKind = (code: number): PhotoPriceFailureKind => {
  if (code === 409 || code === 43101) return 'conflict'
  if (code === 503 || code === 43102) return 'unavailable'
  if (code === 400 || code === 43100) return 'invalid-input'
  if (code === 401 || code === 1510000001) return 'session'
  if (code === 403 || (code >= 1510000002 && code <= 1510000007)) return 'forbidden'
  return 'service'
}
const readResponse = (raw: unknown): unknown => {
  let result: unknown
  try {
    result = typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch {
    return invalid()
  }
  if (!object(result) || typeof result.code !== 'number') return invalid()
  // Shared interceptor retains the normal authentication refresh/relogin behavior.
  if (![0, 200, 401].includes(result.code)) throw new PhotoPriceError(businessKind(result.code))
  return result
}
const parse = (value: unknown): PhotoPrice => {
  if (
    !object(value) ||
    typeof value.profileId !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.profileId) ||
    value.profileKey !== 'MINIAPP_DEMO_PHOTO_6IN' ||
    !revision(value.revision) ||
    value.profileVersion !== value.revision ||
    value.currency !== 'CNY' ||
    typeof value.pricePerSheet !== 'string' ||
    normalizePhotoPrice(value.pricePerSheet) !== value.pricePerSheet ||
    typeof value.quoteEnabled !== 'boolean'
  )
    return invalid()
  return value as unknown as PhotoPrice
}
const url = '/print/miniapp-demo/photo-price'
export const getPhotoPrice = async (): Promise<PhotoPrice> =>
  parse(await request.get<unknown>({ url, transformResponse: [readResponse] }))
export const updatePhotoPrice = async (input: PhotoPriceUpdate): Promise<PhotoPrice> => {
  if (
    !revision(input.expectedRevision) ||
    normalizePhotoPrice(input.pricePerSheet) !== input.pricePerSheet
  ) {
    throw new PhotoPriceError('invalid-input')
  }
  // Only the strict CAS contract is sent. Scope and quote gate are server owned.
  return parse(
    await request.put<unknown>({
      url,
      data: {
        expectedRevision: input.expectedRevision,
        pricePerSheet: input.pricePerSheet
      },
      transformResponse: [readResponse]
    })
  )
}
export const classifyPhotoPriceError = (error: unknown): PhotoPriceFailure => {
  let kind: PhotoPriceFailureKind = 'service'
  if (error instanceof PhotoPriceError) kind = error.kind
  else if (isAxiosError(error))
    kind = error.response ? businessKind(error.response.status) : 'network'
  else if (typeof error === 'string') kind = 'session'
  return { kind, message: messages[kind] }
}
