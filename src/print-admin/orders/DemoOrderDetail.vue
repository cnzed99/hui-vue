<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { classifyOrderError, getOrder, getOrderEvents, orderFailure, validOrderId } from './api'
import type { OrderDetail, OrderEvent, OrderFailure, OrderPage } from './api'
import { DEMO_ORDERS, displayTime, parsePage, statusLabel, paymentStatusLabel, printStatusLabel, eventTypeLabel, useOrderAccess } from './view'
import OrderState from './OrderState.vue'
import './orders.css'

defineOptions({ name: 'PrintDemoOrderDetail' })
const route = useRoute()
const access = useOrderAccess()
const orderId = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const backLink = computed(() => ({ path: DEMO_ORDERS, query: {
  pageNo: String(parsePage(route.query.pageNo, 1, 1000000) || 1),
  pageSize: String(parsePage(route.query.pageSize, 20, 100) || 20)
} }))
const data = ref<OrderDetail | null>(null)
const events = ref<OrderPage<OrderEvent> | null>(null)
const failure = ref<OrderFailure | null>(null)
const eventFailure = ref<OrderFailure | null>(null)
const loading = ref(false)
const eventsLoading = ref(false)
const eventPageNo = ref(1)
const visibleFailure = computed(() => access.failure.value || failure.value)
let generation = 0
let eventGeneration = 0
const loadEvents = async (pageNo = eventPageNo.value) => {
  const current = ++eventGeneration
  events.value = null; eventFailure.value = null; eventsLoading.value = false
  if (!data.value || !access.ready.value) return
  const id = data.value.id
  eventPageNo.value = pageNo
  eventsLoading.value = true
  try {
    const result = await getOrderEvents(id, pageNo, 20)
    if (current === eventGeneration) events.value = result
  } catch (error) { if (current === eventGeneration) eventFailure.value = classifyOrderError(error) }
  finally { if (current === eventGeneration) eventsLoading.value = false }
}
const load = async () => {
  const current = ++generation
  eventGeneration++
  data.value = null; events.value = null; failure.value = null; eventFailure.value = null
  loading.value = false; eventsLoading.value = false; eventPageNo.value = 1
  if (!access.ready.value) return
  if (!validOrderId(orderId.value)) { failure.value = orderFailure('invalid-input'); return }
  loading.value = true
  try {
    const result = await getOrder(orderId.value)
    if (current !== generation) return
    data.value = result
    void loadEvents(1)
  } catch (error) { if (current === generation) failure.value = classifyOrderError(error) }
  finally { if (current === generation) loading.value = false }
}
watch([() => access.key.value, orderId], () => { void load() }, { immediate: true, flush: 'sync' })
const retry = () => {
  if (!access.ready.value) { void access.refreshContext(); return }
  void load()
}
onBeforeUnmount(() => { generation++; eventGeneration++ })
</script>
<template>
  <section class="orders" data-testid="demo-order-detail" :aria-busy="loading || (!access.ready.value && !visibleFailure)">
    <header class="orders-title"><h1>演示订单详情</h1><RouterLink :to="backLink">返回订单列表</RouterLink></header>
    <div class="orders-card">
      <OrderState :failure="visibleFailure" :loading="loading || (!access.ready.value && !visibleFailure)" @retry="retry" />
      <template v-if="data && !visibleFailure && access.ready.value">
        <div class="orders-toolbar"><h2>订单信息 <span v-if="data.simulation" class="orders-badge">模拟</span></h2><button type="button" @click="load">刷新</button></div>
        <dl class="orders-facts">
          <div><dt>订单号</dt><dd>{{ data.orderNo }}</dd></div><div><dt>来源</dt><dd>后台演示</dd></div>
          <div><dt>订单金额</dt><dd class="orders-money">{{ data.totalAmount }} 元</dd></div>
          <div><dt>订单状态</dt><dd>{{ statusLabel(data.orderState) }}</dd></div>
          <div><dt>支付状态</dt><dd>{{ paymentStatusLabel(data.paymentState) }}</dd></div>
          <div><dt>打印状态</dt><dd>{{ printStatusLabel(data.printState) }}</dd></div>
          <div><dt>退款状态</dt><dd>{{ statusLabel(data.refundState) }}</dd></div>
          <div><dt>配送方式 / 状态</dt><dd>{{ statusLabel(data.deliveryMode) }} / {{ statusLabel(data.deliveryState) }}</dd></div>
          <div><dt>创建员工</dt><dd>{{ data.createdByActorId }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ displayTime(data.createdAtMs) }}（北京时间）</dd></div>
        </dl>
        <h2>文件与报价快照</h2>
        <article v-for="(item, index) in data.items" :key="item.id" class="orders-item">
          <h3>文件 {{ index + 1 }}</h3>
          <dl class="orders-facts">
            <div><dt>每份页数</dt><dd>{{ item.quoteSnapshot.pagesPerCopy }} 页</dd></div>
            <div><dt>打印面</dt><dd>{{ statusLabel(item.quoteSnapshot.side) }}</dd></div>
            <div><dt>份数</dt><dd>{{ item.quoteSnapshot.copies }}</dd></div>
            <div><dt>每份纸张</dt><dd>{{ item.quoteSnapshot.sheetsPerCopy }} 张</dd></div>
            <div><dt>总纸张</dt><dd>{{ item.quoteSnapshot.totalSheets }} 张</dd></div>
            <div><dt>适用单价</dt><dd>{{ item.quoteSnapshot.pricePerSheet }} 元 / 张</dd></div>
            <div><dt>项目金额</dt><dd>{{ item.amount }} 元</dd></div>
            <div><dt>阶梯价</dt><dd>{{ item.quoteSnapshot.tierApplied ? '已命中' : '未命中' }}</dd></div>
            <div><dt>价格版本</dt><dd>{{ item.quoteSnapshot.priceProfileVersion }}</dd></div>
          </dl>
          <details class="orders-record-ids">
            <summary>记录标识</summary>
            <dl class="orders-facts">
              <div class="orders-wide"><dt>文件标识</dt><dd>{{ item.fileId }}</dd></div>
              <div class="orders-wide"><dt>报价标识</dt><dd>{{ item.quoteId }}</dd></div>
            </dl>
          </details>
        </article>
      </template>
    </div>
    <div v-if="data && !visibleFailure && access.ready.value" class="orders-card">
      <h2>订单事件</h2>
      <OrderState :failure="eventFailure" :loading="eventsLoading" @retry="loadEvents()" />
      <template v-if="events && !eventFailure">
        <p v-if="events.list.length === 0" class="orders-empty">当前页暂无订单事件。</p>
        <ol v-else class="orders-events"><li v-for="event in events.list" :key="event.eventId">
          <span>{{ eventTypeLabel(event.type) }} <span v-if="event.simulation" class="orders-badge">模拟</span></span>
          <span>员工 {{ event.actorId }} · {{ displayTime(event.createdAtMs) }}（北京时间）</span>
        </li></ol>
        <nav class="orders-pagination" aria-label="订单事件分页">
          <span>共 {{ events.total }} 条，第 {{ events.pageNo }} 页</span>
          <button type="button" :disabled="events.pageNo <= 1" @click="loadEvents(events.pageNo - 1)">上一页</button>
          <button type="button" :disabled="events.pageNo * events.pageSize >= events.total || events.pageNo >= 1000000" @click="loadEvents(events.pageNo + 1)">下一页</button>
        </nav>
      </template>
    </div>
  </section>
</template>
