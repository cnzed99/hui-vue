<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { hasPermission } from '@/directives/permission/hasPermi'
import { actMiniappPrintTask, classifyOrderError, createMiniappPrintTask, getMiniappOrder,
  getOrder, getOrderEvents, orderFailure, validOrderId } from './api'
import type { MiniappOrderDetail, MiniappPrintActionInput, OrderDetail, OrderEvent,
  OrderFailure, OrderPage } from './api'
import { DEMO_ORDERS, cancellationOutcomeLabel, displayTime, eventTypeLabel, parsePage, paymentStatusLabel,
  printStatusLabel, refundStatusLabel, statusLabel, useOrderAccess } from './view'
import OrderState from './OrderState.vue'
import './orders.css'

defineOptions({ name: 'PrintDemoOrderDetail' })
const route = useRoute()
const access = useOrderAccess()
const miniappMode = computed(() => route.query.source === 'miniapp')
const processPermitted = computed(() => hasPermission(['print:order:process']))
const orderId = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const backLink = computed(() => ({ path: DEMO_ORDERS, query: {
  pageNo: String(parsePage(route.query.pageNo, 1, 1000000) || 1),
  pageSize: String(parsePage(route.query.pageSize, 20, 100) || 20),
  ...(miniappMode.value ? { source: 'miniapp' } : {})
} }))
const data = ref<OrderDetail | null>(null)
const miniappData = ref<MiniappOrderDetail | null>(null)
const events = ref<OrderPage<OrderEvent> | null>(null)
const failure = ref<OrderFailure | null>(null)
const eventFailure = ref<OrderFailure | null>(null)
const loading = ref(false)
const eventsLoading = ref(false)
const processing = ref(false)
const eventPageNo = ref(1)
const visibleFailure = computed(() => access.failure.value || failure.value)
const task = computed(() => miniappData.value?.printTask || null)
const cancellation = computed(() => miniappData.value?.cancellation || null)
const refundReceipt = computed(() => cancellation.value?.refundReceipt || null)
const reviewSummary = computed(() => cancellation.value?.reviewSummary || null)
const printableOrder = computed(() => miniappData.value?.orderState === 'OPEN' &&
  miniappData.value.paymentState === 'SUCCEEDED' && miniappData.value.refundState === 'NONE')
const canCreateTask = computed(() => printableOrder.value && !task.value && miniappData.value?.printState === 'NOT_CREATED')
const leaseExpired = computed(() => !!task.value?.leaseUntilMs && Date.now() >= task.value.leaseUntilMs)
let generation = 0
let eventGeneration = 0

const loadEvents = async (pageNo = eventPageNo.value) => {
  const current = ++eventGeneration
  events.value = null; eventFailure.value = null; eventsLoading.value = false
  if (!data.value || !access.ready.value || miniappMode.value) return
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
  data.value = null; miniappData.value = null; events.value = null
  failure.value = null; eventFailure.value = null
  loading.value = false; eventsLoading.value = false; eventPageNo.value = 1
  if (!access.ready.value) return
  if (!validOrderId(orderId.value)) { failure.value = orderFailure('invalid-input'); return }
  loading.value = true
  try {
    if (miniappMode.value) {
      const result = await getMiniappOrder(orderId.value)
      if (current !== generation) return
      miniappData.value = result
    }
    else {
      const result = await getOrder(orderId.value)
      if (current !== generation) return
      data.value = result
      void loadEvents(1)
    }
  } catch (error) { if (current === generation) failure.value = classifyOrderError(error) }
  finally { if (current === generation) loading.value = false }
}
watch([() => access.key.value, orderId, miniappMode], () => { void load() }, { immediate: true, flush: 'sync' })
const retry = () => {
  if (!access.ready.value) { void access.refreshContext(); return }
  void load()
}
onBeforeUnmount(() => { generation++; eventGeneration++ })

