<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onPullDownRefresh, onReachBottom, onShow, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { listDemoOrders } from '@/api/demoOrders'
import type { DemoOrder } from '@/api/demoOrders'

const orders = ref<DemoOrder[]>([])
const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadingMore = ref(false)
const pageNo = ref(0)
const hasMore = ref(false)
const total = ref(0)
const activeContext = ref('')
let generation = 0
let alive = false

const empty = computed(() => state.value === 'ready' && orders.value.length === 0)
const footerText = computed(() => loadingMore.value ? '正在加载…'
  : orders.value.length > 0 && hasMore.value ? '继续上拉加载'
  : orders.value.length > 0 ? '已经没有更多信息了' : '')

onShow(() => { alive = true; void refresh() })
onHide(() => { invalidateHidden() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })
onReachBottom(() => { void loadNext() })
onUnload(() => { invalidateHidden() })

function sessionContext(session: DemoSession) {
  return `${session.sessionId}:${session.principalId}`
}
function current(request: number, expected: string, actual: DemoSession | null) {
  return alive && generation === request && actual !== null && sessionContext(actual) === expected
}
function clearScopedData() {
  orders.value = []
  activeContext.value = ''
  pageNo.value = 0
  total.value = 0
  hasMore.value = false
  loadingMore.value = false
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
  state.value = 'loading'
  void refresh()
}
function goBack() { uni.navigateBack() }
function openOrder(id: string) { uni.navigateTo({ url: `/pages/order-detail/index?id=${encodeURIComponent(id)}` }) }
function formatTime(value: number) {
  const date = new Date(value)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
function paymentText(order: DemoOrder) { return order.paymentState === 'SUCCEEDED' ? '模拟支付成功' : '待模拟支付' }
function printText(order: DemoOrder) {
  const labels: Record<DemoOrder['printState'], string> = {
    NOT_CREATED: '尚未创建模拟任务', QUEUED: '等待模拟处理', CLAIMED: '模拟终端已领取',
    ACCEPTED: '模拟终端已受理', EXECUTING: '模拟执行中', SUCCEEDED: '模拟打印成功',
    FAILED: '模拟打印失败', RESULT_UNKNOWN: '结果待人工核实'
  }
  return labels[order.printState]
}
function details(order: DemoOrder) {
  const snapshot = order.item.quoteSnapshot
  return `${snapshot.pagesPerCopy}页 · ${snapshot.copies}份 · ${snapshot.side === 'DUPLEX' ? '双面' : '单面'} · ${snapshot.totalSheets}张纸`
}
function mergeUnique(rows: DemoOrder[], replace: boolean) {
  const merged = replace ? [] as DemoOrder[] : [...orders.value]
  const seen = new Set(merged.map(item => item.id))
  for (const row of rows) if (!seen.has(row.id)) { seen.add(row.id); merged.push(row) }
  orders.value = merged
}
async function refresh() {
  const request = ++generation
  state.value = 'loading'
  loadingMore.value = false
  const session = await ensureDemoSession()
  if (!session || !alive || generation !== request) {
    if (alive && generation === request) { clearScopedData(); state.value = 'error' }
    return
  }
  const context = sessionContext(session)
  const result = await listDemoOrders(session, 1, 20)
  const confirmed = await ensureDemoSession()
  if (!current(request, context, confirmed)) { sessionChanged(request); return }
  if (result.kind !== 'ok') { state.value = 'error'; return }
  activeContext.value = context
  mergeUnique(result.page.list, true)
  pageNo.value = 1
  total.value = result.page.total
  hasMore.value = result.page.hasMore
  state.value = 'ready'
}
async function loadNext() {
  if (state.value !== 'ready' || loadingMore.value || !hasMore.value) return
  const request = generation
  loadingMore.value = true
  const session = await ensureDemoSession()
  if (!alive || generation !== request) return
  if (!session || sessionContext(session) !== activeContext.value) { sessionChanged(request); return }
  const context = activeContext.value
  const nextPage = pageNo.value + 1
  const result = await listDemoOrders(session, nextPage, 20)
  const confirmed = await ensureDemoSession()
  if (!current(request, context, confirmed)) { sessionChanged(request); return }
  if (result.kind !== 'ok') {
    loadingMore.value = false
    uni.showToast({ title: '下一页暂时无法加载', icon: 'none' })
    return
  }
  mergeUnique(result.page.list, false)
  pageNo.value = nextPage
  total.value = result.page.total
  hasMore.value = result.page.hasMore
  loadingMore.value = false
}
</script>

<template>
  <view class="orders-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>我的订单</text><text class="spacer" /></view></view>
    <view v-if="state === 'loading'" class="message">正在加载订单…</view>
    <view v-else-if="state === 'error'" class="error-state"><text>订单暂时无法加载</text><button @click="refresh">重新加载</button></view>
    <view v-else-if="empty" class="empty-state"><view class="box-art"><view class="box-lid left"/><view class="box-lid right"/><view class="box-body"/></view><text>暂无订单哦~</text><text class="end-tip">已经没有更多信息了</text></view>
    <view v-else class="order-list">
      <view v-for="order in orders" :key="order.id" class="order-card" @click="openOrder(order.id)">
        <view class="card-head"><text>文档打印</text><text class="status">{{ printText(order) }}</text></view>
        <text class="order-no">{{ order.orderNo }}</text>
        <text class="meta">{{ details(order) }}</text>
        <view class="card-foot"><view><text class="time">{{ formatTime(order.createdAtMs) }}</text><text class="payment">{{ paymentText(order) }}</text></view><text class="amount">¥{{ order.totalAmount }} ›</text></view>
      </view>
      <view class="footer">{{ footerText }}</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.orders-page{min-height:100vh;background:#f5f5f5}.header{color:#fff;background:#ffa13e}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 24rpx;font-size:32rpx}.back,.spacer{width:58rpx}.back{font-size:58rpx;line-height:58rpx}.message,.error-state{display:flex;align-items:center;justify-content:center;min-height:620rpx;color:#777;font-size:26rpx}.error-state{flex-direction:column;gap:26rpx}.error-state button{width:240rpx;color:#fff;background:#ffa13e;border-radius:42rpx;font-size:25rpx}.empty-state{display:flex;align-items:center;min-height:720rpx;padding-top:90rpx;flex-direction:column;color:#555;font-size:27rpx}.box-art{position:relative;width:210rpx;height:180rpx;margin-bottom:40rpx}.box-body{position:absolute;left:36rpx;top:64rpx;width:138rpx;height:104rpx;background:linear-gradient(145deg,#eee,#fff);transform:skewY(-4deg)}.box-lid{position:absolute;top:38rpx;width:104rpx;height:58rpx;background:#e4e4e4}.box-lid.left{left:8rpx;transform:skewY(20deg)}.box-lid.right{right:8rpx;transform:skewY(-20deg)}.end-tip{order:-1;margin-bottom:28rpx;padding:20rpx 42rpx;color:#fff;background:#555;border-radius:12rpx;font-size:24rpx}.order-list{padding:22rpx}.order-card{margin-bottom:18rpx;padding:24rpx;background:#fff;border-radius:14rpx;box-shadow:0 4rpx 18rpx rgba(0,0,0,.04)}.card-head,.card-foot,.card-foot view{display:flex;align-items:center;justify-content:space-between}.card-head{font-size:28rpx;font-weight:700}.status{color:#ee8528;font-size:23rpx;font-weight:500}.order-no,.meta{display:block;margin-top:14rpx;color:#888;font-size:22rpx}.order-no{word-break:break-all}.card-foot{margin-top:22rpx;padding-top:20rpx;border-top:1rpx solid #eee}.card-foot view{align-items:flex-start;flex-direction:column;gap:8rpx}.time{color:#999;font-size:20rpx}.payment{color:#56806a;font-size:21rpx}.amount{color:#222;font-size:28rpx;font-weight:700}.footer{padding:20rpx 0 34rpx;text-align:center;color:#999;font-size:22rpx}
</style>
