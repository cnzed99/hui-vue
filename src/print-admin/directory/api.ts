import request from '@/config/axios'
import { isAxiosError } from 'axios'
export interface DirectoryPage<T> { list: T[]; total: number; page: number; pageSize: number; categories: string[]; dataBasis: string }
export interface Product { id: string; code: string; name: string; categoryPath: string; unitPrice: string | null; currency: string | null; priceDescription: string | null }
export interface LibraryEntry { id: string; libraryStoreName: string; fileName: string; categoryPath: string; visibility: string; auditStatus: string; price: string | null; currency: string | null; pages: number | null; printCount: string | null; viewCount: string | null; favoriteCount: string | null; downloadCount: string | null; uploadedAt: string }
export interface Terminal { id: string; name: string; hostName: string; operatingSystem: string | null; clientVersion: string | null; ipAddress: string | null; mobileSelfService: boolean | null; enabled: boolean | null; updatedAt: string; onlineStatus: 'UNKNOWN' }
class DirectoryError extends Error { constructor(readonly kind: 'invalid' | 'forbidden' | 'unavailable' | 'heartbeat') { super(kind) } }
const decode = (raw: unknown): unknown => {
  let v: { code?: number }
  try { v = typeof raw === 'string' ? JSON.parse(raw) : raw as { code?: number } } catch { throw new DirectoryError('unavailable') }
  if (!v || typeof v.code !== 'number') throw new DirectoryError('unavailable')
  if (![0, 200, 401].includes(v.code)) throw new DirectoryError(v.code === 1510090001 ? 'invalid' : v.code === 1510090003 ? 'heartbeat' : v.code === 403 || (v.code >= 1510000001 && v.code <= 1510000007) ? 'forbidden' : 'unavailable')
  return v
}
export async function getDirectory<T>(endpoint: string, params: Record<string, string | number>): Promise<DirectoryPage<T>> {
  const v = await request.get<DirectoryPage<T>>({ url: `/print/directory/${endpoint}`, params, transformResponse: [decode] })
  if (!v || !Array.isArray(v.list) || !Number.isSafeInteger(v.total) || v.total < 0 || !Array.isArray(v.categories)) throw new DirectoryError('unavailable')
  return v
}
export const failureText = (error: unknown) => {
  const kind = error instanceof DirectoryError ? error.kind : isAxiosError(error) && [401, 403].includes(error.response?.status ?? 0) ? 'forbidden' : 'unavailable'
  return kind === 'forbidden' ? '当前账号没有查看权限，或门店身份已失效。' : kind === 'invalid' ? '查询条件无效，请检查筛选和分页。' : kind === 'heartbeat' ? '心跳尚未接入，无法筛选在线或离线终端；请选择全部终端。' : '目录数据源尚未启用或暂时不可用，请联系管理员。'
}
export const display = (value: unknown) => value === null || value === undefined || value === '' ? '未提供' : String(value)
export const priceText = (value: string | null, currency: string | null) => value === null ? '未提供' : `${value} ${currency || '币种未提供'}`
export const productCategories = ['文档打印', '文档打印/黑白打印', '文档打印/彩色打印', '文档打印/文档打印', '照片冲洗', '照片冲洗/黑白打印', '照片冲洗/彩色打印', '证件照冲印', '扫描复印', '扫描复印/黑白复印', '扫描复印/彩色复印', '其它打印']
export function categoryOptions(paths: string[]) {
  const values = new Set<string>()
  for (const path of paths) { const parts = path.split('/'); parts.forEach((_, i) => values.add(parts.slice(0, i + 1).join('/'))) }
  return [...values].sort().map(value => ({ value, label: `${'　'.repeat(value.split('/').length - 1)}${value.split('/').at(-1)}` }))
}