const actionLabels: Record<MiniappPrintActionInput['action'], string> = {
  CLAIM: '模拟终端领取', REQUEUE_EXPIRED_CLAIM: '过期领取重新排队', ACCEPT: '模拟终端受理',
  START: '开始模拟执行', SUCCEED: '写入模拟成功回执', FAIL: '写入模拟失败回执',
  LOSE_RECEIPT: '模拟回执丢失', MARK_TIMEOUT_UNKNOWN: '标记超时待核实',
  ABORT_FILE_UNAVAILABLE: '文件不可用时终止'
}
const createTask = async () => {
  if (!processPermitted.value || !canCreateTask.value || !miniappData.value || processing.value || !window.confirm('仅创建模拟打印任务，不会传输文件或驱动真实打印机。是否继续？')) return
  const requestGeneration = generation
  const requestOrderId = orderId.value
  const requestAccessKey = access.key.value
  processing.value = true; failure.value = null
  try {
    await createMiniappPrintTask(miniappData.value.id)
    if (requestGeneration !== generation || requestOrderId !== orderId.value || requestAccessKey !== access.key.value) return
    await load()
  } catch (error) {
    if (requestGeneration === generation && requestOrderId === orderId.value && requestAccessKey === access.key.value) {
      failure.value = classifyOrderError(error)
    }
  }
  finally { processing.value = false }
}
const runAction = async (action: MiniappPrintActionInput['action']) => {
  const current = task.value
  if (!processPermitted.value || !printableOrder.value || !current || current.state === 'CANCELLED' || processing.value || !window.confirm(`${actionLabels[action]}：本操作只推进模拟状态，不代表真实出纸。是否继续？`)) return
  const requestGeneration = generation
  const requestOrderId = orderId.value
  const requestAccessKey = access.key.value
  const input: MiniappPrintActionInput = { action, expectedVersion: current.version }
  if (['ACCEPT', 'START', 'SUCCEED', 'FAIL', 'LOSE_RECEIPT'].includes(action)) {
    if (!current.leaseId || current.fence < 1) return
    input.leaseId = current.leaseId; input.fence = current.fence
  }
  if (['SUCCEED', 'FAIL', 'ABORT_FILE_UNAVAILABLE'].includes(action)) input.receiptSeq = 1
  processing.value = true; failure.value = null
  try {
    await actMiniappPrintTask(current, input)
    if (requestGeneration !== generation || requestOrderId !== orderId.value || requestAccessKey !== access.key.value) return
    await load()
  } catch (error) {
    if (requestGeneration !== generation || requestOrderId !== orderId.value || requestAccessKey !== access.key.value) return
    failure.value = classifyOrderError(error)
    await load()
  }
  finally { processing.value = false }
}
</script>

