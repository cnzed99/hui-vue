<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession, getActiveDemoSession, watchDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { createDemoQuote } from '@/api/demoQuotes'
import type { DemoQuote } from '@/api/demoQuotes'
import { createDemoOrder, getDemoOrder } from '@/api/demoOrders'
import type { DemoOrder } from '@/api/demoOrders'
import { createDemoPayment, getDemoPayment } from '@/api/demoPayments'
import type { DemoPayment } from '@/api/demoPayments'
import { getDemoPrintResult } from '@/api/demoPrintResult'
import type { DemoPrintTask } from '@/api/demoPrintResult'
import { archiveDraftMatchesSession, loadDraft } from '@/domain/printDraft'
import type { PrintDraft } from '@/domain/printDraft'
import DemoCancellationPanel from '@/components/DemoCancellationPanel.vue'

const draft = ref<PrintDraft | null>(null)
const sideText = computed(() => draft.value?.side === 'DUPLEX' ? '双面' : '单面')
const colorText = computed(() => draft.value?.color === 'COLOR' ? '彩色' : '黑白')
const uploaded = computed(() => draft.value?.source === 'SERVER_UPLOAD')
const quote = ref<DemoQuote | null>(null)
const quoteState = ref<'idle' | 'loading' | 'ready' | 'expired' | 'failed'>('idle')
const quoteRound = ref(1)
const order = ref<DemoOrder | null>(null)
const orderState = ref<'idle' | 'creating' | 'ready' | 'failed'>('idle')
const payment = ref<DemoPayment | null>(null)
const paymentState = ref<'idle' | 'creating' | 'ready' | 'failed'>('idle')
const printTask = ref<DemoPrintTask | null>(null)
const printResultState = ref<'idle' | 'loading' | 'ready' | 'failed'>('idle')
const printResultIssue = ref<'none' | 'unavailable' | 'principal-changed'>('none')
const pendingOrderQuoteId = ref('')
const pendingOrderPrincipalId = ref('')
const visible = ref(true)
const orderRefreshing = ref(false)
let orderGeneration = 0
const amountText = computed(() => order.value
  ? `¥${order.value.totalAmount}`
  : quoteState.value === 'ready' && quote.value
  ? `¥${quote.value.snapshot.totalAmount}`
  : quoteState.value === 'expired' ? '报价已过期' : '待服务端报价')
const printStatusText = computed(() => {
  const labels: Record<string, string> = {
    QUEUED: '等待后台模拟处理', CLAIMED: '模拟终端已领取', ACCEPTED: '模拟终端已受理',
    EXECUTING: '模拟执行中', SUCCEEDED: '模拟打印成功', FAILED: '模拟打印失败',
    RESULT_UNKNOWN: '结果待人工核实', CANCELLED: '模拟任务已取消'
  }
  if (printResultIssue.value === 'principal-changed') return '演示会话已变化，旧订单进度不可查询'
  if (printResultIssue.value === 'unavailable') return printTask.value
    ? `上次已知：${labels[printTask.value.state] || printTask.value.state}（最新进度暂不可确认）`
    : '打印进度暂不可确认'
  if (printTask.value) return labels[printTask.value.state] || printTask.value.state
  if (printResultState.value === 'ready') return '尚未创建打印任务'
  if (printResultState.value === 'loading') return '正在查询打印进度'
  return '打印进度尚未查询'
})
const progressText = computed(() => order.value?.orderState === 'CANCELLED'
  ? order.value.refundState === 'SUCCEEDED' ? '订单已取消，全额模拟退款已记录，不涉及真实资金。' : '未付款订单已取消。'
  : order.value?.cancellationOutcome === 'MANUAL_REVIEW_REQUIRED'
    ? '申请已登记，原任务尚未被停止，当前未退款。'
  : printResultIssue.value === 'principal-changed'
  ? '演示会话已变化，不能用新主体推断旧订单是否已创建任务。'
  : printResultIssue.value === 'unavailable'
    ? '最新打印进度暂不可确认；保留上次已知结果，不推断为未创建任务。'
  : printTask.value
  ? printTask.value.state === 'SUCCEEDED'
    ? '后台已写入模拟成功回执；这不代表真实打印机出纸。'
    : printTask.value.state === 'FAILED'
      ? '后台已写入模拟失败回执；不会自动重打或退款。'
      : printTask.value.state === 'RESULT_UNKNOWN'
        ? '模拟处理结果待人工核实；系统不会自动重排或生成第二任务。'
        : `后台模拟处理进度：${printStatusText.value}。`
  : payment.value && printResultState.value === 'ready'
  ? '模拟支付收据已生成；服务端确认尚未创建打印任务，未发生真实扣款。'
  : payment.value
    ? '模拟支付收据已生成；打印进度尚待查询，未发生真实扣款。'
  : order.value
    ? '模拟订单已创建并冻结服务端金额；等待你主动确认模拟支付。'
    : quoteState.value === 'ready'
      ? `服务端报价已生成（${quote.value?.snapshot.totalSheets} 张纸），订单仍未创建、未支付。`
      : quoteState.value === 'expired'
        ? '原报价已过期，不再显示为有效金额；可生成新一轮报价。'
        : uploaded.value
          ? '文件已解析，正在等待服务端报价；订单仍未创建、未支付。'
          : '固定演示文件未上传，不能生成真实报价。')
