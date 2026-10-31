import { computed } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { hasPermission } from '@/directives/permission/hasPermi'
import { usePrintContextStore } from '../contextStore'
import { orderFailure } from './api'

export const DEMO_ORDERS = '/print/demo-orders'
export const parsePage = (value: unknown, fallback: number, max: number): number | null => {
  if (value === undefined) return fallback
  if (typeof value !== 'string' || !/^[1-9]\d{0,6}$/.test(value)) return null
  const n = Number(value)
  return n <= max ? n : null
}
const statusLabels: Record<string, string> = {
  OPEN: '进行中', NONE: '无', NOT_APPLICABLE: '不适用', SIMPLEX: '单面', DUPLEX: '双面'
}
export const statusLabel = (value: string): string => statusLabels[value] || `未知状态（${value}）`
// Keep payment, printing and event dictionaries separate: SUCCEEDED is not a universal result.
const paymentLabels: Record<string, string> = { UNPAID: '未支付', SUCCEEDED: '模拟收款成功' }
const printLabels: Record<string, string> = {
  NOT_CREATED: '未创建打印任务', QUEUED: '等待打印（排队中）', CLAIMED: '模拟终端已领取',
  ACCEPTED: '模拟终端已受理', EXECUTING: '模拟执行中', SUCCEEDED: '模拟打印成功',
  FAILED: '模拟打印失败', RESULT_UNKNOWN: '结果待人工核实'
}
const eventLabels: Record<string, string> = {
  ORDER_CREATED: '订单已创建', PAYMENT_SIMULATED: '模拟收款已记录',
  PRINT_TASK_CREATED: '模拟打印任务已创建', PRINT_TASK_CLAIMED: '模拟终端已领取',
  PRINT_TASK_REQUEUED: '过期领取已重新排队', PRINT_TASK_ACCEPTED: '模拟终端已受理',
  PRINT_TASK_EXECUTING: '开始模拟执行', PRINT_TASK_SUCCEEDED: '模拟成功回执已记录',
  PRINT_TASK_FAILED: '模拟失败回执已记录', PRINT_TASK_RESULT_UNKNOWN: '结果待人工核实'
}
export const paymentStatusLabel = (value: string): string => paymentLabels[value] || `未知支付状态（${value}）`
export const printStatusLabel = (value: string): string => printLabels[value] || `未知打印状态（${value}）`
export const eventTypeLabel = (value: string): string => eventLabels[value] || `未知事件（${value}）`
export const displayTime = (value: number): string => new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
}).format(value)
export const useOrderAccess = () => {
  const user = useUserStore()
  const context = usePrintContextStore()
  const permitted = computed(() => hasPermission(['print:order:query']))
  const failure = computed(() => !permitted.value ? orderFailure('forbidden') : context.failure)
  const ready = computed(() => permitted.value && context.status === 'ready' &&
    context.context?.userId === user.getUser.id)
  // Invalidate outstanding responses whenever identity/scope/permission changes.
  const key = computed(() => [ready.value, user.getUser.id, context.context?.tenantId,
    context.context?.storeId, context.context?.userId, context.status].join(':'))
  const refreshContext = () => context.load(user.getUser.id)
  return { ready, failure, key, refreshContext }
}
