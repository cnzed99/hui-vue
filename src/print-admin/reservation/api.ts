import request from '@/config/axios'
import { isAxiosError } from 'axios'
import { parseReservationSettings } from './model'
import type { ReservationSettingsCommand } from './model'
export class ReservationSettingsApiError extends Error {
  constructor(readonly kind: 'conflict' | 'forbidden' | 'invalid' | 'disabled' | 'service') { super(kind) }
}
const readResponse = (raw: unknown): unknown => {
  let result: { code?: number }
  try { result = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { throw new ReservationSettingsApiError('service') }
  if (!result || !Number.isInteger(result.code)) throw new ReservationSettingsApiError('service')
  if (result.code !== 0) {
    throw new ReservationSettingsApiError(result.code === 1510100002 ? 'conflict' :
      result.code === 401 || result.code === 403 || (result.code! >= 1510000001 && result.code! <= 1510000007) ? 'forbidden' :
      result.code === 1510100001 ? 'invalid' : result.code === 1510100005 ? 'disabled' : 'service')
  }
  return result
}
const path = '/print/store/reservation-settings'
export const getReservationSettings = async () => parseReservationSettings(await request.get<unknown>({ url: path, transformResponse: [readResponse] }))
export const putReservationSettings = async (data: ReservationSettingsCommand) => parseReservationSettings(await request.put<unknown>({ url: path, data, transformResponse: [readResponse] }))
export const reservationSettingsFailure = (error: unknown) => {
  if (error instanceof ReservationSettingsApiError) return error.kind
  if (isAxiosError(error) && error.response?.status === 409) return 'conflict'
  if (isAxiosError(error) && (error.response?.status === 401 || error.response?.status === 403)) return 'forbidden'
  return 'service'
}