let expiryTimer: ReturnType<typeof setTimeout> | null = null
let printTimer: ReturnType<typeof setInterval> | null = null
let unloaded = false
let pageShown = false
let archiveInvalidated = false

function invalidateArchive() {
  if (!draft.value?.archiveOwner) return
  // The server may have committed an in-flight write. Keep the original owner's recovery record.
  pendingOrderQuoteId.value = ''
  pendingOrderPrincipalId.value = ''
  draft.value = null
  quote.value = null
  order.value = null
  payment.value = null
  printTask.value = null
  visible.value = false
  orderGeneration += 1
  clearExpiryTimer()
  clearPrintTimer()
  loadDraft()
  archiveInvalidated = true
  if (pageShown && !unloaded) uni.redirectTo({ url: '/pages/archive-print/index' })
}

function archiveCurrent(expected = draft.value, session = getActiveDemoSession()) {
  if (!expected?.archiveOwner) return true
  if (unloaded || draft.value !== expected) return false
  if (archiveDraftMatchesSession(expected, getActiveDemoSession())) {
    return archiveDraftMatchesSession(expected, session)
  }
  invalidateArchive()
  return false
}
watchDemoSession(() => { archiveCurrent() })

onLoad(async () => {
  draft.value = loadDraft()
  if (!draft.value) {
    uni.redirectTo({ url: '/pages/document-upload/index' })
    return
  }
  if (draft.value.paper !== 'A4' || draft.value.color !== 'BLACK_WHITE') {
    uni.showToast({ title: '当前仅支持 A4 黑白报价，请重新确认设置', icon: 'none' })
    setTimeout(() => uni.redirectTo({ url: '/pages/document-settings/index' }), 350)
    return
  }
  if (draft.value.source === 'SERVER_UPLOAD') {
    quoteRound.value = loadQuoteRound(draft.value)
    loadPendingOrder(draft.value)
    if (pendingOrderQuoteId.value) {
      const outcome = await recoverOrder(pendingOrderQuoteId.value)
      if (outcome !== 'rejected') return
      clearPendingOrder()
    }
    await requestQuote()
  }
})
onShow(async () => {
  pageShown = true
  if (archiveInvalidated) {
    uni.redirectTo({ url: '/pages/archive-print/index' })
    return
  }
  if (!archiveCurrent()) return
  const current = draft.value
  const request = ++orderGeneration
  visible.value = false
  if (order.value) {
    const session = await ensureDemoSession()
    if (!archiveCurrent(current, session)) return
    if (request !== orderGeneration) return
    if (!session || pendingOrderPrincipalId.value !== session.principalId) {
      order.value = null; payment.value = null; printTask.value = null
      orderState.value = 'failed'; printResultIssue.value = 'principal-changed'
    }
  }
  visible.value = true
  if (order.value) void refreshCurrentOrder()
})
onHide(() => { pageShown = false; visible.value = false; orderGeneration += 1; orderRefreshing.value = false; clearPrintTimer() })
onUnload(() => { unloaded = true; visible.value = false; orderGeneration += 1; clearExpiryTimer(); clearPrintTimer() })

