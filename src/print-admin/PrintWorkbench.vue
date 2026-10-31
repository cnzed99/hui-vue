<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { isAxiosError } from 'axios'
import { Echart } from '@/components/Echart'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from './contextStore'
import { getStoreProfile, profileFailureKind } from './store/profileApi'
import { terminalReport, StatisticsError } from './statistics/api'
import type { TerminalReport } from './statistics/api'
import { getMiniappCatalogStatus } from './workbench/miniappCatalog'
import type { MiniappCatalogStatus } from './workbench/miniappCatalog'
import { metricCards, storeIncome, metricText, validateReport, rangeFromApiEnd, groupLabel, orderChart, BASIS } from './workbench/model'
import type { Days } from './workbench/model'
defineOptions({ name: 'PrintWorkbench' })
type LoadState = 'idle' | 'loading' | 'ready' | 'forbidden' | 'error'
const user = useUserStore(), context = usePrintContextStore()
const permitted = (permission: string) => user.permissions.has('*:*:*') || user.permissions.has(permission)
const canProfile = computed(() => permitted('print:store:query'))
const canStatistics = computed(() => permitted('print:statistics:query'))
const canStartDemo = computed(() => permitted('print:file:upload'))
const canQueryOrders = computed(() => permitted('print:order:query'))
const accountName = computed(() => user.getUser.nickname || `员工 ${context.context?.userId}`)
const notice = ref<string | null>(null), noticeState = ref<LoadState>('idle')
const report = shallowRef<TerminalReport | null>(null), statisticsState = ref<LoadState>('idle')
const days = ref<Days>(30)
const miniappCatalog = shallowRef<MiniappCatalogStatus | null>(null)
const miniappState = ref<LoadState>('idle')
const chart = computed(() => report.value ? orderChart(report.value) : {})
const chartRange = computed(() => report.value ? `${report.value.startDate} — ${report.value.endDate} · ${report.value.timezone}` : '')
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
let noticeGeneration = 0, statisticsGeneration = 0
const loadNotice = async () => {
  if (!canProfile.value || context.status !== 'ready') return
  const ticket = ++noticeGeneration; notice.value = null; noticeState.value = 'loading'
  try {
    const data = await getStoreProfile()
    if (ticket !== noticeGeneration) return
    notice.value = data.profile.notice; noticeState.value = 'ready'
  } catch (error) { if (ticket === noticeGeneration) noticeState.value = profileFailureKind(error) === 'forbidden' ? 'forbidden' : 'error' }
}
const loadStatistics = async (selected: Days = days.value) => {
  if (!canStatistics.value || context.status !== 'ready' || statisticsState.value === 'loading') return
  const anchor = report.value?.endDate
  const ticket = ++statisticsGeneration; report.value = null; statisticsState.value = 'loading'; days.value = selected
  try {
    // Bootstrap uses the server's default interval; subsequent ranges use its returned endDate only.
    const data = validateReport(await terminalReport(anchor ? rangeFromApiEnd(anchor, selected) : {}))
    if (ticket !== statisticsGeneration) return
    if (anchor && (data.startDate !== rangeFromApiEnd(anchor, selected).startDate || data.endDate !== anchor)) throw new Error('unexpected_date_boundary')
    report.value = data; statisticsState.value = 'ready'
    if (!anchor) days.value = 30
  } catch (error) {
    if (ticket !== statisticsGeneration) return
    const forbidden = error instanceof StatisticsError && error.kind === 'forbidden' || isAxiosError(error) && [401, 403].includes(error.response?.status ?? 0)
    statisticsState.value = forbidden ? 'forbidden' : 'error'
  }
}
watch([identity, canProfile, canStatistics], () => {
  noticeGeneration++; statisticsGeneration++; notice.value = null; report.value = null; days.value = 30
  noticeState.value = canProfile.value ? 'idle' : 'forbidden'; statisticsState.value = canStatistics.value ? 'idle' : 'forbidden'
  if (context.status === 'ready') { void loadNotice(); void loadStatistics() }
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { noticeGeneration++; statisticsGeneration++ })
const reloadContext = () => { void context.load(user.getUser.id) }
const loadMiniappStatus = async () => {
  if (miniappState.value === 'loading') return
  miniappState.value = 'loading'; miniappCatalog.value = null
  try { miniappCatalog.value = await getMiniappCatalogStatus(); miniappState.value = 'ready' }
  catch { miniappState.value = 'error' }
}
onMounted(() => { void loadMiniappStatus() })
</script>
<template>
<section class="workbench" data-testid="print-workbench">
  <header class="workbench__title-card"><h1>工作台</h1><span v-if="context.status === 'ready'" data-testid="print-store-name">{{ context.context?.storeName }}</span></header>
  <div v-if="context.status === 'idle' || context.status === 'loading'" class="workbench__panel workbench__state" role="status" data-testid="print-context-loading">正在加载门店信息…</div>
  <div v-else-if="context.failure" class="workbench__panel workbench__state" role="alert" data-testid="print-context-error" :data-error-kind="context.failure.kind"><h2>{{ context.failure.title }}</h2><p>{{ context.failure.message }}</p><button @click="reloadContext">重新加载</button></div>
  <template v-else-if="context.context">
    <article class="workbench__panel workbench__announcement" data-testid="workbench-notice"><div class="workbench__heading"><h2>公告动态</h2><button :disabled="noticeState === 'loading' || !canProfile" @click="loadNotice">刷新公告</button></div>
      <p v-if="noticeState === 'loading'" role="status">正在加载当前门店公告…</p><p v-else-if="noticeState === 'forbidden'" role="alert">当前账号没有查看门店公告的权限。</p><p v-else-if="noticeState === 'error'" role="alert">门店公告加载失败，请重试。</p>
      <template v-else-if="noticeState === 'ready'"><p v-if="notice?.trim()" class="workbench__notice-text">{{ notice }}</p><p v-else class="workbench__muted">当前门店暂无公告。</p><small>来源：当前门店资料 · 未提供发布日期</small></template>
    </article>
    <div class="workbench__metrics"><article v-for="(card,index) in metricCards" :key="card.title" class="workbench__panel workbench__metric"><h2><i :class="`workbench__dot--${index}`"></i>{{ card.title }}</h2><dl><div v-for="item in card.rows" :key="item.label" :data-status="item.status"><dt>{{ item.label }}</dt><dd>{{ metricText(item.value) }}</dd></div></dl><p class="workbench__muted">{{ index === 3 ? '结算与退款账户数据未接入。' : index === 2 ? '待打印渠道与设备状态未接入。' : '经营渠道与实收统计未接入。' }}</p></article></div>
    <div class="workbench__charts">
      <article class="workbench__panel workbench__chart"><div class="workbench__heading"><h2>订单数统计</h2><div class="workbench__range" aria-label="当前账号订单趋势日期范围"><button v-for="value in ([7,30,90] as const)" :key="value" :aria-pressed="days === value" :disabled="!report || statisticsState === 'loading' || !canStatistics" @click="loadStatistics(value)">最近{{ value }}天</button></div></div>
        <p class="workbench__scope">当前账号订单趋势 · 非门店全量经营统计</p>
        <p v-if="statisticsState === 'forbidden'" role="alert">当前账号没有查看订单统计的权限。</p><p v-else-if="statisticsState === 'loading'" role="status">正在加载当前账号订单快照…</p><div v-else-if="statisticsState === 'error'" role="alert"><p>订单统计加载失败或返回口径不匹配。</p><button @click="loadStatistics()">重新加载统计</button></div>
        <template v-else-if="report"><p class="workbench__muted">{{ chartRange }}</p><div v-if="report.list.length === 0" class="workbench__chart-empty" role="status">此 API 日期范围内，当前账号暂无订单快照。</div><Echart v-else :options="chart" height="300px" :not-merge="true" /><small>按 source 原值 / 币种 / simulation 分组；未返回的日期点留空。超出图表整数精度的计数留空，准确原值见下表。</small></template>
      </article>
      <article class="workbench__panel workbench__chart" :data-status="storeIncome.status"><div class="workbench__heading"><h2>门店收入</h2><div class="workbench__range" aria-label="门店收入日期范围尚未接入"><button v-for="value in [7,30,90]" :key="value" disabled>最近{{ value }}天</button></div></div><div class="workbench__chart-empty workbench__income"><strong>{{ metricText(storeIncome.value) }}</strong><p>门店真实收入数据源和金额口径尚未接入。</p><p>当前账号订单金额快照不作为门店实收收入。</p></div></article>
    </div>
    <article v-if="report" class="workbench__panel workbench__snapshots"><h2>当前账号订单快照明细</h2><p class="workbench__muted">basis={{ BASIS }} · {{ report.timezone }} · 金额为订单快照，实收与交易额口径待确认；source 原值不映射电脑/微信/QQ/网页等经营渠道。</p><el-table :data="report.list" border empty-text="当前 API 日期范围内暂无快照" max-height="350"><el-table-column prop="day" label="API日期" width="115" /><el-table-column label="来源 / 币种 / simulation" min-width="260"><template #default="{row}">{{ groupLabel(row) }}</template></el-table-column><el-table-column prop="orders" label="订单数快照" min-width="120" /><el-table-column label="订单金额快照（非收入）" min-width="200"><template #default="{row}">{{ row.amountSnapshot === null ? '待核对' : row.amountSnapshot }} {{ row.currency }}</template></el-table-column><el-table-column prop="invalidAmountCount" label="待核对金额数" min-width="130" /></el-table></article>
    <article class="workbench__panel workbench__miniapp" data-testid="miniapp-service-status"><div class="workbench__heading"><h2>小程序服务状态</h2><button :disabled="miniappState === 'loading'" @click="loadMiniappStatus">刷新状态</button></div>
      <p v-if="miniappState === 'loading'" role="status">正在检查小程序公开目录…</p><p v-else-if="miniappState === 'error'" role="alert">小程序目录服务不可用；前端将显示本地目录，不会伪装为已连接。</p>
      <div v-else-if="miniappCatalog" class="workbench__miniapp-grid"><div><span>目录版本</span><strong>{{ miniappCatalog.catalogVersion }}</strong></div><div><span>打印入口</span><strong>{{ miniappCatalog.methods.length }} 项</strong></div><div><span>上传能力</span><strong>{{ miniappCatalog.uploadEnabled ? '已开放' : '未开放' }}</strong></div><div><span>演示约束</span><strong>{{ Math.round(miniappCatalog.demoLimits.maxFileBytes / 1024 / 1024) }}MB / {{ miniappCatalog.demoLimits.maxPages }}页 / {{ miniappCatalog.demoLimits.retentionMinutes }}分钟</strong></div></div>
      <p class="workbench__muted">当前仅验证匿名只读目录；AppID、微信登录、支付、对象存储和真实打印端均未接入。</p>
    </article>
    <article class="workbench__panel workbench__extensions" data-testid="print-context-ready"><div class="workbench__heading"><h2>本系统演示扩展</h2><span data-testid="print-account-name">{{ accountName }} · 后台员工</span></div><p>以下为既有开发演示入口；资金与打印设备保持模拟边界，不再新增同类演示页面。</p><div v-if="canStartDemo" class="workbench__demo"><RouterLink to="/print/demo-workflow">后台演示流程</RouterLink><span>上传 PDF、服务端报价、创建订单及显式模拟收款</span></div><div v-if="canQueryOrders" class="workbench__demo"><RouterLink to="/print/demo-orders">演示订单</RouterLink><span>查看当前账号的后台演示订单</span></div></article>
  </template>
</section>
</template>
<style scoped src="./workbench/workbench.css"></style>
