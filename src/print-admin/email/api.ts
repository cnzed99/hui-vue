import request from '@/config/axios'
import { isAxiosError } from 'axios'
import { parseEmailSettings } from './model'
import type { EmailSettingsCommand } from './model'
export class EmailSettingsApiError extends Error {
  constructor(readonly kind: 'conflict' | 'forbidden' | 'invalid' | 'disabled' | 'secret' | 'service') { super(kind) }
}
const readResponse = (raw: unknown): unknown => {
  let result: { code?: number }
  try { result = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { throw new EmailSettingsApiError('service') }
  if (!result || !Number.isInteger(result.code)) throw new EmailSettingsApiError('service')
  if (result.code !== 0) {
    throw new EmailSettingsApiError(result.code === 1510200002 ? 'conflict' :
      result.code === 401 || result.code === 403 ? 'forbidden' :
      result.code === 1510200001 ? 'invalid' : result.code === 1510200005 ? 'disabled' :
      result.code === 1510200006 ? 'secret' : 'service')
  }
  return result
}
const path = '/print/store/email-settings'
export const getEmailSettings = async () => parseEmailSettings(await request.get<unknown>({ url: path, transformResponse: [readResponse] }))
export const putEmailSettings = async (data: EmailSettingsCommand) => parseEmailSettings(await request.put<unknown>({ url: path, data, transformResponse: [readResponse] }))
export const emailSettingsFailure = (error: unknown) => {
  if (error instanceof EmailSettingsApiError) return error.kind
  if (isAxiosError(error) && error.response?.status === 409) return 'conflict'
  if (isAxiosError(error) && (error.response?.status === 401 || error.response?.status === 403)) return 'forbidden'
  return 'service'
}