function goBack() { uni.navigateBack() }
function updateCancellationOrder(updated: DemoOrder) {
  if (!archiveCurrent() || !visible.value || updated.id !== order.value?.id) return
  order.value = updated
  if (updated.orderState === 'CANCELLED') { orderGeneration += 1; orderRefreshing.value = false; clearPrintTimer(); printTask.value = null; printResultState.value = 'idle' }
}
async function refreshCurrentOrder(): Promise<boolean> {
  if (!archiveCurrent() || !order.value || !visible.value) return false
  const current = draft.value
  const id = order.value.id
  const request = ++orderGeneration
  orderRefreshing.value = true
  const session = await ensureDemoSession()
  if (!archiveCurrent(current, session)) {
    if (request === orderGeneration) orderRefreshing.value = false
    return false
  }
  if (!session || pendingOrderPrincipalId.value !== session.principalId) {
    if (request === orderGeneration) { orderRefreshing.value = false; printResultIssue.value = 'principal-changed' }
    return false
  }
  const loaded = await getDemoOrder(session, id)
  const confirmed = await ensureDemoSession()
  if (!archiveCurrent(current, confirmed)) {
    if (request === orderGeneration) orderRefreshing.value = false
    return false
  }
  if (!visible.value || request !== orderGeneration || id !== order.value?.id) return false
  orderRefreshing.value = false
  if (!confirmed || confirmed.sessionId !== session.sessionId || confirmed.principalId !== session.principalId) {
    printResultIssue.value = 'principal-changed'; return false
  }
  if (!loaded) { printResultIssue.value = 'unavailable'; return false }
  updateCancellationOrder(loaded)
  return true
}
function roundStorageKey(value: PrintDraft) {
  const owner = value.archiveOwner
  const prefix = owner
    ? `miniapp-archive-quote-round-v1:${owner.sessionId}:${owner.principalId}`
    : 'miniapp-quote-round-v1'
  return `${prefix}:${value.fileId}:${value.paper}:${value.color}:${value.copies}:${value.side}`
}
function pendingOrderStorageKey(value: PrintDraft) {
  const owner = value.archiveOwner
  return owner
    ? `miniapp-archive-order-pending-v1:${owner.sessionId}:${owner.principalId}:${value.fileId}`
    : `miniapp-order-pending-v1:${value.fileId}`
}
function loadPendingOrder(value: PrintDraft) {
  const stored = uni.getStorageSync(pendingOrderStorageKey(value)) as unknown
  const record = stored as { quoteId?: unknown; principalId?: unknown; sessionId?: unknown } | null
  if (value.archiveOwner && (record?.sessionId !== value.archiveOwner.sessionId
    || record.principalId !== value.archiveOwner.principalId)) return
  if (typeof stored === 'string') {
    pendingOrderQuoteId.value = stored
    pendingOrderPrincipalId.value = ''
    return
  }
  pendingOrderQuoteId.value = typeof record?.quoteId === 'string' ? record.quoteId : ''
  pendingOrderPrincipalId.value = typeof record?.principalId === 'string' ? record.principalId : ''
}
function savePendingOrder(quoteId: string, session: DemoSession) {
  if (!draft.value) return
  pendingOrderQuoteId.value = quoteId
  pendingOrderPrincipalId.value = session.principalId
  uni.setStorageSync(pendingOrderStorageKey(draft.value), { quoteId, principalId: session.principalId, sessionId: session.sessionId })
}
function clearPendingOrder() {
  if (draft.value) uni.removeStorageSync(pendingOrderStorageKey(draft.value))
  pendingOrderQuoteId.value = ''
  pendingOrderPrincipalId.value = ''
}
function loadQuoteRound(value: PrintDraft) {
  const stored = Number(uni.getStorageSync(roundStorageKey(value)))
  return Number.isSafeInteger(stored) && stored >= 1 && stored <= 9999 ? stored : 1
}
function saveQuoteRound(value: PrintDraft) { uni.setStorageSync(roundStorageKey(value), quoteRound.value) }
function clearExpiryTimer() {
  if (expiryTimer !== null) clearTimeout(expiryTimer)
  expiryTimer = null
}
function clearPrintTimer() {
  if (printTimer !== null) clearInterval(printTimer)
  printTimer = null
}
function schedulePrintRefresh() {
  clearPrintTimer()
  if (visible.value && order.value?.orderState === 'OPEN' && (!printTask.value || !['SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN', 'CANCELLED'].includes(printTask.value.state))) {
    printTimer = setInterval(() => { void refreshPrintResult(false) }, 5000)
  }
}
async function refreshPrintResult(showFailure = true) {
  if (!archiveCurrent() || !order.value || !visible.value || order.value.orderState !== 'OPEN' || printResultState.value === 'loading') return
  const current = draft.value
  const id = order.value.id
  const request = orderGeneration
  printResultState.value = 'loading'
  printResultIssue.value = 'none'
  const session = await ensureDemoSession()
  if (!archiveCurrent(current, session)) {
    if (request === orderGeneration) printResultState.value = 'failed'
    return
  }
  if (session && pendingOrderPrincipalId.value && pendingOrderPrincipalId.value !== session.principalId) {
    clearPrintTimer()
    printTask.value = null
    printResultState.value = 'failed'
    printResultIssue.value = 'principal-changed'
    if (showFailure) uni.showToast({ title: '演示会话已变化，旧订单不可继续查询', icon: 'none' })
    return
  }
  const result = session ? await getDemoPrintResult(session, order.value.id) : { kind: 'unknown' as const }
  const confirmed = await ensureDemoSession()
  if (!archiveCurrent(current, confirmed)) {
    if (request === orderGeneration) printResultState.value = 'failed'
    return
  }
  if (!visible.value || request !== orderGeneration || id !== order.value?.id || order.value.orderState !== 'OPEN') return
  if (!session || !confirmed || session.sessionId !== confirmed.sessionId || session.principalId !== confirmed.principalId) {
    clearPrintTimer(); printTask.value = null; printResultState.value = 'failed'; printResultIssue.value = 'principal-changed'; return
  }
  if (result.kind !== 'ok') {
    printResultState.value = 'failed'
    printResultIssue.value = 'unavailable'
    if (showFailure) uni.showToast({ title: '打印进度暂未恢复，请稍后重试', icon: 'none' })
    return
  }
  printTask.value = result.task
  printResultState.value = 'ready'
  printResultIssue.value = 'none'
  schedulePrintRefresh()
}
function scheduleExpiry(expiresAtMs: number) {
  clearExpiryTimer()
  const remaining = expiresAtMs - Date.now()
  if (remaining <= 0) {
    quoteState.value = 'expired'
    return
  }
  expiryTimer = setTimeout(() => { quoteState.value = 'expired' }, remaining + 50)
}
async function requestQuote() {
  const current = draft.value
  if (!current?.fileId || !archiveCurrent(current) || quoteState.value === 'loading') return
  if (current.paper !== 'A4' || current.color !== 'BLACK_WHITE') {
    quoteState.value = 'failed'
    return
  }
  clearExpiryTimer()
  quoteState.value = 'loading'
  const session = await ensureDemoSession()
  if (!archiveCurrent(current)) return
  const result = session && archiveCurrent(current, session)
    ? await createDemoQuote(session, current.fileId, current.paper,
      current.color, current.copies, current.side, quoteRound.value)
    : { kind: 'failed' as const }
  if (!archiveCurrent(current)) return
  if (result.kind === 'ok' && result.quote.status === 'VALID' && result.quote.snapshot.expiresAtMs > Date.now()) {
    quote.value = result.quote
    quoteState.value = 'ready'
    scheduleExpiry(result.quote.snapshot.expiresAtMs)
  } else {
    quote.value = null
    quoteState.value = result.kind === 'expired' || (result.kind === 'ok' && result.quote.status === 'EXPIRED')
      ? 'expired' : 'failed'
  }
}
async function retryOrRenew() {
  if (!draft.value || !archiveCurrent()) return
  if (pendingOrderQuoteId.value) {
    const outcome = await recoverOrder(pendingOrderQuoteId.value)
    if (outcome === 'rejected') {
      clearPendingOrder()
      await requestQuote()
    }
    return
  }
  if (quoteState.value === 'expired') {
    if (quoteRound.value >= 9999) {
      uni.showToast({ title: '报价轮次已达演示上限，请重新上传文件', icon: 'none' })
      return
    }
    quoteRound.value += 1
    saveQuoteRound(draft.value)
  }
  await requestQuote()
}
async function createOrder() {
  if (!archiveCurrent()) return
  if (quoteState.value !== 'ready') {
    uni.showToast({ title: '请先完成真实文件上传和服务端报价', icon: 'none' })
    return
  }
  if (!quote.value || orderState.value === 'creating') return
  if (await recoverOrder(quote.value.id) === 'rejected') {
    clearPendingOrder()
    quoteState.value = 'expired'
  }
}
async function recoverOrder(quoteId: string): Promise<'ok' | 'rejected' | 'unknown'> {
  const current = draft.value
  if (!archiveCurrent(current)) return 'unknown'
  orderState.value = 'creating'
  const session = await ensureDemoSession()
  if (!session || !archiveCurrent(current, session)) {
    orderState.value = 'failed'
    return 'unknown'
  }
  if (pendingOrderPrincipalId.value && pendingOrderPrincipalId.value !== session.principalId) {
    orderState.value = 'failed'
    uni.showToast({ title: '原会话已变化，订单结果仍待恢复', icon: 'none' })
    return 'unknown'
  }
  if (!pendingOrderQuoteId.value || !pendingOrderPrincipalId.value) savePendingOrder(quoteId, session)
  const result = await createDemoOrder(session, quoteId)
  if (!archiveCurrent(current)) return 'unknown'
  if (result.kind !== 'ok') {
    orderState.value = 'failed'
    uni.showToast({ title: result.kind === 'rejected' ? '原报价未落单，可重新报价' : '订单结果待恢复，请重试', icon: 'none' })
    return result.kind
  }
  clearExpiryTimer()
  order.value = result.order
  orderState.value = 'ready'
  if (result.order.paymentState === 'SUCCEEDED' && session) {
    paymentState.value = 'creating'
    const receipt = await getDemoPayment(session, result.order.id)
    if (!archiveCurrent(current)) return 'unknown'
    if (receipt) {
      payment.value = receipt
      paymentState.value = 'ready'
    } else paymentState.value = 'failed'
    await refreshPrintResult(false)
  }
  return 'ok'
}
async function confirmPayment() {
  const current = draft.value
  if (!archiveCurrent(current) || !order.value || orderRefreshing.value || paymentState.value === 'creating' || paymentState.value === 'ready') return
  if (!await refreshCurrentOrder() || !archiveCurrent(current) || order.value?.orderState !== 'OPEN') return
  if (order.value.paymentState === 'SUCCEEDED') {
    void recoverPaymentReceipt()
    return
  }
  uni.showModal({
    title: '确认模拟支付',
    content: `本操作仅生成演示收据，不调用微信支付、不扣真实资金。模拟金额 ¥${order.value.totalAmount}，是否继续？`,
    confirmText: '模拟支付',
    success: result => { if (result.confirm && archiveCurrent(current)) void payOrder() }
  })
}
async function recoverPaymentReceipt() {
  const current = draft.value
  const id = order.value?.id
  if (!id || !archiveCurrent(current)) return
  paymentState.value = 'creating'
  const session = await ensureDemoSession()
  const receipt = session && archiveCurrent(current, session) ? await getDemoPayment(session, id) : null
  if (!archiveCurrent(current)) return
  if (!receipt) {
    paymentState.value = 'failed'
    uni.showToast({ title: '模拟支付已完成，收据暂未恢复', icon: 'none' })
    return
  }
  payment.value = receipt
  paymentState.value = 'ready'
  await refreshPrintResult(false)
}
async function payOrder() {
  const current = draft.value
  if (!archiveCurrent(current) || !order.value || !visible.value || !await refreshCurrentOrder()
    || !archiveCurrent(current) || order.value?.orderState !== 'OPEN') return
  const id = order.value.id
  paymentState.value = 'creating'
  const session = await ensureDemoSession()
  const receipt = session && archiveCurrent(current, session) ? await createDemoPayment(session, id) : null
  if (!archiveCurrent(current)) return
  if (!receipt) {
    paymentState.value = 'failed'
    uni.showToast({ title: '模拟支付失败，请重试', icon: 'none' })
    return
  }
  payment.value = receipt
  paymentState.value = 'ready'
  await refreshPrintResult(false)
}
</script>

