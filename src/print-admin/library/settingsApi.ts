import request from '@/config/axios'
import { isAxiosError } from 'axios'
import { parseLibrarySettings } from './settingsModel'
import type { LibrarySettingsCommand } from './settingsModel'
export class LibrarySettingsApiError extends Error {
  constructor(readonly kind: 'conflict' | 'forbidden' | 'invalid' | 'service') { super(kind) }
}
const readResponse = (raw: unknown): unknown => {
  let result: { code?: number }
  try { result = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { throw new LibrarySettingsApiError('service') }
  if (!result || typeof result.code !== 'number') throw new LibrarySettingsApiError('service')
  if (result.code !== 0 && result.code !== 200 && result.code !== 401) {
    throw new LibrarySettingsApiError(result.code === 1510060002 ? 'conflict' :
      result.code === 403 || (result.code >= 1510000001 && result.code <= 1510000007) ? 'forbidden' :
      result.code === 1510060001 ? 'invalid' : 'service')
  }
  return result
}
export const getLibrarySettings = async () => parseLibrarySettings(await request.get<unknown>({ url: '/print/library/settings', transformResponse: [readResponse] }))
export const putLibrarySettings = async (data: LibrarySettingsCommand) => parseLibrarySettings(await request.put<unknown>({ url: '/print/library/settings', data, transformResponse: [readResponse] }))
export const librarySettingsFailure = (error: unknown) => {
  if (error instanceof LibrarySettingsApiError) return error.kind
  if (isAxiosError(error) && error.response?.status === 409) return 'conflict'
  if (isAxiosError(error) && (error.response?.status === 403 || error.response?.status === 401)) return 'forbidden'
  return 'service'
}
