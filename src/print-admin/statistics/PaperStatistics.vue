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
watch([identity, allowed, () => props.daily], () => {
  generation++; busy.value = false; error.value = ''; report.value = null; page.value = 1; size.value = 20; terminal.value = ''; printer.value = ''
  if (allowed.value && context.status === 'ready') void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
const hasNext = computed(() => report.value ? BigInt(page.value) * BigInt(size.value) < BigInt(report.value.total) : false)
</script>
<template>
  <section class="print-statistics">
    <h2>{{ daily ? '按日统计' : '按总量统计' }}</h2>
    <p v-if="!allowed" role="alert">当前账号没有查看统计的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <p class="notice">客户端需V3.9.31115以上并设置剩余余量；统计仅供参考。</p>
      <p class="notice">本系统纸量数据源尚待打印端接入。只有实际收到的报告才显示数据；模拟报告单独标识。余量维护、刷新协议{{ daily ? '' : '与删除规则' }}待确认。</p>
      <el-form inline @submit.prevent="query">
        <el-form-item label="打印终端："><el-input v-model="terminal" maxlength="128" clearable placeholder="全部打印终端" :disabled="busy" /></el-form-item>
        <el-form-item label="打印机名称："><el-input v-model="printer" maxlength="128" clearable :disabled="busy" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="busy" @click="query">查询</el-button></el-form-item>
      </el-form>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="busy" role="status">正在加载纸量报告…</p>
      <template v-if="report">
        <p v-if="!report.list.length" role="status">当前筛选暂无纸量报告。打印端尚未接入时不会生成纸量数据。</p>
        <el-table :data="report.list" border empty-text="暂无纸量报告">
          <el-table-column prop="printer" label="打印机名称" min-width="160" /><el-table-column prop="terminal" label="打印终端" min-width="160" /><el-table-column prop="paper" label="纸张规格" />
          <template v-if="!daily"><el-table-column label="当前剩余量（张）"><template #default="{row}">{{ row.remaining ?? '未上报' }}</template></el-table-column><el-table-column label="余量预警（张）"><template #default="{row}">{{ row.warning ?? '未上报' }}</template></el-table-column></template>
          <el-table-column prop="total" :label="daily ? '当前打印量（张）' : '总打印量（张）'" />
          <template v-if="daily"><el-table-column prop="simplex" label="单面打印量（张）" /><el-table-column prop="duplex" label="双面打印量（张）" /><el-table-column prop="day" label="统计时间" /></template>
          <el-table-column prop="updatedAt" label="更新日期" min-width="165" /><el-table-column label="数据类型"><template #default="{row}">{{ row.simulation ? '模拟' : '非模拟' }}</template></el-table-column>
          <el-table-column v-if="!daily" label="操作"><template #default><el-button link disabled title="删除范围及规则待确认">删除（待定）</el-button></template></el-table-column>
        </el-table>
        <div class="pagination"><span>共 {{ report.total }} 条</span><label>每页显示数量：<select v-model="size" :disabled="busy" @change="query"><option v-for="n in [10,20,40,100]" :key="n" :value="n">{{ n }}</option></select></label><el-button :disabled="busy || page <= 1" @click="page--; load()">上一页</el-button><span>第 {{ page }} 页</span><el-button :disabled="busy || !hasNext" @click="page++; load()">下一页</el-button></div>
      </template>
    </template>
  </section>
</template>
<style scoped src="./statistics.css"></style>