<template>
  <view class="confirm-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>确认订单</text><text class="spacer" /></view></view>
    <view v-if="draft && visible" class="body">
      <view class="card file-card"><view class="file-icon">PDF</view><view class="file-copy"><text>{{ draft.fileName }}</text><text>{{ draft.pages }} 页 · {{ colorText }} · {{ sideText }} · {{ draft.copies }} 份</text></view></view>
      <view class="card rows"><view><text>纸张规格</text><text>A4 · 黑白</text></view><view><text>计价范围</text><text>本文件单独计算</text></view><view><text>份数</text><text>{{ draft.copies }}</text></view><view><text>金额</text><text class="pending">{{ quoteState === 'loading' ? '服务端计算中…' : amountText }}</text></view></view>
      <view class="notice"><text>当前进度</text><text>{{ progressText }}</text></view>
      <view v-if="order" class="card result-card"><text>{{ order.orderState === 'CANCELLED' ? '模拟订单已取消' : '模拟订单' }}</text><text>{{ order.orderNo }}</text><text>金额 ¥{{ order.totalAmount }} · {{ order.refundState === 'SUCCEEDED' ? '已全额模拟退款' : payment ? '模拟支付成功' : order.paymentState === 'SUCCEEDED' ? '已模拟支付，收据待恢复' : '未支付' }}</text><text>打印状态：{{ order.orderState === 'CANCELLED' ? '订单已取消，不再处理' : printStatusText }}</text><text v-if="printTask">Job {{ printTask.jobId }}</text></view>
      <view v-if="payment" class="receipt"><text>SIMULATED</text><text>模拟支付成功，不涉及真实资金</text><text>收据 {{ payment.id }}</text></view>
      <view v-if="printTask?.receipt" class="print-receipt"><text>{{ printTask.receipt.result === 'SIMULATED_SUCCESS' ? '模拟成功回执' : '模拟失败回执' }}</text><text>完成 {{ printTask.receipt.completedCopies }} 份 / {{ printTask.receipt.completedSheets }} 张</text><text v-if="printTask.receipt.reason">原因：{{ printTask.receipt.reason }}</text><text>仅为系统模拟，不代表真实出纸</text></view>
      <DemoCancellationPanel v-if="order" :order-id="order.id" :active="visible" :archive-owner="draft.archiveOwner" @updated="updateCancellationOrder" />
      <button v-if="order?.orderState === 'CANCELLED'" class="disabled">订单已取消</button>
      <button v-else-if="order && paymentState !== 'ready'" :class="paymentState === 'creating' || orderRefreshing ? 'disabled' : 'retry'" @click="confirmPayment">{{ orderRefreshing ? '正在确认订单状态…' : paymentState === 'creating' ? '正在恢复支付结果…' : paymentState === 'failed' && order.paymentState === 'SUCCEEDED' ? '重试恢复模拟收据' : paymentState === 'failed' ? '重试模拟支付' : '模拟支付（不扣真实资金）' }}</button>
      <button v-else-if="order && paymentState === 'ready' && printResultIssue === 'principal-changed'" class="disabled">会话已变化 · 旧订单进度不可查询</button>
      <button v-else-if="order && paymentState === 'ready'" class="retry" @click="refreshPrintResult(true)">{{ printResultState === 'loading' ? '正在刷新模拟打印进度…' : printResultIssue === 'unavailable' ? '重试查询模拟打印进度' : '刷新模拟打印进度' }}</button>
      <button v-else-if="pendingOrderQuoteId && orderState === 'creating'" class="disabled">正在恢复原模拟订单…</button>
      <button v-else-if="pendingOrderQuoteId" class="retry" @click="retryOrRenew">恢复原模拟订单</button>
      <button v-else-if="quoteState === 'failed' || quoteState === 'expired'" class="retry" @click="retryOrRenew">{{ quoteState === 'expired' ? '重新生成报价' : '重试同一次报价' }}</button>
      <button v-else-if="orderState === 'idle' || orderState === 'failed'" :class="quoteState === 'ready' ? 'retry' : 'disabled'" @click="createOrder">{{ quoteState === 'ready' ? '创建模拟订单' : '等待服务端报价' }}</button>
      <button v-else-if="orderState === 'creating'" class="disabled">正在创建模拟订单…</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.confirm-page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.body{padding:24rpx}.card{margin-bottom:22rpx;padding:26rpx;background:#fff;border-radius:16rpx}.file-card{display:flex;align-items:center}.file-icon{display:flex;align-items:center;justify-content:center;width:72rpx;height:72rpx;color:#fff;background:#ef6e5f;border-radius:10rpx;font-size:20rpx;font-weight:700}.file-copy{display:flex;min-width:0;flex:1;flex-direction:column;margin-left:18rpx}.file-copy text:first-child{overflow:hidden;font-size:27rpx;font-weight:600;text-overflow:ellipsis;white-space:nowrap}.file-copy text:last-child{margin-top:10rpx;color:#999;font-size:22rpx}.rows view{display:flex;justify-content:space-between;padding:22rpx 0;border-bottom:1rpx solid #eee;font-size:25rpx}.rows view:last-child{border-bottom:0}.rows view text:last-child{font-weight:600}.rows .pending{color:#ee8528}.notice{display:flex;flex-direction:column;padding:22rpx;color:#8d6d43;background:#fff7e9;border-radius:12rpx;font-size:22rpx;line-height:36rpx}.notice text:first-child{font-weight:700}.disabled{height:86rpx;margin-top:28rpx;color:#888;background:#e7e9eb;border-radius:44rpx;font-size:28rpx;line-height:86rpx}
.retry{height:86rpx;margin-top:28rpx;color:#fff;background:linear-gradient(90deg,#ff9736,#ffad45);border-radius:44rpx;font-size:28rpx;line-height:86rpx}
.result-card{display:flex;flex-direction:column;margin-top:22rpx;gap:10rpx;color:#555;font-size:23rpx;line-height:34rpx}.result-card text:first-child{color:#222;font-size:27rpx;font-weight:700}.result-card text:nth-child(2){word-break:break-all}.receipt{display:flex;flex-direction:column;margin-top:20rpx;padding:22rpx;color:#287d51;background:#eaf8f0;border-radius:12rpx;font-size:22rpx;line-height:34rpx}.receipt text:first-child{font-weight:800}.receipt text:last-child{word-break:break-all}
.print-receipt{display:flex;flex-direction:column;margin-top:20rpx;padding:22rpx;color:#7b4c00;background:#fff4dd;border-radius:12rpx;font-size:22rpx;line-height:34rpx}.print-receipt text:first-child{font-weight:800}
</style>
