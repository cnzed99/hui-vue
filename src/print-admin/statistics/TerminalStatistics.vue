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
  <section class="print-statistics">
    <h2>按终端统计</h2>
    <p v-if="!allowed" role="alert">当前账号没有查看统计的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <p class="notice">原页面说明：当天9点后可看前一天数据，仅供参考，实际以财务交易订单为准。</p>
      <p class="notice">当前为本系统当前账号订单快照实时查询，按北京时间及订单创建日期分组。终端尚未关联；打印场景显示数据源原值。金额为订单金额快照，交易额口径待定，不能作为实收收入。</p>
      <el-form inline @submit.prevent="load">
        <el-form-item label="终端名称："><el-input v-model="terminal" maxlength="128" clearable placeholder="未关联终端" :disabled="busy" /></el-form-item>
        <el-form-item label="打印场景："><el-select v-model="scene" clearable placeholder="全部场景" :disabled="busy" style="width:190px"><el-option v-for="s in scenes" :key="s" :label="s" :value="s" /></el-select></el-form-item>
        <el-form-item label="交易时间："><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" :disabled="busy" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="busy" @click="load">查询</el-button><el-button :disabled="!report || busy" @click="exportData">导出当前查询</el-button></el-form-item>
      </el-form>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="busy" role="status">正在加载统计…</p>
      <template v-if="report">
        <p v-if="!report.list.length" role="status">当前筛选暂无订单数据。</p>
        <template v-else>
          <div class="chart-grid">
            <article><h3>订单数统计图</h3><div v-for="(r, i) in report.list" :key="i" class="chart-row"><span>{{ r.day }} · {{ r.scene }} · {{ r.currency }} · {{ r.simulation ? '模拟' : '非模拟' }}</span><div class="track"><i :style="{ width: width(r.orders, 'orders') }"></i></div><b>{{ r.orders }}</b></div></article>
            <article><h3>交易额统计图（订单金额快照，口径待定）</h3><div v-for="(r, i) in report.list" :key="i" class="chart-row"><span>{{ r.day }} · {{ r.currency }} · {{ r.simulation ? '模拟' : '非模拟' }}</span><div class="track"><i :style="{ width: width(r.amountSnapshot, 'amountSnapshot') }"></i></div><b>{{ amountText(r.amountSnapshot) }}</b></div></article>
          </div>
          <el-table :data="report.list" border><el-table-column prop="day" label="日期" /><el-table-column prop="terminal" label="打印终端" /><el-table-column prop="scene" label="场景原值" /><el-table-column prop="currency" label="币种" /><el-table-column label="数据类型"><template #default="{row}">{{ row.simulation ? '模拟' : '非模拟' }}</template></el-table-column><el-table-column prop="orders" label="订单数" /><el-table-column label="订单金额快照"><template #default="{row}">{{ amountText(row.amountSnapshot) }}</template></el-table-column><el-table-column prop="invalidAmountCount" label="待核对金额数" /></el-table>
        </template>
      </template>
    </template>
  </section>
</template>
<style scoped src="./statistics.css"></style>
