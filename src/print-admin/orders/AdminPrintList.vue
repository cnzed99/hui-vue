<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { classifyOrderError, getOrders, getOrder, getOrderEvents } from './api'
import type { OrderDetail, OrderEvent, OrderFailure, OrderFilters, OrderPage, OrderSummary } from './api'
import { displayTime, useOrderAccess } from './view'
import { P22_SCOPE, unavailableFields, unavailableText, detailItemCount } from './adminPrintModel'
import OrderState from './OrderState.vue'
import './orders.css'
defineOptions({ name: 'PrintAdminPrintList' })
const access = useOrderAccess()
const pageNo = ref(1), pageSize = ref(20)
const filters = reactive({ orderNo: '', createdFrom: '', createdTo: '', orderState: '', paymentState: '', refundState: '', printState: '' })
const appliedFilters = ref<OrderFilters>({}), filterError = ref('')
const stateFields = [
  { key: 'orderState', label: '订单原始状态' }, { key: 'paymentState', label: '支付原始状态' },
  { key: 'refundState', label: '退款原始状态' }, { key: 'printState', label: '打印原始状态' }
] as const
const filledStateFilterCount = computed(() => stateFields.filter(field => filters[field.key].trim()).length)
const data = ref<OrderPage<OrderSummary> | null>(null), loading = ref(false), failure = ref<OrderFailure | null>(null)
const selectedId = ref(''), detail = ref<OrderDetail | null>(null), detailLoading = ref(false), detailFailure = ref<OrderFailure | null>(null)
const events = ref<OrderPage<OrderEvent> | null>(null), eventsLoading = ref(false), eventFailure = ref<OrderFailure | null>(null), eventPage = ref(1)
const visibleFailure = computed(() => access.failure.value || failure.value)
const pageCount = computed(() => data.value ? Math.max(1, Math.ceil(data.value.total / data.value.pageSize)) : 1)
const itemCount = computed(() => detailItemCount(detail.value))
let generation = 0, detailGeneration = 0, eventGeneration = 0
const clearDetail = () => { detailGeneration++; eventGeneration++; selectedId.value = ''; detail.value = null; events.value = null; detailLoading.value = false; eventsLoading.value = false; detailFailure.value = null; eventFailure.value = null; eventPage.value = 1 }
const load = async () => {
  const ticket = ++generation; data.value = null; failure.value = null; loading.value = false; clearDetail()
  if (!access.ready.value) return
  loading.value = true
  try { const result = await getOrders(pageNo.value, pageSize.value, appliedFilters.value); if (ticket === generation) data.value = result }
  catch (error) { if (ticket === generation) failure.value = classifyOrderError(error) }
  finally { if (ticket === generation) loading.value = false }
}
const loadEvents = async (next = eventPage.value) => {
  const ticket = ++eventGeneration; events.value = null; eventFailure.value = null; eventsLoading.value = false
  if (!detail.value || !access.ready.value) return
  const id = detail.value.id; eventPage.value = next; eventsLoading.value = true
  try { const result = await getOrderEvents(id, next, 20); if (ticket === eventGeneration) events.value = result }
  catch (error) { if (ticket === eventGeneration) eventFailure.value = classifyOrderError(error) }
  finally { if (ticket === eventGeneration) eventsLoading.value = false }
}
const openDetail = async (id: string) => {
  clearDetail()
  if (!access.ready.value) return
  const ticket = ++detailGeneration; selectedId.value = id; detailLoading.value = true
  try {
    const result = await getOrder(id)
    if (ticket !== detailGeneration) return
    detail.value = result; void loadEvents(1)
  } catch (error) { if (ticket === detailGeneration) detailFailure.value = classifyOrderError(error) }
  finally { if (ticket === detailGeneration) detailLoading.value = false }
}
watch(() => access.key.value, () => { pageNo.value = 1; void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { generation++; detailGeneration++; eventGeneration++ })
const go = (next: number) => { if (!loading.value && Number.isSafeInteger(next) && next >= 1 && next <= 1000000) { pageNo.value = next; void load() } }
const changeSize = (event: Event) => { if (loading.value) return; pageSize.value = Number((event.target as HTMLSelectElement).value); go(1) }
const retry = () => { if (!access.ready.value) { void access.refreshContext(); return }; void load() }
const search = () => {
  filterError.value = ''
  const query: OrderFilters = {}
  const orderNo = filters.orderNo.trim()
  if (orderNo && !/^[A-Za-z0-9_-]{1,34}$/.test(orderNo)) { filterError.value = '请输入完整订单号（最多34个字母、数字、下划线或短横线）。'; return }
  if (orderNo) query.orderNo = orderNo
  for (const field of stateFields) {
    const value = filters[field.key].trim()
    if (value && !/^[A-Z][A-Z_]{0,31}$/.test(value)) { filterError.value = '状态请填写列表中的大写原始值，最多32个字符。'; return }
    if (value) query[field.key] = value
  }
  // Interpret the labelled wall-clock controls explicitly as Beijing time, regardless of browser timezone.
  if (filters.createdFrom) query.createdFromMs = Date.parse(`${filters.createdFrom}:00+08:00`)
  if (filters.createdTo) query.createdToMs = Date.parse(`${filters.createdTo}:59.999+08:00`)
  if ([query.createdFromMs, query.createdToMs].some(value => value != null && (!Number.isSafeInteger(value) || value < 0 || value > 8640000000000000))) {
    filterError.value = '请选择有效的订单创建时间。'; return
  }
  if (query.createdFromMs != null && query.createdToMs != null && query.createdFromMs > query.createdToMs) {
    filterError.value = '创建时间起点不能晚于终点。'; return
  }
  appliedFilters.value = query; pageNo.value = 1; void load()
}
const resetFilters = () => {
  Object.assign(filters, { orderNo: '', createdFrom: '', createdTo: '', orderState: '', paymentState: '', refundState: '', printState: '' })
  filterError.value = ''; appliedFilters.value = {}; pageNo.value = 1; void load()
}
</script>
<template>
<section class="orders admin-print-list" data-testid="p22-admin-print-list">
  <header class="orders-title"><h1>管理员打印列表 · 受限只读</h1><RouterLink to="/print/workbench">返回工作台</RouterLink></header>
  <div class="orders-card"><p class="p22-scope"><strong>{{ P22_SCOPE }}</strong>。仅查看当前登录账号可访问的数据；此入口未提供全店管理员记录。</p><p class="p22-notice">支付与打印状态按原值分别显示。QUEUED 只表示排队，模拟回执不证明设备打印或实际出纸。“仅当天可看”的适用规则待确认，本页不实施该限制。</p>
    <form class="p22-filters" @submit.prevent="search">
      <div class="p22-filters__primary">
        <label>订单号：<input v-model="filters.orderNo" maxlength="34" placeholder="完整订单号，精确匹配" /></label>
        <label class="p22-filters__time">订单创建时间（北京时间）：<input v-model="filters.createdFrom" type="datetime-local" aria-label="订单创建时间起点" /><span>至</span><input v-model="filters.createdTo" type="datetime-local" aria-label="订单创建时间终点" /></label>
        <div class="p22-filters__actions"><button type="submit" :disabled="loading || !access.ready.value">查询</button><button type="button" :disabled="loading || !access.ready.value" @click="resetFilters">重置</button></div>
      </div>
      <el-collapse class="p22-filters__more">
        <el-collapse-item name="states">
          <template #title><span>更多筛选</span><span class="p22-filters__count">已填写 {{ filledStateFilterCount }} 项状态条件</span></template>
          <div class="p22-filters__states">
            <label v-for="field in stateFields" :key="field.key">{{ field.label }}：<input v-model="filters[field.key]" maxlength="32" placeholder="按原始值精确匹配" /></label>
          </div>
        </el-collapse-item>
      </el-collapse>
    </form>
    <p v-if="filterError" role="alert">{{ filterError }}</p>
    <p class="p22-notice">筛选在服务端执行；时间范围包含起止分钟。实际打印时间尚未接入。</p>
    <div class="orders-toolbar"><button type="button" disabled title="后端未提供实际打印完成记录筛选">已打印（筛选未接入）</button><button v-if="access.ready.value" type="button" :disabled="loading" @click="load">刷新</button></div>
    <OrderState :failure="visibleFailure" :loading="loading || (!access.ready.value && !visibleFailure)" @retry="retry" />
    <template v-if="data && access.ready.value && !visibleFailure"><p v-if="data.list.length === 0" class="orders-empty" role="status">{{ data.total === 0 ? '当前账号没有符合筛选条件的后台演示订单。' : '当前页暂无订单，请返回前一页或第一页。' }}</p>
      <div v-else class="orders-table-wrap"><table class="orders-table"><thead><tr><th>选择</th><th>订单号</th><th>创建时间（北京时间）</th><th>金额 / 币种</th><th>订单原始状态</th><th>支付原始状态</th><th>打印原始状态</th><th>创建员工ID</th><th v-for="field in unavailableFields" :key="field.key">{{ field.label }}</th><th>报价页数 / 份数 / 单双面</th><th>项目条目数</th><th>操作</th></tr></thead>
      <tbody><tr v-for="order in data.list" :key="order.id"><td><input type="checkbox" disabled aria-label="批量操作未开放" /></td><td class="orders-identifier">{{ order.orderNo }}<small>{{ order.source }} · 模拟</small></td><td>{{ displayTime(order.createdAtMs) }}</td><td class="orders-money">{{ order.totalAmount }} {{ order.currency }}</td><td><code>{{ order.orderState }}</code></td><td><code>{{ order.paymentState }}</code></td><td><code>{{ order.printState }}</code></td><td>{{ order.createdByActorId }}</td><td v-for="field in unavailableFields" :key="field.key" class="p22-unavailable">{{ unavailableText(field.value) }}</td><td>在只读详情中查看</td><td>列表未提供，详情可查看</td><td><button type="button" @click="openDetail(order.id)">查看只读详情</button></td></tr></tbody></table></div>
      <nav class="orders-pagination" aria-label="真实订单分页"><span>共 {{ data.total }} 条，第 {{ data.pageNo }} / {{ pageCount }} 页</span><label>每页 <select :value="pageSize" :disabled="loading" @change="changeSize"><option v-for="size in [10,20,50,100]" :key="size" :value="size">{{ size }}</option></select> 条</label><button :disabled="loading || data.pageNo <= 1" @click="go(1)">首页</button><button :disabled="loading || data.pageNo <= 1" @click="go(data.pageNo - 1)">上一页</button><button :disabled="loading || data.pageNo >= pageCount || data.pageNo >= 1000000" @click="go(data.pageNo + 1)">下一页</button></nav>
    </template>
  </div>
  <article v-if="selectedId && access.ready.value" class="orders-card"><div class="orders-toolbar"><h2>只读订单详情</h2><button @click="clearDetail">关闭详情</button></div><OrderState :failure="detailFailure" :loading="detailLoading" @retry="openDetail(selectedId)" />
    <template v-if="detail && !detailFailure"><dl class="orders-facts"><div><dt>订单号</dt><dd>{{ detail.orderNo }}</dd></div><div><dt>创建时间</dt><dd>{{ displayTime(detail.createdAtMs) }}（北京时间）</dd></div><div><dt>创建员工ID</dt><dd>{{ detail.createdByActorId }}</dd></div><div><dt>项目条目数</dt><dd>{{ itemCount }}（详情 items 数量）</dd></div><div><dt>订单原始状态</dt><dd>{{ detail.orderState }}</dd></div><div><dt>支付原始状态</dt><dd>{{ detail.paymentState }}</dd></div><div><dt>打印原始状态</dt><dd>{{ detail.printState }}</dd></div><div><dt>金额 / 币种</dt><dd>{{ detail.totalAmount }} {{ detail.currency }}</dd></div></dl>
      <article v-for="(item,index) in detail.items" :key="item.id" class="orders-item"><h3>报价条目 {{ index + 1 }} · 原始快照</h3><dl class="orders-facts"><div class="orders-wide"><dt>文件ID</dt><dd>{{ item.fileId }}</dd></div><div><dt>报价每份页数</dt><dd>{{ item.quoteSnapshot.pagesPerCopy }}</dd></div><div><dt>报价份数</dt><dd>{{ item.quoteSnapshot.copies }}</dd></div><div><dt>单双面原值</dt><dd>{{ item.quoteSnapshot.side }}</dd></div><div><dt>条目金额</dt><dd>{{ item.amount }} {{ item.quoteSnapshot.currency }}</dd></div><div v-for="field in unavailableFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ unavailableText(field.value) }}</dd></div></dl></article>
      <h2>只读订单事件</h2><OrderState :failure="eventFailure" :loading="eventsLoading" @retry="loadEvents()" /><template v-if="events && !eventFailure"><p v-if="events.list.length === 0" class="orders-empty">当前页暂无事件。</p><ol v-else class="orders-events"><li v-for="event in events.list" :key="event.eventId"><span>事件原值：{{ event.type }} · 模拟</span><span>员工ID {{ event.actorId }} · 事件创建时间 {{ displayTime(event.createdAtMs) }}（北京时间）</span></li></ol><nav class="orders-pagination" aria-label="真实事件分页"><span>共 {{ events.total }} 条，第 {{ events.pageNo }} 页</span><button :disabled="eventsLoading || events.pageNo <= 1" @click="loadEvents(events.pageNo - 1)">上一页</button><button :disabled="eventsLoading || events.pageNo * events.pageSize >= events.total || events.pageNo >= 1000000" @click="loadEvents(events.pageNo + 1)">下一页</button></nav></template>
    </template>
  </article>
</section>
</template>
<style scoped>
.p22-scope { line-height: 1.8; }
.p22-notice { color: #7b8797; line-height: 1.8; font-size: 12px; }
.p22-filters { margin: 20px 0; }
.p22-filters__primary { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; }
.p22-filters label { display: flex; gap: 8px; align-items: center; color: #334155; }
.p22-filters input { border: 1px solid #cbd5e1; border-radius: 3px; padding: 7px 10px; color: #263445; background: #fff; color-scheme: light; }
.p22-filters input::placeholder { color: #94a3b8; }
.p22-filters input:focus-visible { border-color: #0284c7; outline: 2px solid #bae6fd; outline-offset: 1px; }
.p22-filters input:disabled { color: #94a3b8; background: #f8fafc; cursor: not-allowed; }
.p22-filters__actions { display: flex; gap: 8px; }
.p22-filters__more { margin-top: 14px; border-color: #e2e8f0; }
.p22-filters__more :deep(.el-collapse-item__header) { gap: 12px; color: #475569; font-size: 13px; }
.p22-filters__more :deep(.el-collapse-item__content) { padding-bottom: 16px; }
.p22-filters__count { color: #64748b; font-size: 12px; }
.p22-filters__states { display: flex; flex-wrap: wrap; gap: 12px 24px; }
.p22-filters__states input { width: 205px; }
.p22-unavailable { color: #94a3b8; }
.orders-table small { display: block; color: #8a96a5; font-size: 11px; margin-top: 7px; }
.orders-table th { white-space: nowrap; }
.orders-table td { min-width: 90px; overflow-wrap: anywhere; }
.orders-table { min-width: 2300px; }
.orders-identifier { min-width: 180px !important; }
</style>
