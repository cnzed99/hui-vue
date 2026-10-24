<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, RouterLink } from 'vue-router'
import { hasPermission } from '@/directives/permission/hasPermi'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { getOrder } from '../orders/api'
import { displayTime, paymentStatusLabel, printStatusLabel } from '../orders/view'
import { classifyWorkflowError, configuredProfile, createOrder, createQuote, getFileMetadata, getPayment,
  parseCopies, simulatePayment, uploadPdf } from './api'
import type { DemoQuote, FileMetadata, PaymentReceipt, QuoteInput, WorkflowFailure, WorkflowOrder } from './api'
import './workflow.css'

defineOptions({ name: 'PrintDemoWorkflow' })
const user = useUserStore()
const context = usePrintContextStore()
const profileId = configuredProfile(import.meta.env.VITE_PRINT_DEMO_PRICE_PROFILE_ID)
const permitted = (permission: string) => hasPermission([permission])
const accessReady = computed(() => context.status === 'ready' && context.context?.userId === user.getUser.id)
const accessKey = computed(() => [accessReady.value, user.getUser.id, context.context?.tenantId, context.context?.storeId,
  context.context?.userId, context.status, ...['print:file:upload', 'print:file:query', 'print:quote:create',
    'print:order:create', 'print:order:query', 'print:payment:simulate'].map(permitted)].join(':'))
const selected = ref<File | null>(null)
const file = ref<FileMetadata | null>(null)
const side = ref<'SIMPLEX' | 'DUPLEX'>('SIMPLEX')
const copies = ref('1')
const quote = ref<DemoQuote | null>(null)
const order = ref<WorkflowOrder | null>(null)
const receipt = ref<PaymentReceipt | null>(null)
const paymentConfirmed = ref(false)
const busy = ref('')
const failure = ref<WorkflowFailure | null>(null)
const pending = ref('')
const now = ref(Date.now())
let generation = 0
let uploadOperation: { key: string; file: File } | null = null
let quoteOperation: { key: string; input: QuoteInput } | null = null
let orderOperation: { key: string; quoteId: string } | null = null
let paymentOperation: { key: string; orderId: string } | null = null
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
const quoteExpired = computed(() => !!quote.value && (quote.value.status === 'EXPIRED' || quote.value.snapshot.expiresAtMs <= now.value))
const fileReady = computed(() => file.value?.status === 'READY' && file.value.contentAvailable &&
  Date.parse(file.value.expiresAt) > now.value)
const locked = computed(() => !!busy.value || !!pending.value || !!order.value)
const canUpload = computed(() => accessReady.value && permitted('print:file:upload') && !!selected.value && !busy.value && !file.value)
const canQuote = computed(() => accessReady.value && permitted('print:quote:create') && !!profileId &&
  !busy.value && !order.value && (pending.value === 'quote' || (!pending.value && fileReady.value && parseCopies(copies.value) !== null && (!quote.value || quoteExpired.value))))
const canOrder = computed(() => accessReady.value && permitted('print:order:create') && !!quote.value &&
  !order.value && !busy.value && (pending.value === 'order' || (!pending.value && !quoteExpired.value)))
const canPay = computed(() => accessReady.value && permitted('print:payment:simulate') && !!order.value &&
  order.value.paymentState === 'UNPAID' && !receipt.value && paymentConfirmed.value && !busy.value && (!pending.value || pending.value === 'payment'))
const fileLabels: Record<string, string> = { UPLOADED: '已上传，等待解析', PROCESSING: '正在解析', READY: '解析完成', FAILED: '解析失败', DELETED: '已删除或过期' }
const pendingLabel = computed(() => ({ upload: '上传', quote: '报价', order: '下单', payment: '模拟收款' } as Record<string, string>)[pending.value] || '操作')
const fileStatus = computed(() => file.value ? fileLabels[file.value.status] || `未知文件状态（${file.value.status}）` : '')
const parserFailureLabels: Record<string, string> = { ENCRYPTED: 'PDF 已加密', MALFORMED: 'PDF 格式损坏', ZERO_PAGES: 'PDF 没有有效页面',
  TOO_MANY_PAGES: 'PDF 超出允许页数', EXPIRED: '文件已过期', PROCESSING_RETRY_LIMIT: '解析未完成', FILE_MISSING: '文件不可用' }
