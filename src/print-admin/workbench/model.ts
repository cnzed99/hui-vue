import type { EChartsOption } from 'echarts'
import type { TerminalReport } from '../statistics/api'
export const BASIS = 'CURRENT_ACTOR_ORDER_SNAPSHOTS' as const
export const metricCards = [
  { title: '今日收入', rows: ['在线支付', '线下支付（管理员/员工）'].map(label => ({ label, value: null, status: 'NOT_CONNECTED' })) },
  { title: '今日打印订单', rows: ['电脑自助', '微信自助', 'QQ自助'].map(label => ({ label, value: null, status: 'NOT_CONNECTED' })) },
  { title: '今日待打印订单', rows: ['微信自助', 'QQ自助', '网页自助'].map(label => ({ label, value: null, status: 'NOT_CONNECTED' })) },
  { title: '账户信息', rows: ['可结算余额', '结算中金额', '已结算总额', '冻结待退款金额'].map(label => ({ label, value: null, status: 'NOT_CONNECTED' })) }
] as const
export const storeIncome = { value: null, status: 'NOT_CONNECTED' } as const
export type Days = 7 | 30 | 90
function calendarDate(value: unknown): Date {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('invalid_report_date')
  const date = new Date(`${value}T00:00:00Z`)
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) throw new Error('invalid_report_date')
  return date
}
/** Date arithmetic uses only an API-returned calendar boundary, never the browser's today. */
export function rangeFromApiEnd(endDate: string, days: Days) {
  if (![7, 30, 90].includes(days)) throw new Error('invalid_days')
  const start = calendarDate(endDate); start.setUTCDate(start.getUTCDate() - days + 1)
  return { startDate: start.toISOString().slice(0, 10), endDate }
}
const decimal = /^(?:0|[1-9][0-9]*)(?:[.][0-9]+)?$/
export function validateReport(value: TerminalReport): TerminalReport {
  if (!value || value.basis !== BASIS || typeof value.timezone !== 'string' || !value.timezone || !Array.isArray(value.list)) throw new Error('invalid_report_contract')
  // Validate the supplied timezone, without deriving dates from the browser clock.
  new Intl.DateTimeFormat('en', { timeZone: value.timezone })
  const start = calendarDate(value.startDate), end = calendarDate(value.endDate)
  if (end < start || (end.getTime() - start.getTime()) / 86400000 > 365) throw new Error('invalid_report_range')
  const keys = new Set<string>()
  for (const row of value.list) {
    calendarDate(row.day)
    if (row.day < value.startDate || row.day > value.endDate || typeof row.scene !== 'string' || typeof row.currency !== 'string' || typeof row.simulation !== 'boolean' || !/^(0|[1-9][0-9]*)$/.test(row.orders) || !/^(0|[1-9][0-9]*)$/.test(row.invalidAmountCount) || (row.amountSnapshot !== null && (typeof row.amountSnapshot !== 'string' || !decimal.test(row.amountSnapshot)))) throw new Error('invalid_report_row')
    const key = JSON.stringify([row.day, row.scene, row.currency, row.simulation])
    if (keys.has(key)) throw new Error('duplicate_report_group')
    keys.add(key)
  }
  return value
}
export const groupLabel = (row: TerminalReport['list'][number]) => `source=${row.scene} · ${row.currency} · ${row.simulation ? '模拟' : '非模拟'}`
export const metricText = (value: unknown) => value === null ? '未接入' : String(value)
/** Missing days and unsafe integer counts remain null, never fabricated zero points. */
export function orderChart(report: TerminalReport): EChartsOption {
  validateReport(report)
  const dates: string[] = []; const cursor = calendarDate(report.startDate)
  while (cursor.toISOString().slice(0, 10) <= report.endDate) { dates.push(cursor.toISOString().slice(0, 10)); cursor.setUTCDate(cursor.getUTCDate() + 1) }
  const groups = new Map<string, { label: string; counts: Map<string, number | null> }>()
  for (const row of report.list) {
    const key = JSON.stringify([row.scene, row.currency, row.simulation])
    if (!groups.has(key)) groups.set(key, { label: groupLabel(row), counts: new Map() })
    const count = Number(row.orders)
    groups.get(key)!.counts.set(row.day, Number.isSafeInteger(count) ? count : null)
  }
  return {
    color: ['#25a7e0', '#67c23a', '#e6a23c', '#8b82d9'],
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    legend: { type: 'scroll', bottom: 0, data: [...groups.values()].map(group => group.label) },
    grid: { left: 55, right: 24, top: 30, bottom: 80 },
    xAxis: { type: 'category', data: dates, boundaryGap: false },
    yAxis: { type: 'value', name: '订单数快照', minInterval: 1 },
    series: [...groups.values()].map(group => ({ name: group.label, type: 'line' as const, smooth: false, connectNulls: false, data: dates.map(day => group.counts.get(day) ?? null) }))
  }
}
