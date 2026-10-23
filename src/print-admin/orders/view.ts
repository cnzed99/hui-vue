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
  OPEN: '进行中', UNPAID: '未支付', NONE: '无', NOT_APPLICABLE: '不适用',
  NOT_CREATED: '未创建打印任务', ORDER_CREATED: '订单已创建', SIMPLEX: '单面', DUPLEX: '双面'
}
export const statusLabel = (value: string): string => statusLabels[value] || `未知状态（${value}）`
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
