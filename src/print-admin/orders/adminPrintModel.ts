import type { OrderDetail } from './api'
export const P22_SCOPE = '当前账号的后台演示订单' as const
export const unavailableFields = [
  { key: 'userAccount', label: '用户账号', value: null },
  { key: 'documentName', label: '文档名称', value: null },
  { key: 'printer', label: '打印机', value: null },
  { key: 'computer', label: '计算机', value: null },
  { key: 'color', label: '颜色', value: null },
  { key: 'paper', label: '纸张', value: null },
  { key: 'printedAt', label: '实际打印时间', value: null }
] as const
export const unsupportedFilters = { orderNo: null, printedAtFrom: null, printedAtUntil: null } as const
/** The list API has no itemCount: count only the items in an explicitly loaded detail. */
export const detailItemCount = (detail: OrderDetail | null): number | null => detail ? detail.items.length : null
export const unavailableText = (value: null) => value === null ? '未接入' : ''
