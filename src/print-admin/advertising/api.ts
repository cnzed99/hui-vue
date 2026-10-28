import request from '@/config/axios'
import { isAxiosError } from 'axios'
import { findPosition, slotsFor } from './positions'
import type { AdPosition } from './positions'
export interface AdAsset { slot: string; url: string }
export interface AdDraft { position: AdPosition; version: string; configured: boolean; customEnabled: false; successMessageHtml: string; assets: AdAsset[]; deliveryStatus: 'NOT_CONNECTED' }
export interface AdCommand { expectedVersion: string; customEnabled: false; successMessageHtml: string; assets: AdAsset[] }
export class AdApiError extends Error {
  constructor(readonly kind: 'invalid' | 'conflict' | 'forbidden' | 'pending' | 'asset' | 'service') { super(kind) }
}
const decode = (raw: unknown): unknown => {
  let data: { code?: number }
  try { data = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { throw new AdApiError('service') }
  if (!data || typeof data.code !== 'number') throw new AdApiError('service')
  if (data.code !== 0 && data.code !== 200 && data.code !== 401) throw new AdApiError(
    data.code === 1510100001 ? 'invalid' : data.code === 1510100002 ? 'conflict' : data.code === 1510100005 ? 'pending' : data.code === 1510100006 ? 'asset' :
    data.code === 403 || (data.code >= 1510000001 && data.code <= 1510000007) ? 'forbidden' : 'service')
  return data
}
const parse = (raw: unknown, position: AdPosition): AdDraft => {
  const d = raw as AdDraft
  if (!d || d.position !== position || !findPosition(position) || typeof d.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(d.version) ||
      BigInt(d.version) > 9223372036854775807n || typeof d.configured !== 'boolean' || d.customEnabled !== false ||
      typeof d.successMessageHtml !== 'string' || d.deliveryStatus !== 'NOT_CONNECTED' || !Array.isArray(d.assets) || d.assets.length > findPosition(position)!.max ||
      d.assets.some((a) => !a || !slotsFor(position).includes(a.slot) || typeof a.url !== 'string' || !a.url.startsWith('https://')) ||
      new Set(d.assets.map((a) => a.slot)).size !== d.assets.length || (d.configured ? d.version === '0' : d.version !== '0' || d.assets.length !== 0 || d.successMessageHtml !== '')) throw new AdApiError('service')
  return d
}
export const getAdDraft = async (position: AdPosition) => parse(await request.get<unknown>({ url: `/print/advertising/${position}`, transformResponse: [decode] }), position)
export const saveAdDraft = async (position: AdPosition, data: AdCommand) => parse(await request.put<unknown>({ url: `/print/advertising/${position}`, data, transformResponse: [decode] }), position)
export const adFailure = (error: unknown) => error instanceof AdApiError ? error.kind : isAxiosError(error) && [401, 403].includes(error.response?.status || 0) ? 'forbidden' : 'service'
