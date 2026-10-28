<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { getPrinters, printerFailure } from './api'
import type { Printer, TerminalOption } from './api'
const user = useUserStore(); const context = usePrintContextStore()
const permitted = computed(() => user.permissions.has('*:*:*') || user.permissions.has('print:printer-directory:query'))
const ready = computed(() => context.status === 'ready')
const rows = shallowRef<Printer[]>([]); const terminals = shallowRef<TerminalOption[]>([])
const terminalId = ref(''); const page = ref(1); const pageSize = ref(10); const total = ref(0)
const busy = ref(false); const loaded = ref(false); const message = ref(''); let generation = 0
const load = async () => {
  if (busy.value || !ready.value || !permitted.value) return
  const ticket = ++generation; rows.value = []; total.value = 0; loaded.value = false; busy.value = true; message.value = ''
  try {
    const result = await getPrinters({ terminalId: terminalId.value, page: page.value, pageSize: pageSize.value })
    if (ticket !== generation) return
    rows.value = result.list; terminals.value = result.terminals; total.value = result.total; loaded.value = true
  } catch (error) { if (ticket === generation) message.value = printerFailure(error) }
  finally { if (ticket === generation) busy.value = false }
}
const selectTerminal = (id: string) => { if (!busy.value) { terminalId.value = id; page.value = 1; void load() } }
watch(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}/${permitted.value}`, () => {
  generation++; rows.value = []; terminals.value = []; total.value = 0; terminalId.value = ''; page.value = 1; loaded.value = false; busy.value = false; message.value = ''
  if (ready.value && permitted.value) void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { generation++ })
const flag = (value: boolean | null) => value === null ? '未提供' : value ? '是' : '否'
</script>
<template><section class="printer-directory"><h2>打印机</h2><p>仅查看当前账号的打印机目录。设备上报尚未接入，状态显示“未上报”。</p>
<p v-if="!permitted" role="alert">当前账号没有查看打印机的权限。</p><p v-else-if="!ready" role="status">请先确认当前门店身份。</p>
<div v-else class="printer-layout"><aside aria-label="所属终端筛选"><el-button :type="terminalId === '' ? 'primary' : 'default'" :disabled="busy" @click="selectTerminal('')">全部打印机</el-button><el-button v-for="terminal in terminals" :key="terminal.id" :type="terminalId === terminal.id ? 'primary' : 'default'" :disabled="busy" @click="selectTerminal(terminal.id)"><span>{{ terminal.name }}<small>{{ terminal.hostName }} · 手机自助：{{ flag(terminal.mobileSelfService) }}</small></span></el-button></aside>
<main><el-button :disabled="busy" @click="load">重新加载</el-button><p v-if="message" role="alert">{{ message }}</p><p v-if="busy" role="status">正在加载打印机…</p>
<el-table v-if="loaded" :data="rows" border empty-text="当前终端筛选下暂无打印机"><el-table-column prop="name" label="打印机名称" min-width="150" /><el-table-column prop="terminalName" label="所属终端" min-width="140" /><el-table-column label="地址" min-width="140"><template #default="{row}">{{ row.address || '未提供' }}</template></el-table-column><el-table-column label="默认" width="100"><template #default="{row}"><el-switch v-if="row.defaultPrinter !== null" :model-value="row.defaultPrinter" disabled /><span v-else>未提供</span></template></el-table-column><el-table-column label="手机自助" width="110"><template #default="{row}">{{ flag(row.mobileSelfService) }}</template></el-table-column><el-table-column label="状态" width="100"><template #default><el-tag type="info">未上报</el-tag></template></el-table-column><el-table-column prop="updatedAt" label="更新日期" min-width="160" /><el-table-column label="操作" min-width="320"><template #default><el-button link disabled>打印首选项</el-button><el-button link disabled>纸盒设置</el-button><el-button link disabled>设置手机自助打印</el-button><el-button link disabled>删除</el-button><el-button link disabled>二维码</el-button><el-button link disabled>海报</el-button></template></el-table-column></el-table>
<el-pagination v-if="loaded" v-model:current-page="page" :total="total" :page-size="pageSize" layout="total, prev, pager, next, jumper" :disabled="busy" @current-change="load" />
<p class="printer-note">首选项、纸盒、默认切换、手机自助保存、删除、二维码/海报及驱动命令尚未开放。共享计价模式和额外增收不在本页开放范围。</p></main></div></section></template>
<style scoped>.printer-directory{padding:20px;background:#fff}.printer-layout{display:grid;grid-template-columns:220px minmax(0,1fr);gap:20px}aside{display:flex;flex-direction:column;gap:10px}aside .el-button{margin:0;height:auto;min-height:44px;white-space:normal;text-align:left;padding:12px}aside small{display:block;margin-top:6px;overflow-wrap:anywhere}main{min-width:0}.el-pagination{margin:20px 0}.printer-note{color:#606266;line-height:1.7}</style>
