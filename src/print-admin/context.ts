import request from '@/config/axios'
import { isAxiosError } from 'axios'

export interface PrintContext {
  tenantId: number
  storeId: number
  userId: number
  storeName: string
  actorType: 'STAFF'
}

export type ContextFailureKind = 'session' | 'forbidden' | 'unassigned' | 'ambiguous' |
  'store-unavailable' | 'tenant' | 'network' | 'service' | 'invalid-response' | 'identity'

export interface ContextFailure {
  kind: ContextFailureKind
  title: string
  message: string
}

const failures: Record<ContextFailureKind, Omit<ContextFailure, 'kind'>> = {
  session: { title: '登录状态已失效', message: '请重新登录后再打开工作台。' },
  forbidden: { title: '没有门店访问权限', message: '当前账号无法访问此门店，请联系管理员检查账号权限。' },
  unassigned: { title: '尚未分配门店', message: '请联系管理员为当前账号分配门店后重试。' },
  ambiguous: { title: '无法确定当前门店', message: '当前账号关联了多个有效门店，请联系管理员检查门店分配。' },
  'store-unavailable': { title: '门店暂不可用', message: '当前门店不存在或已停用，请联系管理员检查门店状态。' },
  tenant: { title: '缺少商家信息', message: '请重新登录并确认所属商家。' },
  network: { title: '连接服务失败', message: '请检查网络连接，稍后点击重新加载。' },
  service: { title: '暂时无法加载门店', message: '服务暂时不可用，请稍后重试。' },
  'invalid-response': { title: '门店信息无法识别', message: '服务返回的信息不完整，请联系管理员检查。' },
  identity: { title: '登录身份不一致', message: '请退出后重新登录，避免在错误的账号下操作。' }
}

export const contextFailure = (kind: ContextFailureKind): ContextFailure => ({ kind, ...failures[kind] })

class PrintContextError extends Error {
  constructor(readonly kind: ContextFailureKind) {
    super(failures[kind].message)
    this.name = 'PrintContextError'
  }
}

const businessFailureKind = (code: number): ContextFailureKind => {
  if (code === 401 || code === 1510000001) return 'session'
  if (code === 403 || code === 1510000003 || code === 1510000006) return 'forbidden'
  if (code === 1510000002) return 'tenant'
  if (code === 1510000004) return 'unassigned'
  if (code === 1510000005) return 'ambiguous'
  if (code === 1510000007) return 'store-unavailable'
  return 'service'
}

/** Preserve this endpoint's CommonResult code before the shared interceptor discards business codes. */
const readContextResponse = (raw: unknown): unknown => {
  let result: unknown
  try {
    result = typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch {
    throw new PrintContextError('invalid-response')
  }
  if (!result || typeof result !== 'object' || !('code' in result) || typeof result.code !== 'number') {
    throw new PrintContextError('invalid-response')
  }
  // 401 must reach the normal interceptor so refresh-token/relogin behavior remains intact.
  if (result.code !== 0 && result.code !== 200 && result.code !== 401) {
    throw new PrintContextError(businessFailureKind(result.code))
  }
  return result
}

const isValidId = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0

export const getPrintContext = async (): Promise<PrintContext> => {
  const data = await request.get<unknown>({
    url: '/print/context',
    transformResponse: [readContextResponse]
  })
  if (!data || typeof data !== 'object' || !('tenantId' in data) || !isValidId(data.tenantId) ||
      !('storeId' in data) || !isValidId(data.storeId) ||
      !('userId' in data) || !isValidId(data.userId) ||
      !('storeName' in data) || typeof data.storeName !== 'string' || !data.storeName.trim() ||
      !('actorType' in data) || data.actorType !== 'STAFF') {
    throw new PrintContextError('invalid-response')
  }
  return { tenantId: data.tenantId, storeId: data.storeId, userId: data.userId,
    storeName: data.storeName, actorType: data.actorType }
}

export const classifyContextError = (error: unknown): ContextFailure => {
  if (error instanceof PrintContextError) return contextFailure(error.kind)
  if (isAxiosError(error)) {
    if (error.response?.status === 401) return contextFailure('session')
    if (error.response?.status === 403) return contextFailure('forbidden')
    return contextFailure(error.response ? 'service' : 'network')
  }
  // The shared auth interceptor rejects with a translated string when relogin is required.
  if (typeof error === 'string') return contextFailure('session')
  return contextFailure('service')
}
