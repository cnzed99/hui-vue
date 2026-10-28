import request from '@/config/axios'
import { isAxiosError } from 'axios'
export interface OrderPoint { day: string; terminal: string; scene: string; currency: string; simulation: boolean; orders: string; amountSnapshot: string | null; invalidAmountCount: string }
export interface TerminalReport { list: OrderPoint[]; scenes: string[]; startDate: string; endDate: string; timezone: string; basis: string; transactionAmountDefinition: string }
export interface PaperRow { id: string; terminal: string; printer: string; paper: string; remaining: string | null; warning: string | null; total: string; simplex: string | null; duplex: string | null; day: string | null; updatedAt: string; simulation: boolean }
export interface PaperReport { list: PaperRow[]; total: string; page: number; size: number }
export class StatisticsError extends Error { constructor(readonly kind: 'invalid' | 'forbidden' | 'service') { super(kind) } }
const response = (raw: unknown): unknown => {
  let result: { code?: number }
  try { result = typeof raw === 'string' ? JSON.parse(raw) : raw as { code?: number } } catch { throw new StatisticsError('service') }
  if (!result || typeof result.code !== 'number') throw new StatisticsError('service')
  if (![0, 200, 401].includes(result.code)) throw new StatisticsError(result.code === 1510070001 ? 'invalid' : result.code === 403 || (result.code >= 1510000001 && result.code <= 1510000007) ? 'forbidden' : 'service')
  return result
}
export const terminalReport = (params: Record<string, string>) => request.get<TerminalReport>({ url: '/print/statistics/terminal', params, transformResponse: [response] })
export const paperReport = (daily: boolean, params: Record<string, string | number>) => request.get<PaperReport>({ url: `/print/statistics/paper-${daily ? 'daily' : 'total'}`, params, transformResponse: [response] })
export const failureText = (error: unknown) => {
  const kind = error instanceof StatisticsError ? error.kind : isAxiosError(error) && [401, 403].includes(error.response?.status ?? 0) ? 'forbidden' : 'service'
  return kind === 'forbidden' ? '当前账号没有查看统计的权限，或门店身份已失效。' : kind === 'invalid' ? '筛选条件无效，请核对日期范围（最多366天）和分页。' : '统计数据加载失败，请重试或联系管理员确认数据源已启用。'
}
export const amountText = (v: string | null) => v === null ? '待核对' : v.includes('.') ? v.replace(/0+$/, '').replace(/[.]$/, '') : v
export const csvCell = (value: unknown) => {
  const raw = String(value ?? '')
  const safe = /^[\s=+\-@]/.test(raw) ? `'${raw}` : raw
  return `"${safe.replace(/"/g, '""')}"`
}