const fileFailure = computed(() => file.value?.failureCode ? parserFailureLabels[file.value.failureCode] || `解析未完成（${file.value.failureCode}）` : '')
const clear = () => {
  generation++; selected.value = null; file.value = null; quote.value = null; order.value = null; receipt.value = null
  failure.value = null; busy.value = ''; pending.value = ''; paymentConfirmed.value = false; side.value = 'SIMPLEX'; copies.value = '1'
  uploadOperation = null; quoteOperation = null; orderOperation = null; paymentOperation = null
}
watch(accessKey, clear, { flush: 'sync' })
const choose = (event: Event) => {
  if (locked.value) return
  const input = event.target as HTMLInputElement
  const chosen = input.files?.[0] || null
  clear(); selected.value = chosen
  if (chosen && (!/\.pdf$/i.test(chosen.name) || chosen.size === 0 || chosen.size > 2 * 1024 * 1024)) {
    selected.value = null
    failure.value = { kind: 'invalid-input', title: '请选择有效 PDF', message: '演示限单个 PDF，最大 2 MiB。页数由服务端解析，上限 20 页。', uncertain: false }
  }
  input.value = ''
}
const settingsChanged = () => {
  if (locked.value) return
  quote.value = null; quoteOperation = null; orderOperation = null; failure.value = null
}
const execute = async (name: string, writing: boolean, action: () => Promise<void>) => {
  if (busy.value || !accessReady.value) return
  const current = generation
  busy.value = name; failure.value = null
  try { await action() }
  catch (error) {
    if (current !== generation) return
    const result = classifyWorkflowError(error)
    failure.value = result
    if (writing && result.uncertain) pending.value = name
    else if (writing && pending.value === name) pending.value = ''
    if (result.kind === 'quote-expired') {
      if (quote.value) quote.value.status = 'EXPIRED'
      if (name === 'quote') quoteOperation = null
    }
  } finally { if (current === generation) busy.value = '' }
}
const upload = () => {
  if (!canUpload.value || (pending.value && pending.value !== 'upload')) return
  void execute('upload', true, async () => {
    const current = generation
    if (!uploadOperation) uploadOperation = { key: crypto.randomUUID(), file: selected.value! }
    const result = await uploadPdf(uploadOperation.file, uploadOperation.key)
    if (current !== generation) return
    file.value = result; pending.value = ''
  })
}
const refreshFile = () => {
  if (!file.value || busy.value || !permitted('print:file:query')) return
  void execute('file-read', false, async () => {
    const current = generation
    const result = await getFileMetadata(file.value!.id)
    if (current !== generation) return
    file.value = result
    if (result.status !== 'READY' || !result.contentAvailable) { quote.value = null; quoteOperation = null }
  })
}
const askQuote = () => {
  if (!canQuote.value) return
  void execute('quote', true, async () => {
    const current = generation
    if (!quoteOperation || (quoteExpired.value && !pending.value)) {
      quoteOperation = { key: crypto.randomUUID(), input: { fileId: file.value!.id, priceProfileId: profileId!, copies: parseCopies(copies.value)!, side: side.value } }
    }
    const result = await createQuote(quoteOperation.input, quoteOperation.key)
    if (current !== generation) return
    quote.value = result; pending.value = ''
  })
}
const placeOrder = () => {
  if (!canOrder.value) return
  void execute('order', true, async () => {
    const current = generation
    if (!orderOperation) orderOperation = { key: crypto.randomUUID(), quoteId: quote.value!.id }
    const result = await createOrder(orderOperation.quoteId, orderOperation.key)
    if (current !== generation) return
    order.value = result; pending.value = ''
  })
}
const refreshOrder = () => {
  if (!order.value || busy.value || !permitted('print:order:query')) return
  void execute('order-read', false, async () => {
    const current = generation
    const result = await getOrder(order.value!.id)
    if (current === generation) order.value = result
  })
}
const pay = () => {
  if (!canPay.value) return
  void execute('payment', true, async () => {
    const current = generation
    if (!paymentOperation) paymentOperation = { key: crypto.randomUUID(), orderId: order.value!.id }
    const result = await simulatePayment(paymentOperation.orderId, paymentOperation.key)
    if (current !== generation) return
    receipt.value = result; pending.value = ''
  })
}
const readPayment = () => {
  if (!order.value || busy.value || !permitted('print:order:query')) return
  void execute('payment-read', false, async () => {
    const current = generation
    const result = await getPayment(order.value!.id)
    if (current !== generation) return
    receipt.value = result; if (pending.value === 'payment') pending.value = ''
  })
}
const reloadContext = () => { void context.load(user.getUser.id) }
const beforeUnload = (event: BeforeUnloadEvent) => {
  if (pending.value || busy.value) { event.preventDefault(); event.returnValue = '' }
}
// Preserve a pending operation in this tab instead of silently abandoning its key.
onBeforeRouteLeave(() => !pending.value && !busy.value)
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { generation++; window.clearInterval(timer); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <section class="workflow" data-testid="demo-workflow" :aria-busy="!!busy">
    <header class="workflow-title"><h1>后台演示流程 <span class="workflow-badge">模拟</span></h1><RouterLink to="/print/workbench">返回工作台</RouterLink></header>
    <div class="workflow-notice">仅供后台演示：单 PDF，最大 2 MiB / 20 页，文件保留 3600 秒。正式经营参数待定。模拟收款不产生真实付款；排队不代表已打印。</div>
    <div v-if="context.failure" class="workflow-card" role="alert"><h2>{{ context.failure.title }}</h2><p>{{ context.failure.message }}</p><button @click="reloadContext">重新加载门店</button></div>
    <div v-else-if="!accessReady" class="workflow-card" role="status">正在确认当前账号与门店…</div>
    <template v-else>
      <div v-if="failure" class="workflow-error" role="alert" :data-error-kind="failure.kind"><strong>{{ failure.title }}</strong><p>{{ failure.message }}</p></div>
      <div v-if="pending" class="workflow-warning" role="alert">本次{{ pendingLabel }}结果待确认。请保留本页，点击对应“确认本次结果”使用原操作键；不要刷新或另开一次操作。</div>
      <div class="workflow-card">
        <h2>1. 上传 PDF 并解析</h2>
        <p>页数以服务端解析结果为准；保留期限以服务端返回时间为准。</p>
        <label class="workflow-file">选择 PDF <input data-testid="workflow-file-input" type="file" accept=".pdf,application/pdf" :disabled="locked || !permitted('print:file:upload')" @change="choose" /></label>
        <p v-if="!permitted('print:file:upload')" class="workflow-muted">当前账号没有上传权限。</p>
        <p v-if="selected">待上传：{{ selected.name }}</p>
        <button data-testid="workflow-upload" type="button" :disabled="!canUpload || (!!pending && pending !== 'upload')" @click="upload">{{ busy === 'upload' ? '正在上传…' : pending === 'upload' ? '确认本次上传结果' : '上传 PDF' }}</button>
        <template v-if="file">
          <dl class="workflow-facts"><div><dt>文件名</dt><dd>{{ file.displayName }}</dd></div><div><dt>解析状态</dt><dd data-testid="workflow-file-status">{{ fileStatus }}</dd></div><div v-if="file.pageCount !== null"><dt>服务端页数</dt><dd>{{ file.pageCount }} 页</dd></div><div><dt>保留至</dt><dd>{{ displayTime(Date.parse(file.expiresAt)) }}（北京时间）</dd></div></dl>
          <p v-if="fileFailure" role="alert">{{ fileFailure }}</p>
          <p v-if="['UPLOADED', 'PROCESSING'].includes(file.status)" role="status">文件已接收，等待服务端解析。请手动刷新状态后继续。</p>
          <button data-testid="workflow-refresh-file" type="button" :disabled="!!busy || !permitted('print:file:query') || !!pending" @click="refreshFile">{{ busy === 'file-read' ? '正在刷新…' : '刷新解析状态' }}</button>
          <p v-if="!permitted('print:file:query')" class="workflow-muted">当前账号没有读取文件状态的权限，无法继续确认页数。</p>
        </template>
      </div>
      <div class="workflow-card">
        <h2>2. 选择打印方式并获取服务端报价</h2>
        <p v-if="!profileId" class="workflow-warning" role="status">尚未配置有效的演示测试价格 UUID，报价已禁用。请管理员配置真实 DEMO 测试价格记录。</p>
        <p v-else class="workflow-muted">价格标签：测试价。金额由服务端真实价格版本计算。</p>
        <div class="workflow-fields"><label>打印面 <select data-testid="workflow-side" v-model="side" :disabled="locked" @change="settingsChanged"><option value="SIMPLEX">单面</option><option value="DUPLEX">双面</option></select></label><label>份数 <input data-testid="workflow-copies" v-model="copies" type="text" inputmode="numeric" :disabled="locked" @input="settingsChanged" /></label></div>
        <p v-if="parseCopies(copies) === null" role="alert">份数须为不超过浏览器安全整数范围的正整数。</p>
        <button data-testid="workflow-create-quote" type="button" :disabled="!canQuote" @click="askQuote">{{ busy === 'quote' ? '正在请求报价…' : pending === 'quote' ? '确认本次报价结果' : quoteExpired ? '重新获取报价' : '获取服务端报价' }}</button>
        <p v-if="!permitted('print:quote:create')" class="workflow-muted">当前账号没有创建报价权限。</p>
        <template v-if="quote"><dl class="workflow-facts"><div><dt>服务端金额</dt><dd class="workflow-money" data-testid="workflow-quote-amount">{{ quote.snapshot.totalAmount }} 元</dd></div><div><dt>每份页数 / 份数</dt><dd>{{ quote.snapshot.pagesPerCopy }} 页 / {{ quote.snapshot.copies }} 份</dd></div><div><dt>服务端纸张</dt><dd>{{ quote.snapshot.totalSheets }} 张</dd></div><div><dt>测试价格版本</dt><dd>{{ quote.snapshot.priceProfileKey }} / {{ quote.snapshot.priceProfileVersion }}</dd></div><div><dt>有效至</dt><dd>{{ displayTime(quote.snapshot.expiresAtMs) }}（北京时间）</dd></div></dl><p v-if="quoteExpired" class="workflow-warning" role="alert">报价已到期，请重新获取。最终有效性仍由服务端检查。</p></template>
      </div>
      <div class="workflow-card">
        <h2>3. 创建后台演示订单</h2><p>仅提交当前报价标识；订单金额来自服务端报价快照。</p>
        <button data-testid="workflow-create-order" type="button" :disabled="!canOrder" @click="placeOrder">{{ busy === 'order' ? '正在创建…' : pending === 'order' ? '确认本次下单结果' : '创建演示订单' }}</button>
        <p v-if="!permitted('print:order:create')" class="workflow-muted">当前账号没有创建订单权限。</p>
        <dl v-if="order" class="workflow-facts"><div><dt>订单号</dt><dd>{{ order.orderNo }}</dd></div><div><dt>订单金额</dt><dd class="workflow-money">{{ order.totalAmount }} 元</dd></div><div><dt>支付状态</dt><dd>{{ paymentStatusLabel(order.paymentState) }}</dd></div><div><dt>打印状态</dt><dd>{{ printStatusLabel(order.printState) }}</dd></div></dl>
        <RouterLink data-testid="workflow-detail-link" v-if="order && permitted('print:order:query')" :to="`/print/demo-orders/${order.id}`">打开已有订单详情</RouterLink>
      </div>
      <div class="workflow-card">
        <h2>4. 显式模拟收款并确认排队</h2><p>这会记录模拟收款并创建持久化排队任务，不产生真实收付款。</p>
        <label class="workflow-confirm"><input data-testid="workflow-payment-confirm" v-model="paymentConfirmed" type="checkbox" :disabled="!!busy || !!receipt" />我确认执行本订单的模拟收款</label>
        <div class="workflow-actions"><button data-testid="workflow-simulate-payment" type="button" :disabled="!canPay" @click="pay">{{ busy === 'payment' ? '正在记录模拟收款…' : pending === 'payment' ? '确认本次模拟收款结果' : '模拟收款' }}</button><button data-testid="workflow-read-payment" type="button" :disabled="!order || !!busy || !permitted('print:order:query') || (!!pending && pending !== 'payment')" @click="readPayment">读取已有模拟收款记录</button><button data-testid="workflow-refresh-order" type="button" :disabled="!order || !!busy || !permitted('print:order:query') || (!!pending && pending !== 'payment')" @click="refreshOrder">刷新订单状态</button></div>
        <p v-if="!permitted('print:payment:simulate')" class="workflow-muted">当前账号没有模拟收款权限。</p>
        <template v-if="receipt"><dl data-testid="workflow-receipt" class="workflow-facts"><div><dt>收款结果</dt><dd>模拟收款成功</dd></div><div><dt>模拟金额</dt><dd class="workflow-money">{{ receipt.amount }} 元</dd></div><div><dt>记录时间</dt><dd>{{ displayTime(receipt.createdAtMs) }}（北京时间）</dd></div></dl><p>持久化任务标识：</p><ul><li v-for="id in receipt.jobIds" :key="id" class="workflow-id">{{ id }}</li></ul><p class="workflow-warning">模拟收款记录包含排队任务标识。请刷新订单确认当前排队状态。终端领取与回执尚未实现，不能据此判断已打印。</p></template>
        <p v-if="order?.printState === 'QUEUED'" data-testid="workflow-queue">等待打印（排队中）。终端回执尚未实现。</p>
      </div>
      <div class="workflow-footer"><button data-testid="workflow-reset" type="button" :disabled="!!busy || !!pending" @click="clear">开始另一次演示</button><RouterLink v-if="permitted('print:order:query') && !pending && !busy" to="/print/demo-orders">查看演示订单列表</RouterLink><span class="workflow-muted">取消、退款及补打模块尚未实现。</span></div>
    </template>
  </section>
</template>
