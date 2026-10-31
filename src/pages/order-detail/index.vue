<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { getDemoOrder } from '@/api/demoOrders'
import type { DemoOrder } from '@/api/demoOrders'
import { getDemoPayment } from '@/api/demoPayments'
import type { DemoPayment } from '@/api/demoPayments'
import { getDemoPrintResult } from '@/api/demoPrintResult'
import type { DemoPrintTask } from '@/api/demoPrintResult'

const orderId = ref('')
const order = ref<DemoOrder | null>(null)
const payment = ref<DemoPayment | null>(null)
const printTask = ref<DemoPrintTask | null>(null)
const state = ref<'loading' | 'ready' | 'error'>('loading')
const paymentQuery = ref<'idle' | 'ready' | 'unknown'>('idle')
const printQuery = ref<'idle' | 'ready' | 'unknown'>('idle')
let generation = 0
let alive = false

const snapshot = computed(() => order.value?.item.quoteSnapshot)
const sideText = computed(() => snapshot.value?.side === 'DUPLEX' ? '双面' : '单面')
const paymentText = computed(() => {
  if (!order.value) return '暂不可确认'
  if (order.value.paymentState === 'UNPAID') return '未模拟支付'
  if (payment.value) return '模拟支付成功'
  return paymentQuery.value === 'unknown' ? '已模拟支付，收据暂不可确认' : '模拟支付成功'
})
const printText = computed(() => {
  if (!order.value) return '暂不可确认'
  const labels: Record<DemoOrder['printState'], string> = {
    NOT_CREATED: '尚未创建模拟任务', QUEUED: '等待模拟处理', CLAIMED: '模拟终端已领取',
    ACCEPTED: '模拟终端已受理', EXECUTING: '模拟执行中', SUCCEEDED: '模拟打印成功',
    FAILED: '模拟打印失败', RESULT_UNKNOWN: '结果待人工核实'
  }
  if (printQuery.value === 'unknown') return `上次已知：${labels[order.value.printState]}，最新结果暂不可确认`
  return printTask.value ? labels[printTask.value.state] : labels[order.value.printState]
})

onLoad(options => {
  const id = typeof options?.id === 'string' ? options.id : ''
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) {
    state.value = 'error'
    return
  }
  orderId.value = id
})
onShow(() => {
  alive = true
  if (orderId.value) void loadDetail()
})
onHide(() => { invalidateHidden() })
onUnload(() => { invalidateHidden() })

