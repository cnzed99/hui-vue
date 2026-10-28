import request from '@/config/axios'
import { isAxiosError } from 'axios'
import { parseStoreProfile } from './profileModel'
import type { StoreProfileData } from './profileModel'

export class StoreProfileApiError extends Error {
  constructor(readonly kind: 'conflict' | 'forbidden' | 'invalid' | 'service') { super(kind) }
}
const readResponse = (raw: unknown): unknown => {
  let result: { code?: number }
  try { result = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { throw new StoreProfileApiError('service') }
  if (!result || typeof result.code !== 'number') throw new StoreProfileApiError('service')
  // Preserve refresh/relogin handling for 401 in the project's normal authenticated client.
  if (result.code !== 0 && result.code !== 200 && result.code !== 401) {
    throw new StoreProfileApiError(result.code === 1510050002 ? 'conflict' :
      result.code === 403 || (result.code >= 1510000001 && result.code <= 1510000007) ? 'forbidden' :
      result.code === 1510050001 || result.code === 1510050005 ? 'invalid' : 'service')
  }
  return result
}
export const getStoreProfile = async () => parseStoreProfile(await request.get<unknown>({
  url: '/print/store/current', transformResponse: [readResponse]
}))
export const putStoreProfile = async (data: StoreProfileData & { expectedVersion: string }) =>
  parseStoreProfile(await request.put<unknown>({ url: '/print/store/current', data, transformResponse: [readResponse] }))
export const profileFailureKind = (error: unknown) => {
  if (error instanceof StoreProfileApiError) return error.kind
  if (isAxiosError(error) && error.response?.status === 409) return 'conflict'
  if (isAxiosError(error) && (error.response?.status === 403 || error.response?.status === 401)) return 'forbidden'
  return 'service'
}