<template>
  <section class="orders" data-testid="demo-order-detail" :aria-busy="loading || processing || (!access.ready.value && !visibleFailure)">
    <header class="orders-title"><h1>{{ miniappMode ? '小程序订单处理' : '演示订单详情' }}</h1><RouterLink :to="backLink">返回订单列表</RouterLink></header>
    <div class="orders-card">
      <OrderState :failure="visibleFailure" :loading="loading || (!access.ready.value && !visibleFailure)" @retry="retry" />
      <template v-if="miniappData && !visibleFailure && access.ready.value">
        <div class="orders-toolbar"><h2>订单信息 <span class="orders-badge">小程序模拟</span></h2><button type="button" :disabled="processing" @click="load">刷新</button></div>
        <dl class="orders-facts">
          <div><dt>订单号</dt><dd>{{ miniappData.orderNo }}</dd></div><div><dt>来源</dt><dd>小程序</dd></div>
          <div><dt>订单金额</dt><dd class="orders-money">{{ miniappData.totalAmount }} 元</dd></div>
          <div><dt>订单状态</dt><dd>{{ statusLabel(miniappData.orderState) }}</dd></div>
          <div><dt>支付状态</dt><dd>{{ paymentStatusLabel(miniappData.paymentState) }}</dd></div>
          <div><dt>打印状态</dt><dd>{{ printStatusLabel(miniappData.printState) }}</dd></div>
          <div><dt>退款状态</dt><dd>{{ refundStatusLabel(miniappData.refundState) }}</dd></div>
          <div><dt>取消 / 人工核实</dt><dd>{{ cancellationOutcomeLabel(miniappData.cancellationOutcome) }}</dd></div>
          <div><dt>版本</dt><dd>{{ miniappData.version }}</dd></div>
          <div class="orders-wide"><dt>小程序主体</dt><dd>{{ miniappData.principalId }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ displayTime(miniappData.createdAtMs) }}（北京时间）</dd></div>
        </dl>
        <h2>文件与冻结计价</h2>
        <article class="orders-item"><dl class="orders-facts">
          <div><dt>每份页数</dt><dd>{{ miniappData.item.quoteSnapshot.pagesPerCopy }} 页</dd></div>
          <div><dt>打印面</dt><dd>{{ statusLabel(miniappData.item.quoteSnapshot.side) }}</dd></div>
          <div><dt>份数</dt><dd>{{ miniappData.item.quoteSnapshot.copies }}</dd></div>
          <div><dt>每份纸张</dt><dd>{{ miniappData.item.quoteSnapshot.sheetsPerCopy }} 张</dd></div>
          <div><dt>总纸张</dt><dd>{{ miniappData.item.quoteSnapshot.totalSheets }} 张</dd></div>
          <div><dt>适用单价</dt><dd>{{ miniappData.item.quoteSnapshot.pricePerSheet }} 元 / 张</dd></div>
          <div><dt>项目金额</dt><dd>{{ miniappData.item.amount }} 元</dd></div>
          <div><dt>阶梯价</dt><dd>{{ miniappData.item.quoteSnapshot.tierApplied ? '已命中' : '未命中' }}</dd></div>
        </dl></article>
      </template>
      <template v-else-if="data && !visibleFailure && access.ready.value">
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
          <h3>文件 {{ index + 1 }}</h3><dl class="orders-facts">
            <div><dt>每份页数</dt><dd>{{ item.quoteSnapshot.pagesPerCopy }} 页</dd></div>
            <div><dt>打印面</dt><dd>{{ statusLabel(item.quoteSnapshot.side) }}</dd></div>
            <div><dt>份数</dt><dd>{{ item.quoteSnapshot.copies }}</dd></div>
            <div><dt>每份纸张</dt><dd>{{ item.quoteSnapshot.sheetsPerCopy }} 张</dd></div>
            <div><dt>总纸张</dt><dd>{{ item.quoteSnapshot.totalSheets }} 张</dd></div>
            <div><dt>适用单价</dt><dd>{{ item.quoteSnapshot.pricePerSheet }} 元 / 张</dd></div>
            <div><dt>项目金额</dt><dd>{{ item.amount }} 元</dd></div>
            <div><dt>阶梯价</dt><dd>{{ item.quoteSnapshot.tierApplied ? '已命中' : '未命中' }}</dd></div>
            <div><dt>价格版本</dt><dd>{{ item.quoteSnapshot.priceProfileVersion }}</dd></div>
          </dl></article>
      </template>
    </div>

    <div v-if="miniappData && cancellation && !visibleFailure && access.ready.value" class="orders-card" data-testid="miniapp-cancellation">
      <div class="orders-toolbar"><h2>取消与人工核实记录</h2><span class="orders-badge">只读记录</span></div>
      <dl class="orders-facts">
        <div><dt>申请结果</dt><dd>{{ cancellationOutcomeLabel(cancellation.originalResult.outcome) }}</dd></div>
        <div><dt>登记时间</dt><dd>{{ displayTime(cancellation.originalResult.createdAtMs) }}（北京时间）</dd></div>
        <div><dt>登记时订单版本</dt><dd>{{ cancellation.originalResult.orderVersionBefore }} → {{ cancellation.originalResult.orderVersionAfter }}</dd></div>
        <div><dt>当前订单版本</dt><dd>{{ miniappData.version }}</dd></div>
      </dl>
      <template v-if="reviewSummary">
        <p class="orders-warning">申请已登记，原任务尚未被停止，当前未退款。</p>
        <h3>申请时冻结快照</h3>
        <dl class="orders-facts">
          <div><dt>任务状态</dt><dd>{{ printStatusLabel(reviewSummary.taskStateAtRequest) }}</dd></div>
          <div><dt>任务版本 / fence</dt><dd>{{ reviewSummary.taskVersionAtRequest }} / {{ reviewSummary.fenceAtRequest }}</dd></div>
          <div><dt>核实状态</dt><dd>待人工核实</dd></div>
        </dl>
        <p>当前任务信息显示在下方。任务后续推进不会改变这份申请时快照；本页不提供退款、重打或核实结论操作。</p>
      </template>
      <p v-else-if="cancellation.originalResult.outcome === 'CANCELLED_UNPAID'">未付款订单已取消，没有发生模拟退款。</p>
      <template v-if="refundReceipt">
        <h3>全额模拟退款收据</h3>
        <dl class="orders-facts">
          <div class="orders-wide"><dt>退款收据号</dt><dd>{{ refundReceipt.id }}</dd></div>
          <div class="orders-wide"><dt>原模拟付款收据</dt><dd>{{ refundReceipt.paymentReceiptId }}</dd></div>
          <div><dt>模拟退款金额</dt><dd class="orders-money">{{ refundReceipt.amount }} 元</dd></div>
          <div><dt>模拟退款时间</dt><dd>{{ displayTime(refundReceipt.createdAtMs) }}（北京时间）</dd></div>
          <div><dt>反向借方</dt><dd>模拟门店 {{ refundReceipt.debit.amount }} {{ refundReceipt.debit.currency }}</dd></div>
          <div><dt>反向贷方</dt><dd>模拟用户 {{ refundReceipt.credit.amount }} {{ refundReceipt.credit.currency }}</dd></div>
        </dl>
        <p class="orders-warning">模拟退款已记录，不涉及真实资金，不代表微信退款到账。</p>
      </template>
    </div>

    <div v-if="miniappData && !visibleFailure && access.ready.value" class="orders-card orders-process">
      <div class="orders-toolbar"><h2>模拟打印处理</h2><span class="orders-badge">不会驱动真实打印机</span></div>
      <p v-if="miniappData.orderState === 'CANCELLED'">订单已取消，不能创建或推进打印任务。</p>
      <p v-else-if="miniappData.paymentState !== 'SUCCEEDED'">订单尚未模拟支付，不能创建打印任务。</p>
      <p v-if="!processPermitted">当前账号只有查看权限，不能推进模拟打印任务。</p>
      <template v-if="!task && canCreateTask && processPermitted">
        <p>尚未创建打印任务。创建时会再次检查文件是否有效，任务全局唯一。</p>
        <button type="button" :disabled="processing" @click="createTask">创建唯一模拟打印任务</button>
      </template>
      <template v-if="task">
        <dl class="orders-facts">
          <div><dt>任务状态</dt><dd>{{ printStatusLabel(task.state) }}</dd></div>
          <div><dt>任务版本 / fence</dt><dd>{{ task.version }} / {{ task.fence }}</dd></div>
          <div class="orders-wide"><dt>稳定 Job ID</dt><dd>{{ task.jobId }}</dd></div>
          <div><dt>计划份数 / 纸张</dt><dd>{{ task.plannedCopies }} / {{ task.plannedSheets }}</dd></div>
          <div><dt>租约</dt><dd>{{ task.leaseUntilMs ? `${displayTime(task.leaseUntilMs)}${leaseExpired ? '（已过期）' : ''}` : '无' }}</dd></div>
        </dl>
        <div v-if="processPermitted && printableOrder && task.state !== 'CANCELLED'" class="orders-actions">
          <button v-if="task.state === 'QUEUED'" type="button" :disabled="processing" @click="runAction('CLAIM')">模拟终端领取</button>
          <button v-if="task.state === 'QUEUED' || task.state === 'CLAIMED'" type="button" class="secondary" :disabled="processing" @click="runAction('ABORT_FILE_UNAVAILABLE')">文件不可用时终止</button>
          <button v-if="task.state === 'CLAIMED' && !leaseExpired" type="button" :disabled="processing" @click="runAction('ACCEPT')">模拟终端受理</button>
          <button v-if="task.state === 'CLAIMED' && leaseExpired" type="button" :disabled="processing" @click="runAction('REQUEUE_EXPIRED_CLAIM')">过期后重新排队</button>
          <button v-if="task.state === 'ACCEPTED' && !leaseExpired" type="button" :disabled="processing" @click="runAction('START')">开始模拟执行</button>
          <button v-if="task.state === 'EXECUTING' && !leaseExpired" type="button" :disabled="processing" @click="runAction('SUCCEED')">写入模拟成功回执</button>
          <button v-if="(task.state === 'ACCEPTED' || task.state === 'EXECUTING') && !leaseExpired" type="button" class="secondary" :disabled="processing" @click="runAction('FAIL')">写入模拟失败回执</button>
          <button v-if="(task.state === 'ACCEPTED' || task.state === 'EXECUTING') && !leaseExpired" type="button" class="secondary" :disabled="processing" @click="runAction('LOSE_RECEIPT')">模拟回执丢失</button>
          <button v-if="(task.state === 'ACCEPTED' || task.state === 'EXECUTING') && leaseExpired" type="button" :disabled="processing" @click="runAction('MARK_TIMEOUT_UNKNOWN')">标记超时待核实</button>
        </div>
        <div v-if="task.receipt" class="orders-receipt"><strong>{{ task.receipt.result === 'SIMULATED_SUCCESS' ? '模拟成功回执' : '模拟失败回执' }}</strong>
          <span>完成 {{ task.receipt.completedCopies }} 份 / {{ task.receipt.completedSheets }} 张</span>
          <span v-if="task.receipt.reason">原因：{{ task.receipt.reason }}</span><span>该回执不代表真实出纸。</span></div>
        <p v-else-if="task.state === 'RESULT_UNKNOWN'" class="orders-warning">结果待人工核实：系统不会自动重排、重打或生成第二任务。</p>
        <p v-else-if="task.state === 'CANCELLED'">任务在首次领取前已取消，没有生成打印回执。</p>
      </template>
    </div>

    <div v-if="miniappData && !visibleFailure && access.ready.value" class="orders-card">
      <h2>订单与打印事件</h2><ol class="orders-events"><li v-for="event in miniappData.events" :key="event.id">
        <span>{{ eventTypeLabel(event.type) }} <span class="orders-badge">模拟</span></span>
        <span>{{ event.actorType === 'STAFF_SIMULATOR' ? '后台处理' : '小程序' }} · {{ displayTime(event.createdAtMs) }}（北京时间）</span>
      </li></ol>
    </div>
    <div v-else-if="data && !visibleFailure && access.ready.value" class="orders-card">
      <h2>订单事件</h2><OrderState :failure="eventFailure" :loading="eventsLoading" @retry="loadEvents()" />
      <template v-if="events && !eventFailure"><p v-if="events.list.length === 0" class="orders-empty">当前页暂无订单事件。</p>
        <ol v-else class="orders-events"><li v-for="event in events.list" :key="event.eventId">
          <span>{{ eventTypeLabel(event.type) }} <span v-if="event.simulation" class="orders-badge">模拟</span></span>
          <span>员工 {{ event.actorId }} · {{ displayTime(event.createdAtMs) }}（北京时间）</span>
        </li></ol>
        <nav class="orders-pagination" aria-label="订单事件分页"><span>共 {{ events.total }} 条，第 {{ events.pageNo }} 页</span>
          <button type="button" :disabled="events.pageNo <= 1" @click="loadEvents(events.pageNo - 1)">上一页</button>
          <button type="button" :disabled="events.pageNo * events.pageSize >= events.total || events.pageNo >= 1000000" @click="loadEvents(events.pageNo + 1)">下一页</button>
        </nav></template>
    </div>
  </section>
</template>