function context(session: DemoSession) { return `${session.sessionId}:${session.principalId}:${orderId.value}` }
function current(request: number, expected: string, actual: DemoSession | null) {
  return alive && generation === request && actual !== null && context(actual) === expected
}
function clearScopedData() {
  order.value = null
  payment.value = null
  printTask.value = null
  paymentQuery.value = 'idle'
  printQuery.value = 'idle'
}
function invalidateHidden() {
  alive = false
  generation += 1
  clearScopedData()
  state.value = 'loading'
}
function sessionChanged(request: number) {
  if (!alive || generation !== request) return
  generation += 1
  clearScopedData()
  state.value = 'error'
}
function goBack() { uni.navigateBack() }
function formatTime(value: number) {
  const date = new Date(value); const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
async function loadDetail() {
  const request = ++generation
  state.value = 'loading'; order.value = null; payment.value = null; printTask.value = null
  paymentQuery.value = 'idle'; printQuery.value = 'idle'
  const session = await ensureDemoSession()
  if (!session || !alive || generation !== request) { if (alive && generation === request) state.value = 'error'; return }
  const expected = context(session)
  const loaded = await getDemoOrder(session, orderId.value)
  const confirmed = await ensureDemoSession()
  if (!current(request, expected, confirmed)) { sessionChanged(request); return }
  if (!loaded) { state.value = 'error'; return }
  order.value = loaded; state.value = 'ready'
  const [receipt, result] = await Promise.all([
    loaded.paymentState === 'SUCCEEDED' ? getDemoPayment(session, loaded.id) : Promise.resolve(null),
    loaded.paymentState === 'SUCCEEDED' ? getDemoPrintResult(session, loaded.id) : Promise.resolve({ kind: 'ok' as const, task: null })
  ])
  const finalSession = await ensureDemoSession()
  if (!current(request, expected, finalSession)) { sessionChanged(request); return }
  if (loaded.paymentState === 'SUCCEEDED') {
    payment.value = receipt
    paymentQuery.value = receipt ? 'ready' : 'unknown'
  } else paymentQuery.value = 'ready'
  if (result.kind === 'ok') { printTask.value = result.task; printQuery.value = 'ready' }
  else printQuery.value = 'unknown'
}
</script>

<template>
  <view class="detail-page">
    <view class="header"><view class="status-space"/><view class="title-row"><text class="back" @click="goBack">‹</text><text>订单详情</text><text class="spacer"/></view></view>
    <view v-if="state === 'loading'" class="message">正在加载订单…</view>
    <view v-else-if="state === 'error'" class="error-state"><text>订单详情暂时无法确认</text><button @click="loadDetail">重新加载</button></view>
    <view v-else-if="order && snapshot" class="content">
      <view class="status-card"><text class="status-title">{{ printText }}</text><text>仅展示系统模拟处理结果</text></view>
      <view class="card"><view class="heading">文档打印</view><view class="row"><text>订单号</text><text class="break">{{ order.orderNo }}</text></view><view class="row"><text>创建时间</text><text>{{ formatTime(order.createdAtMs) }}</text></view><view class="row"><text>打印设置</text><text>A4 · 黑白 · {{ sideText }}</text></view><view class="row"><text>页数与份数</text><text>{{ snapshot.pagesPerCopy }}页 · {{ snapshot.copies }}份</text></view><view class="row"><text>纸张数</text><text>{{ snapshot.totalSheets }}张</text></view><view class="row amount"><text>订单金额</text><text>¥{{ order.totalAmount }}</text></view></view>
      <view class="card"><view class="heading">处理状态</view><view class="row"><text>支付</text><text>{{ paymentText }}</text></view><view class="row"><text>打印</text><text>{{ printText }}</text></view></view>
      <view v-if="payment" class="receipt green"><text class="receipt-title">SIMULATED 支付收据</text><text>金额 ¥{{ payment.amount }}</text><text class="break">收据 {{ payment.id }}</text><text>模拟支付不涉及真实资金</text></view>
      <view v-if="printTask?.receipt" class="receipt orange"><text class="receipt-title">{{ printTask.receipt.result === 'SIMULATED_SUCCESS' ? '模拟成功回执' : '模拟失败回执' }}</text><text>完成 {{ printTask.receipt.completedCopies }}份 / {{ printTask.receipt.completedSheets }}张</text><text v-if="printTask.receipt.reason">原因：{{ printTask.receipt.reason }}</text><text>模拟回执不代表真实打印机出纸</text></view>
      <view class="boundary">当前 AppID、微信支付、退款和真实打印端尚未接入；本页仅用于开发演示。</view>
      <button class="refresh" @click="loadDetail">刷新订单状态</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.detail-page{min-height:100vh;background:#f5f5f5}.header{color:#fff;background:#ffa13e}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 24rpx;font-size:32rpx}.back,.spacer{width:58rpx}.back{font-size:58rpx;line-height:58rpx}.message,.error-state{display:flex;align-items:center;justify-content:center;min-height:620rpx;color:#777;font-size:26rpx}.error-state{flex-direction:column;gap:26rpx}.error-state button{width:240rpx;color:#fff;background:#ffa13e;border-radius:42rpx;font-size:25rpx}.content{padding:22rpx}.status-card{display:flex;padding:30rpx 26rpx;color:#fff;background:linear-gradient(135deg,#ff9634,#ffb54d);border-radius:16rpx;flex-direction:column;gap:10rpx;font-size:22rpx}.status-title{font-size:30rpx;font-weight:700}.card,.receipt,.boundary{margin-top:20rpx;padding:26rpx;background:#fff;border-radius:14rpx}.heading{margin-bottom:12rpx;color:#222;font-size:28rpx;font-weight:700}.row{display:flex;justify-content:space-between;gap:28rpx;padding:19rpx 0;border-bottom:1rpx solid #eee;color:#777;font-size:23rpx}.row text:last-child{color:#333;text-align:right}.row:last-child{border-bottom:0}.row.amount text:last-child{color:#ef8527;font-size:30rpx;font-weight:700}.break{word-break:break-all}.receipt{display:flex;flex-direction:column;gap:10rpx;font-size:22rpx;line-height:32rpx}.receipt.green{color:#287d51;background:#eaf8f0}.receipt.orange{color:#7b4c00;background:#fff4dd}.receipt-title{font-weight:800}.boundary{color:#856a47;background:#fff8ed;font-size:21rpx;line-height:34rpx}.refresh{height:84rpx;margin:26rpx 0;color:#fff;background:linear-gradient(90deg,#ff9736,#ffad45);border-radius:42rpx;font-size:27rpx;line-height:84rpx}
</style>
