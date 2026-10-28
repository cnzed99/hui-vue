import request from '@/config/axios'
import { isAxiosError } from 'axios'
export interface Printer { id: string; name: string; terminalId: string; terminalName: string; address: string | null; defaultPrinter: boolean | null; mobileSelfService: boolean | null; updatedAt: string; reportStatus: 'NOT_REPORTED' }
export interface TerminalOption { id: string; name: string; hostName: string; mobileSelfService: boolean | null }
export interface PrinterPage { list: Printer[]; total: number; page: number; pageSize: number; terminals: TerminalOption[]; statusBasis: 'DEVICE_REPORT_NOT_CONNECTED' }
class PrinterError extends Error { constructor(readonly kind: 'invalid' | 'forbidden' | 'unavailable') { super(kind) } }
const decode = (raw: unknown): unknown => {
  let result: { code?: number }
  try { result = typeof raw === 'string' ? JSON.parse(raw) : raw as { code?: number } } catch { throw new PrinterError('unavailable') }
  if (!result || typeof result.code !== 'number') throw new PrinterError('unavailable')
  if (![0, 200, 401].includes(result.code)) throw new PrinterError(result.code === 1510100001 ? 'invalid' : result.code === 403 || (result.code >= 1510000001 && result.code <= 1510000007) ? 'forbidden' : 'unavailable')
  return result
}
export async function getPrinters(params: { terminalId: string; page: number; pageSize: number }): Promise<PrinterPage> {
  const value = await request.get<PrinterPage>({ url: '/print/directory/printers', params, transformResponse: [decode] })
  if (!value || !Array.isArray(value.list) || !Number.isSafeInteger(value.total) || value.total < 0 || !Array.isArray(value.terminals) || value.statusBasis !== 'DEVICE_REPORT_NOT_CONNECTED') throw new PrinterError('unavailable')
  return value
}
export const printerFailure = (error: unknown) => {
  const kind = error instanceof PrinterError ? error.kind : isAxiosError(error) && [401, 403].includes(error.response?.status ?? 0) ? 'forbidden' : 'unavailable'
  return kind === 'invalid' ? '打印机筛选或分页无效，请重新选择。' : kind === 'forbidden' ? '当前账号没有查看打印机的权限，或门店身份已失效。' : '打印机目录数据源尚未启用或暂时不可用，请联系管理员。'
}
