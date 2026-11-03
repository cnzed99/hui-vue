<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onPullDownRefresh, onReachBottom, onShow, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { loadDemoMemberActivityPage } from '@/api/demoMemberActivity'
import type { DemoMemberActivity } from '@/api/demoMemberActivity'

const activities = ref<DemoMemberActivity[]>([])
const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadingMore = ref(false)
const pageNo = ref(0)
const hasMore = ref(false)
const activeContext = ref('')
let generation = 0
let alive = false

const empty = computed(() => state.value === 'ready' && activities.value.length === 0 && !hasMore.value)
const footerText = computed(() => loadingMore.value ? '正在加载…'
  : hasMore.value ? '继续加载较早记录'
  : activities.value.length > 0 ? '已显示全部模拟收支' : '')

onShow(() => { alive = true; void refresh() })
onHide(() => invalidateHidden())
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })
onReachBottom(() => { void loadNext() })
onUnload(() => invalidateHidden())

function sessionContext(session: DemoSession) { return `${session.sessionId}:${session.principalId}` }
function current(request: number, expected: string, actual: DemoSession | null) {
  return alive && generation === request && actual !== null && sessionContext(actual) === expected
}
function clearScopedData() {
  activities.value = []
  activeContext.value = ''
  pageNo.value = 0
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
function mergeUnique(rows: DemoMemberActivity[], replace: boolean) {
  const merged = replace ? [] as DemoMemberActivity[] : [...activities.value]
  const seen = new Set(merged.map(item => item.id))
  for (const row of rows) if (!seen.has(row.id)) { seen.add(row.id); merged.push(row) }
  activities.value = merged.sort((a, b) => b.createdAtMs - a.createdAtMs || a.id.localeCompare(b.id))
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
  const result = await loadDemoMemberActivityPage(session, 1)
  const confirmed = await ensureDemoSession()
  if (!current(request, context, confirmed)) { sessionChanged(request); return }
  if (result.kind !== 'ok') { clearScopedData(); state.value = 'error'; return }
  activeContext.value = context
  mergeUnique(result.page.list, true)
  pageNo.value = 1
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
  const result = await loadDemoMemberActivityPage(session, nextPage)
  const confirmed = await ensureDemoSession()
  if (!current(request, context, confirmed)) { sessionChanged(request); return }
  if (result.kind !== 'ok') {
    loadingMore.value = false
    uni.showToast({ title: '较早记录暂时无法加载', icon: 'none' })
    return
  }
  mergeUnique(result.page.list, false)
  pageNo.value = nextPage
  hasMore.value = result.page.hasMore
  loadingMore.value = false
}
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/member-card/index' }) }
function openOrder(orderId: string) { uni.navigateTo({ url: `/pages/order-detail/index?id=${encodeURIComponent(orderId)}` }) }
function formatTime(value: number) {
  const date = new Date(value)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
function title(row: DemoMemberActivity) { return row.kind === 'SIMULATED_REFUND' ? '全额模拟退款' : '模拟支付' }
function amount(row: DemoMemberActivity) { return `${row.kind === 'SIMULATED_REFUND' ? '+' : '-'}¥${row.amount}` }
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>模拟消费明细</text><text class="spacer" /></view></view>
    <view class="notice">仅显示新系统开发环境的模拟支付与模拟退款，不代表会员余额或真实资金。</view>
    <view v-if="state === 'loading'" class="message">正在读取模拟明细…</view>
    <view v-else-if="state === 'error'" class="error"><text>模拟明细暂时无法核实</text><button @click="refresh">重新加载</button></view>
    <view v-else-if="empty" class="empty"><view class="empty-icon">⌁</view><text>暂无模拟消费记录</text></view>
    <view v-else class="list">
      <view v-for="row in activities" :key="row.id" class="item" @click="openOrder(row.orderId)">
        <view class="icon" :class="{ refund: row.kind === 'SIMULATED_REFUND' }">{{ row.kind === 'SIMULATED_REFUND' ? '退' : '付' }}</view>
        <view class="copy"><text class="name">{{ title(row) }}</text><text class="order">{{ row.orderNo }}</text><text class="time">{{ formatTime(row.createdAtMs) }}</text></view>
        <text class="amount" :class="{ refund: row.kind === 'SIMULATED_REFUND' }">{{ amount(row) }}</text>
      </view>
      <button v-if="hasMore" class="more" @click="loadNext">{{ footerText }}</button>
      <view v-else class="footer">{{ footerText }}</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.notice{margin:24rpx;padding:20rpx 24rpx;color:#8b682f;background:#fff7e7;border-radius:14rpx;font-size:22rpx;line-height:34rpx}.message,.error,.empty{display:flex;align-items:center;justify-content:center;min-height:520rpx;color:#777;font-size:26rpx}.error,.empty{flex-direction:column;gap:24rpx}.error button{width:240rpx;color:#fff;background:#ff9f3d;border-radius:42rpx;font-size:25rpx}.empty-icon{display:flex;align-items:center;justify-content:center;width:170rpx;height:140rpx;color:#b8c6d1;background:#eef3f6;border-radius:50%;font-size:76rpx}.list{padding:0 22rpx 44rpx}.item{display:flex;align-items:center;margin-bottom:16rpx;padding:22rpx;background:#fff;border-radius:14rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.04)}.icon{display:flex;align-items:center;justify-content:center;width:70rpx;height:70rpx;margin-right:18rpx;color:#d98223;background:#fff1df;border-radius:50%;font-size:25rpx;font-weight:700}.icon.refund{color:#2f8567;background:#e9f7f1}.copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:7rpx}.name{font-size:27rpx;font-weight:650}.order,.time{overflow:hidden;color:#999;font-size:20rpx;text-overflow:ellipsis;white-space:nowrap}.amount{color:#333;font-size:29rpx;font-weight:700}.amount.refund{color:#2f8567}.more{height:76rpx;color:#dd842c;background:#fff;border:1rpx solid #ffc484;border-radius:38rpx;font-size:24rpx;line-height:76rpx}.footer{padding:22rpx;color:#999;text-align:center;font-size:22rpx}
</style>
