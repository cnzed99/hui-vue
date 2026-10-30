<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { terminalReport, failureText, amountText, csvCell } from './api'
import type { TerminalReport } from './api'
defineOptions({ name: 'PrintTerminalStatistics' })
const user = useUserStore(), context = usePrintContextStore()
const allowed = computed(() => user.permissions.has('*:*:*') || user.permissions.has('print:statistics:query'))
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
const terminal = ref(''), scene = ref(''), dates = ref<string[]>([]), busy = ref(false), error = ref('')
const report = ref<TerminalReport | null>(null)
const scenes = ref<string[]>([])
let generation = 0
const load = async () => {
  if (!allowed.value || context.status !== 'ready' || busy.value) return
  const ticket = ++generation
  busy.value = true; error.value = ''; report.value = null
  try {
    const data = await terminalReport({ terminal: terminal.value.trim(), scene: scene.value, ...(dates.value?.length === 2 ? { startDate: dates.value[0], endDate: dates.value[1] } : {}) })
    if (ticket !== generation) return
    report.value = data; scenes.value = data.scenes; dates.value = [data.startDate, data.endDate]
  } catch (e) { if (ticket === generation) error.value = failureText(e) }
  finally { if (ticket === generation) busy.value = false }
}
watch([identity, allowed], () => {
  generation++; busy.value = false; error.value = ''; report.value = null; scenes.value = []; terminal.value = ''; scene.value = ''; dates.value = []
  if (allowed.value && context.status === 'ready') void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
const reset = () => { terminal.value = ''; scene.value = ''; dates.value = []; void load() }
const width = (value: string | null, kind: 'orders' | 'amountSnapshot') => {
  const max = Math.max(0, ...(report.value?.list ?? []).map(row => Number(row[kind] ?? 0)))
  const n = Number(value ?? 0)
  return `${Number.isFinite(max) && Number.isFinite(n) && max > 0 ? Math.max(0, n / max * 100) : 0}%`
}
const exportData = () => {
  if (!report.value || busy.value) return
  const rows = [['日期', '打印终端', '场景原值', '币种', '模拟', '订单数', '订单金额快照（交易额口径待定）', '异常金额数'],
    ...report.value.list.map(r => [r.day, r.terminal, r.scene, r.currency, r.simulation ? '模拟' : '非模拟', r.orders, amountText(r.amountSnapshot), r.invalidAmountCount])]
  const url = URL.createObjectURL(new Blob(['\uFEFF' + rows.map(r => r.map(csvCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = '按终端统计-订单快照.csv'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template>
  <section class="print-statistics" :aria-busy="busy">
    <header class="page-heading"><h2>按终端统计</h2><span class="page-kind">打印订单量统计</span></header>
    <el-alert v-if="!allowed" title="当前账号没有查看统计的权限。" type="warning" :closable="false" show-icon />
    <el-alert v-else-if="context.status !== 'ready'" title="请先确认当前门店身份。" type="info" :closable="false" show-icon />
    <template v-else>
      <div class="statistics-panel filter-panel">
        <h3 class="panel-heading">统计条件</h3>
        <p class="notice reference-notice">当天9点后可查看前一天的数据，该统计结果仅供参考，实际数据以财务交易订单为准。</p>
        <el-form inline @submit.prevent="load">
          <el-form-item label="终端名称："><el-input v-model="terminal" maxlength="128" clearable placeholder="请输入终端名称" :disabled="busy" /></el-form-item>
          <el-form-item label="打印场景："><el-select v-model="scene" clearable placeholder="全部" :disabled="busy" class="filter-select"><el-option v-for="s in scenes" :key="s" :label="s" :value="s" /><template #empty><div class="select-empty">暂无场景选项，请先查询数据</div></template></el-select></el-form-item>
          <el-form-item label="交易时间："><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :disabled="busy" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="busy" @click="load">查询</el-button><el-button :disabled="busy" @click="reset">重置</el-button><el-button :disabled="!report?.list.length || busy" @click="exportData">导出当前查询</el-button></el-form-item>
        </el-form>
      </div>
      <el-alert class="source-note" title="当前为本系统当前账号订单快照，按北京时间及订单创建日期分组；终端尚未关联，场景显示数据源原值。金额为订单金额快照，交易额口径待定，不能作为实收收入。" type="info" :closable="false" show-icon />
      <div v-if="error" class="error-state" role="alert"><span>{{ error }}</span><el-button type="primary" plain :disabled="busy" @click="load">重新加载</el-button></div>
      <p v-if="busy" class="loading-state" role="status">正在加载统计，请稍候…</p>
      <p v-if="report" class="result-caption">查询区间：{{ report.startDate }} 至 {{ report.endDate }} · 北京时间 · {{ report.list.length }} 条分组记录。修改条件后请点击查询。</p>
      <div class="chart-grid">
        <article class="statistics-panel"><h3 class="panel-heading">订单数统计</h3>
          <div v-if="report?.list.length" class="chart-content"><div v-for="(r, i) in report.list" :key="i" class="chart-row"><span>{{ r.day }} · {{ r.terminal }} · {{ r.scene }} · {{ r.currency }} · {{ r.simulation ? '模拟' : '非模拟' }}</span><div class="track"><i :style="{ width: width(r.orders, 'orders') }"></i></div><b>{{ r.orders }} 单</b></div></div>
          <el-empty v-else :image-size="64" :description="busy ? '正在加载订单统计' : error ? '统计加载失败，请重新加载' : '当前筛选暂无订单数据'" />
        </article>
        <article class="statistics-panel amount-chart"><h3 class="panel-heading">交易额统计 <small>订单金额快照，口径待定</small></h3>
          <div v-if="report?.list.length" class="chart-content"><div v-for="(r, i) in report.list" :key="i" class="chart-row"><span>{{ r.day }} · {{ r.terminal }} · {{ r.scene }} · {{ r.currency }} · {{ r.simulation ? '模拟' : '非模拟' }}</span><div class="track"><i :style="{ width: width(r.amountSnapshot, 'amountSnapshot') }"></i></div><b>{{ amountText(r.amountSnapshot) }}</b></div></div>
          <el-empty v-else :image-size="64" :description="busy ? '正在加载金额快照' : error ? '统计加载失败，请重新加载' : '当前筛选暂无金额快照'" />
        </article>
      </div>
      <div class="statistics-panel table-panel"><h3 class="panel-heading">统计明细 <small>与当前查询及导出内容一致</small></h3>
        <el-table :data="report?.list ?? []" border stripe :empty-text="busy ? '正在加载统计…' : error ? '数据加载失败' : '当前筛选暂无订单数据'">
          <el-table-column prop="day" label="日期" min-width="115" /><el-table-column prop="terminal" label="打印终端" min-width="145" show-overflow-tooltip /><el-table-column prop="scene" label="场景原值" min-width="130" show-overflow-tooltip /><el-table-column prop="currency" label="币种" min-width="85" /><el-table-column label="数据类型" min-width="95"><template #default="{row}"><el-tag :type="row.simulation ? 'warning' : 'info'" effect="plain">{{ row.simulation ? '模拟' : '非模拟' }}</el-tag></template></el-table-column><el-table-column prop="orders" label="订单数" min-width="100" align="right" /><el-table-column label="订单金额快照" min-width="150" align="right"><template #default="{row}">{{ amountText(row.amountSnapshot) }}</template></el-table-column><el-table-column prop="invalidAmountCount" label="待核对金额数" min-width="130" align="right" />
        </el-table>
      </div>
    </template>
  </section>
</template>
<style scoped src="./statistics.css"></style>
