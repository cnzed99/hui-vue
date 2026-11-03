<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ensureDemoSession, getActiveDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import type { DemoOrder } from '@/api/demoOrders'
import { clearPendingCancellation, getDemoCancellation, loadPendingCancellation, postDemoCancellation, savePendingCancellation } from '@/api/demoCancellations'
import type { CancellationRead, PendingCancellation } from '@/api/demoCancellations'

const props = withDefaults(defineProps<{ orderId: string; active?: boolean; archiveOwner?: Pick<DemoSession, 'sessionId' | 'principalId'> }>(), { active: true })
const emit = defineEmits<{ updated: [order: DemoOrder] }>()
const value = ref<CancellationRead | null>(null)
const state = ref<'loading' | 'ready' | 'unknown' | 'sending'>('loading')
const pending = ref<PendingCancellation | null>(null)
const message = ref('')
let generation = 0
let mounted = true
let sessionContext = ''

const actionLabel = computed(() => ({ CANCEL_UNPAID: '取消订单', CANCEL_AND_REFUND: '取消并模拟退款', REQUEST_REVIEW: '申请人工核实', NONE: '' })[value.value?.capability.action || 'NONE'])
const resultText = computed(() => {
  switch (value.value?.cancellation?.originalResult.outcome) {
    case 'CANCELLED_UNPAID': return '订单已取消，未发生模拟付款。'
    case 'CANCELLED_REFUNDED': return '订单已取消，全额模拟退款已记录；不涉及真实资金。'
    case 'MANUAL_REVIEW_REQUIRED': return '申请已登记，原任务尚未被停止，当前未退款。'
    default: return ''
  }
})
const context = (session: DemoSession, orderId: string) => `${session.sessionId}:${session.principalId}:${orderId}`
function archiveOwnerMatches(session: DemoSession | null) {
  return !props.archiveOwner || (session?.sessionId === props.archiveOwner.sessionId
    && session.principalId === props.archiveOwner.principalId)
}
function valid(request: number, expected: string, session: DemoSession | null, id: string) {
  return mounted && props.active && request === generation && props.orderId === id && session !== null
    && archiveOwnerMatches(session) && archiveOwnerMatches(getActiveDemoSession()) && context(session, id) === expected
}
function invalidate() { generation += 1; value.value = null; pending.value = null; message.value = ''; state.value = 'loading'; sessionContext = '' }
onBeforeUnmount(() => { mounted = false; invalidate() })
watch(() => [props.orderId, props.active] as const, () => { invalidate(); if (props.active && props.orderId) void refresh() }, { immediate: true })

async function refresh() {
  if (!props.active || !props.orderId || state.value === 'sending') return
  const id = props.orderId
  const request = ++generation
  value.value = null; state.value = 'loading'; message.value = ''
  const session = await ensureDemoSession()
  if (!session || !archiveOwnerMatches(session) || !archiveOwnerMatches(getActiveDemoSession())
    || !mounted || !props.active || request !== generation || id !== props.orderId) {
    if (mounted && props.active && request === generation) state.value = 'unknown'
    return
  }
  const expected = context(session, id)
  sessionContext = expected
  const archiveSessionId = props.archiveOwner?.sessionId
  pending.value = loadPendingCancellation(session.principalId, id, archiveSessionId)
  const loaded = await getDemoCancellation(session, id)
  const confirmed = await ensureDemoSession()
  if (!valid(request, expected, confirmed, id)) {
    if (request === generation) { invalidate(); state.value = 'unknown'; message.value = '演示会话已变化，请重新查询当前主体订单。' }
    return
  }
  if (loaded.kind !== 'ok') { state.value = 'unknown'; message.value = '取消结果暂不可确认，请先恢复查询。'; return }
  value.value = loaded.value
  if (loaded.value.cancellation) {
    clearPendingCancellation(session.principalId, id, archiveSessionId); pending.value = null
  }
  state.value = 'ready'
  emit('updated', loaded.value.currentOrder)
}

