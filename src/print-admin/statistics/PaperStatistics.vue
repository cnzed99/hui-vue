<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { paperReport, failureText } from './api'
import type { PaperReport } from './api'
const props = defineProps<{ daily: boolean }>()
const user = useUserStore(), context = usePrintContextStore()
const allowed = computed(() => user.permissions.has('*:*:*') || user.permissions.has('print:statistics:query'))
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
const terminal = ref(''), printer = ref(''), page = ref(1), size = ref(20), busy = ref(false), error = ref('')
const report = ref<PaperReport | null>(null)
let generation = 0
const load = async () => {
  if (!allowed.value || context.status !== 'ready' || busy.value) return
  const ticket = ++generation
  busy.value = true; error.value = ''; report.value = null
  try {
    const data = await paperReport(props.daily, { terminal: terminal.value.trim(), printer: printer.value.trim(), page: page.value, size: size.value })
    if (ticket === generation) report.value = data
  } catch (e) { if (ticket === generation) error.value = failureText(e) }
  finally { if (ticket === generation) busy.value = false }
}
const query = () => { page.value = 1; void load() }
const reset = () => { terminal.value = ''; printer.value = ''; query() }
watch([identity, allowed, () => props.daily], () => {
  generation++; busy.value = false; error.value = ''; report.value = null; page.value = 1; size.value = 20; terminal.value = ''; printer.value = ''
  if (allowed.value && context.status === 'ready') void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
const hasNext = computed(() => report.value ? BigInt(page.value) * BigInt(size.value) < BigInt(report.value.total) : false)
</script>
<template>
  <section class="print-statistics" :aria-busy="busy">
    <header class="page-heading"><h2>{{ daily ? '按日统计' : '按总量统计' }}</h2><span class="page-kind">打印纸张量统计</span></header>
    <el-alert v-if="!allowed" title="当前账号没有查看统计的权限。" type="warning" :closable="false" show-icon />
    <el-alert v-else-if="context.status !== 'ready'" title="请先确认当前门店身份。" type="info" :closable="false" show-icon />
    <template v-else>
      <div class="statistics-panel filter-panel">
        <h3 class="panel-heading">统计条件</h3>
        <el-form inline @submit.prevent="query">
          <el-form-item label="打印终端："><el-input v-model="terminal" maxlength="128" clearable placeholder="全部打印终端" :disabled="busy" /></el-form-item>
          <el-form-item label="打印机名称："><el-input v-model="printer" maxlength="128" clearable placeholder="请输入打印机名称" :disabled="busy" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="busy" @click="query">查询</el-button><el-button :disabled="busy" @click="reset">重置</el-button></el-form-item>
        </el-form>
        <p class="notice reference-notice">客户端升级到V3.9.31115以上版本才支持纸张统计，需对纸张剩余余量进行设置，该统计结果仅供参考。</p>
      </div>
      <el-alert class="source-note" :title="`当前页面只读，显示实际收到的纸量报告，模拟报告单独标识。打印端尚未接入时不会生成数据；余量维护、刷新协议${daily ? '' : '与删除规则'}待确认。`" type="info" :closable="false" show-icon />
      <div v-if="error" class="error-state" role="alert"><span>{{ error }}</span><el-button type="primary" plain :disabled="busy" @click="load">重新加载</el-button></div>
      <p v-if="busy" class="loading-state" role="status">正在加载纸量报告，请稍候…</p>
      <div class="statistics-panel table-panel">
        <div class="table-toolbar"><h3>{{ daily ? '每日纸张打印量' : '纸张余量与累计用量' }}</h3><label class="page-size">每页显示数量：<el-select v-model="size" :disabled="busy" aria-label="每页显示数量" @change="query"><el-option v-for="n in [10,20,40,100]" :key="n" :label="String(n)" :value="n" /></el-select></label></div>
        <el-table :data="report?.list ?? []" border stripe :empty-text="busy ? '正在加载纸量报告…' : error ? '报告加载失败，请重新加载' : '当前筛选暂无纸量报告'">
          <el-table-column prop="printer" label="打印机名称" min-width="170" show-overflow-tooltip /><el-table-column prop="terminal" label="打印终端" min-width="170" show-overflow-tooltip /><el-table-column prop="paper" label="纸张规格" min-width="100" />
          <template v-if="!daily"><el-table-column label="当前剩余量（张）" min-width="155" align="right"><template #default="{row}">{{ row.remaining ?? '未上报' }}</template></el-table-column><el-table-column label="余量预警（张）" min-width="145" align="right"><template #default="{row}">{{ row.warning ?? '未上报' }}</template></el-table-column></template>
          <el-table-column prop="total" :label="daily ? '当前打印量（张）' : '总打印量（张）'" min-width="155" align="right" />
          <template v-if="daily"><el-table-column label="单面打印量（张）" min-width="155" align="right"><template #default="{row}">{{ row.simplex ?? '未上报' }}</template></el-table-column><el-table-column label="双面打印量（张）" min-width="155" align="right"><template #default="{row}">{{ row.duplex ?? '未上报' }}</template></el-table-column><el-table-column label="统计时间" min-width="120"><template #default="{row}">{{ row.day ?? '未上报' }}</template></el-table-column></template>
          <el-table-column prop="updatedAt" label="更新日期" min-width="180" /><el-table-column label="数据类型" min-width="100"><template #default="{row}"><el-tag :type="row.simulation ? 'warning' : 'info'" effect="plain">{{ row.simulation ? '模拟' : '非模拟' }}</el-tag></template></el-table-column>
          <el-table-column v-if="!daily" label="操作" min-width="115"><template #default><el-button link disabled title="删除范围及规则待确认，当前页面只读">删除（待定）</el-button></template></el-table-column>
        </el-table>
        <p v-if="report && !report.list.length" class="empty-note" role="status">当前筛选暂无纸量报告。打印端尚未接入时不会生成纸量数据。</p>
        <div v-if="report" class="pagination"><span>共 {{ report.total }} 条</span><el-button :disabled="busy || page <= 1" @click="page--; load()">上一页</el-button><span>第 {{ page }} 页</span><el-button :disabled="busy || !hasNext" @click="page++; load()">下一页</el-button></div>
      </div>
    </template>
  </section>
</template>
<style scoped src="./statistics.css"></style>
