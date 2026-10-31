<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { classifyOrderError, getMiniappOrders, getOrders, orderFailure } from './api'
import type { MiniappOrderSummary, OrderFailure, OrderPage, OrderSummary } from './api'
import { DEMO_ORDERS, displayTime, parsePage, statusLabel, paymentStatusLabel, printStatusLabel, useOrderAccess } from './view'
import OrderState from './OrderState.vue'
import './orders.css'

defineOptions({ name: 'PrintDemoOrders' })
const route = useRoute()
const router = useRouter()
const access = useOrderAccess()
const miniappMode = computed(() => route.query.source === 'miniapp')
const pageNo = computed(() => parsePage(route.query.pageNo, 1, 1000000))
const pageSize = computed(() => parsePage(route.query.pageSize, 20, 100))
const data = ref<OrderPage<OrderSummary | MiniappOrderSummary> | null>(null)
const failure = ref<OrderFailure | null>(null)
const loading = ref(false)
let generation = 0
const visibleFailure = computed(() => access.failure.value || failure.value)
const pageCount = computed(() => data.value ? Math.max(1, Math.ceil(data.value.total / data.value.pageSize)) : 1)
const load = async () => {
  const current = ++generation
  data.value = null
  failure.value = null
  loading.value = false
  if (!access.ready.value) return
  if (pageNo.value === null || pageSize.value === null) { failure.value = orderFailure('invalid-input'); return }
  loading.value = true
  try {
    const result = miniappMode.value
      ? await getMiniappOrders(pageNo.value, pageSize.value)
      : await getOrders(pageNo.value, pageSize.value)
    if (current === generation) data.value = result
  } catch (error) { if (current === generation) failure.value = classifyOrderError(error) }
  finally { if (current === generation) loading.value = false }
}
watch([() => access.key.value, pageNo, pageSize, miniappMode], () => { void load() }, { immediate: true, flush: 'sync' })
const retry = () => {
  if (!access.ready.value) { void access.refreshContext(); return }
  void load()
}
onBeforeUnmount(() => { generation++ })
const go = (next: number, size = pageSize.value || 20) => {
  void router.push({ path: DEMO_ORDERS, query: { pageNo: String(next), pageSize: String(size),
    ...(miniappMode.value ? { source: 'miniapp' } : {}) } })
}
const changeSize = (event: Event) => go(1, Number((event.target as HTMLSelectElement).value))
const detailLink = (id: string) => ({ path: `${DEMO_ORDERS}/${id}`, query: {
  pageNo: String(pageNo.value || 1), pageSize: String(pageSize.value || 20),
  ...(miniappMode.value ? { source: 'miniapp' } : {})
} })
const switchMode = (miniapp: boolean) => {
  void router.push({ path: DEMO_ORDERS, query: { pageNo: '1', pageSize: String(pageSize.value || 20),
    ...(miniapp ? { source: 'miniapp' } : {}) } })
}
</script>
<template>
  <section class="orders" data-testid="demo-orders" :aria-busy="loading || (!access.ready.value && !visibleFailure)">
    <header class="orders-title"><h1>订单处理</h1><RouterLink to="/print/workbench">返回工作台</RouterLink></header>
    <div class="orders-card">
      <nav class="orders-source-tabs" aria-label="订单来源">
        <button type="button" :class="{ active: !miniappMode }" @click="switchMode(false)">后台演示订单</button>
        <button type="button" :class="{ active: miniappMode }" @click="switchMode(true)">小程序订单</button>
      </nav>
      <div class="orders-toolbar"><p>{{ miniappMode ? '当前门店的小程序模拟订单，可进入详情驱动配套后台处理。' : '仅展示当前账号的后台演示订单。支付状态与打印状态分别记录。' }}</p>
        <button v-if="access.ready.value" type="button" :disabled="loading" @click="load">刷新</button></div>
      <OrderState :failure="visibleFailure" :loading="loading || (!access.ready.value && !visibleFailure)" @retry="retry" />
      <button v-if="failure?.kind === 'invalid-input'" type="button" @click="go(1, 20)">返回第一页</button>
      <template v-if="data && !visibleFailure && access.ready.value">
        <p v-if="data.list.length === 0" class="orders-empty" data-testid="orders-empty" role="status">{{ data.total === 0 ? '当前账号暂无演示订单' : '当前页暂无订单，请返回前一页或第一页。' }}</p>
        <div v-else class="orders-table-wrap"><table class="orders-table">
          <thead><tr><th>订单号</th><th>来源</th><th>金额（元）</th><th>订单状态</th><th>支付状态</th><th>打印状态</th><th>创建时间（北京时间）</th><th>操作</th></tr></thead>
          <tbody><tr v-for="order in data.list" :key="order.id">
            <td class="orders-identifier">{{ order.orderNo }}</td><td>{{ order.source === 'MINIAPP_DEMO' ? '小程序' : '后台演示' }} <span v-if="order.simulation" class="orders-badge">模拟</span></td>
            <td class="orders-money">{{ order.totalAmount }}</td><td>{{ statusLabel(order.orderState) }}</td>
            <td>{{ paymentStatusLabel(order.paymentState) }}</td><td>{{ printStatusLabel(order.printState) }}</td>
            <td>{{ displayTime(order.createdAtMs) }}</td><td><RouterLink :to="detailLink(order.id)">查看</RouterLink></td>
          </tr></tbody>
        </table></div>
        <nav class="orders-pagination" aria-label="订单分页">
          <span>共 {{ data.total }} 条，第 {{ data.pageNo }} 页 / {{ pageCount }} 页</span>
          <label>每页 <select :value="data.pageSize" @change="changeSize">
            <option v-if="![10, 20, 50, 100].includes(data.pageSize)" :value="data.pageSize">{{ data.pageSize }}</option>
            <option v-for="size in [10, 20, 50, 100]" :key="size" :value="size">{{ size }}</option>
          </select> 条</label>
          <button type="button" :disabled="data.pageNo <= 1" @click="go(1)">首页</button>
          <button type="button" :disabled="data.pageNo <= 1" @click="go(data.pageNo - 1)">上一页</button>
          <button type="button" :disabled="data.pageNo >= pageCount || data.pageNo >= 1000000" @click="go(data.pageNo + 1)">下一页</button>
        </nav>
      </template>
    </div>
  </section>
</template>