async function submit(saved: PendingCancellation) {
  if (!props.active || state.value === 'sending') return
  const id = props.orderId
  const request = ++generation
  state.value = 'sending'; message.value = ''
  const session = await ensureDemoSession()
  if (!session || !valid(request, sessionContext, session, id) || saved.principalId !== session.principalId
    || saved.archiveSessionId !== props.archiveOwner?.sessionId) {
    if (request === generation) { invalidate(); state.value = 'unknown' }
    return
  }
  const expected = context(session, id)
  // Persist before the write; timeout and page changes must keep the exact request.
  savePendingCancellation(saved); pending.value = saved
  const result = await postDemoCancellation(session, saved)
  const confirmed = await ensureDemoSession()
  if (!valid(request, expected, confirmed, id)) {
    if (request === generation) { invalidate(); state.value = 'unknown'; message.value = '演示会话已变化，原请求仍按原主体保存。' }
    return
  }
  if (result.kind === 'unknown') {
    state.value = 'unknown'; message.value = '请求结果待恢复。请先查询，不要重复创建请求。'; return
  }
  if (result.kind === 'rejected' && result.reason !== 'ALREADY_RECORDED') {
    if (result.reason === 'STALE_VERSION' || result.reason === 'DISABLED' || result.reason === 'INVALID_INPUT') {
      clearPendingCancellation(session.principalId, id, saved.archiveSessionId); pending.value = null
    }
    state.value = 'ready'
    const rejectedMessage = result.reason === 'STALE_VERSION' ? '订单状态已经变化，请重新确认新的操作。'
      : result.reason === 'IDEMPOTENCY_CONFLICT' ? '请求与已保存的操作冲突，请先恢复查询。' : '当前无法提交此操作，请刷新后查看。'
    await refresh()
    if (props.active && props.orderId === id && sessionContext === expected) message.value = rejectedMessage
    return
  }
  // Recover from a new consistent snapshot after both success and an existing-record response.
  state.value = 'ready'
  await refresh()
}

function confirmAction() {
  if (!value.value || state.value !== 'ready' || pending.value || value.value.capability.action === 'NONE') return
  const snapshot = value.value
  const expected = sessionContext
  const id = props.orderId
  const content = snapshot.capability.action === 'CANCEL_UNPAID' ? '确认取消此未付款订单？'
    : snapshot.capability.action === 'CANCEL_AND_REFUND' ? `确认取消并全额模拟退款 ¥${snapshot.currentOrder.totalAmount}？仅记录模拟退款，不涉及真实资金。`
    : '仅登记人工核实申请，原任务尚未被停止，当前不会退款，也不会自动重打。是否继续？'
  uni.showModal({ title: actionLabel.value, content, confirmText: '确认', success: result => {
    if (!result.confirm || !props.active || state.value !== 'ready' || props.orderId !== id || sessionContext !== expected || value.value !== snapshot) return
    const principalId = expected.split(':')[1]
    if (!principalId) return
    void submit({ schemaVersion: 1, principalId, orderId: id, expectedVersion: snapshot.capability.expectedVersion,
      archiveSessionId: props.archiveOwner?.sessionId,
      idempotencyKey: `miniapp-cancel-v1-${Date.now()}-${Math.random().toString(36).slice(2, 14)}` })
  } })
}
function retryPending() {
  // This button is exposed only after a successful GET explicitly reports null.
  if (state.value !== 'ready' || !value.value || value.value.cancellation !== null || !pending.value) return
  void submit(pending.value)
}
</script>

<template>
  <view class="cancel-panel">
    <text class="heading">取消与模拟退款</text>
    <text v-if="state === 'loading'">正在查询取消权限与结果…</text>
    <text v-else-if="state === 'sending'">正在提交，请等待结果确认…</text>
    <template v-else-if="value?.cancellation">
      <text class="result">{{ resultText }}</text>
      <template v-if="value.cancellation.refundReceipt">
        <text>SIMULATED 退款收据 · ¥{{ value.cancellation.refundReceipt.amount }}</text>
        <text class="break">收据 {{ value.cancellation.refundReceipt.id }}</text>
      </template>
      <text v-if="value.cancellation.reviewSummary">核实申请保存的是申请时状态；当前任务可继续按原流程处理。</text>
    </template>
    <text v-if="message">{{ message }}</text>
    <text v-if="state === 'unknown'">尚未确认结果，暂不允许新操作。</text>
    <button v-if="state === 'unknown'" class="secondary" @click="refresh">恢复取消结果</button>
    <template v-else-if="state === 'ready' && !value?.cancellation">
      <template v-if="pending">
        <text>服务端本次查询明确尚无记录。可使用原请求重试。</text>
        <button class="secondary" @click="retryPending">重试原取消请求</button>
      </template>
      <button v-else-if="actionLabel" class="action" @click="confirmAction">{{ actionLabel }}</button>
      <text v-else>当前取消写入口未开放。</text>
    </template>
  </view>
</template>

<style scoped lang="scss">
.cancel-panel{display:flex;flex-direction:column;gap:14rpx;margin:20rpx 0;padding:26rpx;color:#80613b;background:#fff7e9;border-radius:14rpx;font-size:22rpx;line-height:34rpx}.heading{color:#533b20;font-size:27rpx;font-weight:700}.result{font-weight:600}.break{word-break:break-all}.action,.secondary{width:100%;margin-top:12rpx;border-radius:40rpx;font-size:25rpx}.action{color:#fff;background:#ed9039}.secondary{color:#a76727;background:#fff;border:1rpx solid #e8c69d}
</style>
