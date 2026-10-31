<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession } from '@/api/demoSession'
import { createDemoQuote } from '@/api/demoQuotes'
import type { DemoQuote } from '@/api/demoQuotes'
import { createDemoOrder } from '@/api/demoOrders'
import type { DemoOrder } from '@/api/demoOrders'
import { createDemoPayment, getDemoPayment } from '@/api/demoPayments'
import type { DemoPayment } from '@/api/demoPayments'
import { loadDraft } from '@/domain/printDraft'
import type { PrintDraft } from '@/domain/printDraft'

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
const pendingOrderQuoteId = ref('')
const pendingOrderPrincipalId = ref('')
const amountText = computed(() => order.value
  ? `¥${order.value.totalAmount}`
  : quoteState.value === 'ready' && quote.value
  ? `¥${quote.value.snapshot.totalAmount}`
  : quoteState.value === 'expired' ? '报价已过期' : '待服务端报价')
const progressText = computed(() => payment.value
  ? '模拟支付收据已生成；尚未创建打印任务，未发生真实扣款。'
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
onUnload(() => clearExpiryTimer())

function goBack() { uni.navigateBack() }
function roundStorageKey(value: PrintDraft) {
  return `miniapp-quote-round-v1:${value.fileId}:${value.paper}:${value.color}:${value.copies}:${value.side}`
}
function pendingOrderStorageKey(value: PrintDraft) { return `miniapp-order-pending-v1:${value.fileId}` }
function loadPendingOrder(value: PrintDraft) {
  const stored = uni.getStorageSync(pendingOrderStorageKey(value)) as unknown
  if (typeof stored === 'string') {
    pendingOrderQuoteId.value = stored
    pendingOrderPrincipalId.value = ''
    return
  }
  const record = stored as { quoteId?: unknown; principalId?: unknown } | null
  pendingOrderQuoteId.value = typeof record?.quoteId === 'string' ? record.quoteId : ''
  pendingOrderPrincipalId.value = typeof record?.principalId === 'string' ? record.principalId : ''
}
function savePendingOrder(quoteId: string, principalId: string) {
  if (!draft.value) return
  pendingOrderQuoteId.value = quoteId
  pendingOrderPrincipalId.value = principalId
  uni.setStorageSync(pendingOrderStorageKey(draft.value), { quoteId, principalId })
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
  if (!draft.value?.fileId || quoteState.value === 'loading') return
  if (draft.value.paper !== 'A4' || draft.value.color !== 'BLACK_WHITE') {
    quoteState.value = 'failed'
    return
  }
  clearExpiryTimer()
  quoteState.value = 'loading'
  const session = await ensureDemoSession()
  const result = session ? await createDemoQuote(session, draft.value.fileId, draft.value.paper,
    draft.value.color, draft.value.copies, draft.value.side, quoteRound.value) : { kind: 'failed' as const }
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
  if (!draft.value) return
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
  orderState.value = 'creating'
  const session = await ensureDemoSession()
  if (!session) {
    orderState.value = 'failed'
    return 'unknown'
  }
  if (pendingOrderPrincipalId.value && pendingOrderPrincipalId.value !== session.principalId) {
    orderState.value = 'failed'
    uni.showToast({ title: '原会话已变化，订单结果仍待恢复', icon: 'none' })
    return 'unknown'
  }
  if (!pendingOrderQuoteId.value) savePendingOrder(quoteId, session.principalId)
  else if (!pendingOrderPrincipalId.value) savePendingOrder(quoteId, session.principalId)
  const result = await createDemoOrder(session, quoteId)
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
    if (receipt) {
      payment.value = receipt
      paymentState.value = 'ready'
    } else paymentState.value = 'failed'
  }
  return 'ok'
}
function confirmPayment() {
  if (!order.value || paymentState.value === 'creating' || paymentState.value === 'ready') return
  if (order.value.paymentState === 'SUCCEEDED') {
    void recoverPaymentReceipt()
    return
  }
  uni.showModal({
    title: '确认模拟支付',
    content: `本操作仅生成演示收据，不调用微信支付、不扣真实资金。模拟金额 ¥${order.value.totalAmount}，是否继续？`,
    confirmText: '模拟支付',
    success: result => { if (result.confirm) void payOrder() }
  })
}
async function recoverPaymentReceipt() {
  if (!order.value) return
  paymentState.value = 'creating'
  const session = await ensureDemoSession()
  const receipt = session ? await getDemoPayment(session, order.value.id) : null
  if (!receipt) {
    paymentState.value = 'failed'
    uni.showToast({ title: '模拟支付已完成，收据暂未恢复', icon: 'none' })
    return
  }
  payment.value = receipt
  paymentState.value = 'ready'
}
async function payOrder() {
  if (!order.value) return
  paymentState.value = 'creating'
  const session = await ensureDemoSession()
  const receipt = session ? await createDemoPayment(session, order.value.id) : null
  if (!receipt) {
    paymentState.value = 'failed'
    uni.showToast({ title: '模拟支付失败，请重试', icon: 'none' })
    return
  }
  payment.value = receipt
  paymentState.value = 'ready'
}
</script>

<template>
  <view class="confirm-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>确认订单</text><text class="spacer" /></view></view>
    <view v-if="draft" class="body">
      <view class="card file-card"><view class="file-icon">PDF</view><view class="file-copy"><text>{{ draft.fileName }}</text><text>{{ draft.pages }} 页 · {{ colorText }} · {{ sideText }} · {{ draft.copies }} 份</text></view></view>
      <view class="card rows"><view><text>纸张规格</text><text>A4 · 黑白</text></view><view><text>计价范围</text><text>本文件单独计算</text></view><view><text>份数</text><text>{{ draft.copies }}</text></view><view><text>金额</text><text class="pending">{{ quoteState === 'loading' ? '服务端计算中…' : amountText }}</text></view></view>
      <view class="notice"><text>当前进度</text><text>{{ progressText }}</text></view>
      <view v-if="order" class="card result-card"><text>模拟订单</text><text>{{ order.orderNo }}</text><text>金额 ¥{{ order.totalAmount }} · {{ payment ? '模拟支付成功' : order.paymentState === 'SUCCEEDED' ? '已模拟支付，收据待恢复' : '未支付' }}</text><text>打印状态：未创建打印任务</text></view>
      <view v-if="payment" class="receipt"><text>SIMULATED</text><text>模拟支付成功，不涉及真实资金</text><text>收据 {{ payment.id }}</text></view>
      <button v-if="order && paymentState !== 'ready'" :class="paymentState === 'creating' ? 'disabled' : 'retry'" @click="confirmPayment">{{ paymentState === 'creating' ? '正在恢复支付结果…' : paymentState === 'failed' && order.paymentState === 'SUCCEEDED' ? '重试恢复模拟收据' : paymentState === 'failed' ? '重试模拟支付' : '模拟支付（不扣真实资金）' }}</button>
      <button v-else-if="order && paymentState === 'ready'" class="disabled">模拟支付成功 · 等待后台生成打印任务</button>
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
</style>
