<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { createDemoTextFile, getDemoTextFile } from '@/api/demoTextFiles'
import type { TextFileFailure, TextFileResult } from '@/api/demoTextFiles'
import type { DemoFileMetadata } from '@/api/demoFiles'
import { createDraft, saveDraft } from '@/domain/printDraft'

const content = ref('')
const styles = ['14px', 'H1', 'H2', 'H3']
const state = ref<'idle' | 'submitting' | 'processing' | 'ready' | 'failed'>('idle')
const failureText = ref('')
const parsedPages = ref(0)
const count = computed(() => Array.from(normalize(content.value)).length)
const busy = computed(() => state.value === 'submitting' || state.value === 'processing')
const canSubmit = computed(() => !busy.value && !validationMessage(content.value))
const buttonText = computed(() => state.value === 'submitting' ? '正在生成 PDF…'
  : state.value === 'processing' ? '正在安全解析…' : state.value === 'failed' ? '重试生成并解析' : '生成文件并设置打印')
let generation = 0
let alive = false
let attempt: { snapshot: string; key: string; context: string; file: DemoFileMetadata | null } | null = null

onShow(() => { alive = true })
onHide(invalidateHidden)
onUnload(invalidateHidden)
watch(content, () => {
  if (busy.value) return
  generation += 1
  state.value = 'idle'
  failureText.value = ''
  parsedPages.value = 0
})

function normalize(value: string) { return value.replace(/\r\n?/g, '\n').replace(/\t/g, '    ') }
function validationMessage(value: string) {
  const normalized = normalize(value)
  if (!normalized.trim()) return '请输入需要打印的文字。'
  const points = Array.from(normalized)
  if (points.length > 10000) return '文字展开后超过1万字，请删减后重试。'
  let bytes = 0
  for (const point of points) {
    const cp = point.codePointAt(0) || 0
    if ((cp < 32 && cp !== 10) || (cp >= 0x7f && cp <= 0x9f) || (cp >= 0xd800 && cp <= 0xdfff)) return '文字包含无法打印的控制字符，请检查后重试。'
    bytes += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4
  }
  return bytes > 60 * 1024 ? '文字数据超过演示限制，请删减后重试。' : ''
}
function context(session: DemoSession) { return `${session.sessionId}:${session.principalId}` }
function storedContext() {
  const stored = uni.getStorageSync('miniapp-demo-session-v1') as Partial<DemoSession> | null
  return stored && typeof stored.sessionId === 'string' && typeof stored.principalId === 'string'
    && Number(stored.expiresAtMs) > Date.now() ? `${stored.sessionId}:${stored.principalId}` : null
}
function isCurrent(request: number, expected: string, session: DemoSession | null) {
  return alive && generation === request && session !== null && session.expiresAtMs > Date.now()
    && context(session) === expected && storedContext() === expected
}
function invalidateHidden() {
  alive = false
  generation += 1
  attempt = null
  state.value = 'idle'
  content.value = ''
  failureText.value = ''
  parsedPages.value = 0
}
function fail(request: number, message: string) {
  if (!alive || generation !== request) return
  state.value = 'failed'
  failureText.value = message
}
function failureMessage(reason: TextFileFailure) {
  const messages: Record<TextFileFailure, string> = {
    unavailable: '文字生成服务暂时不可用，请稍后重试。',
    network: '网络未能确认结果；保持文字不变重试会复用同一次提交。',
    invalid: '文字格式不符合要求，请检查内容后重试。',
    'too-large': '文字排版超过20页或文件限制，请删减内容后重试。',
    unsupported: '当前字体不支持部分字符，请移除生僻字符或表情后重试。',
    busy: '文字生成服务繁忙，请稍后保持原内容重试。',
    conflict: '此次提交与原内容不一致，请修改文字后重新提交。',
    session: '演示会话已变化，请重新进入页面后提交。',
    'file-limit': '当前演示会话已达到3个文件限制，请等待文件过期后重试。',
    capacity: '服务器暂存空间不足，请稍后重试。',
    'not-found': '文字生成入口或暂存文件不可用，请稍后重试。'
  }
  return messages[reason]
}

function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/home/index' })
}

async function submit() {
  if (!alive || busy.value) return
  const invalid = validationMessage(content.value)
  if (invalid) { failureText.value = invalid; state.value = 'failed'; return }
  const snapshot = normalize(content.value)
  const request = ++generation
  state.value = 'submitting'
  failureText.value = ''
  parsedPages.value = 0
  const session = await ensureDemoSession()
  if (!alive || generation !== request) return
  if (!session || storedContext() !== context(session)) {
    attempt = null
    fail(request, '安全演示会话不可用或已变化，请重新进入页面后提交。')
    return
  }
  const expected = context(session)
  if (!attempt || attempt.snapshot !== snapshot || attempt.context !== expected) {
    attempt = { snapshot, context: expected, key: `miniapp-text-${Date.now()}-${Math.random().toString(36).slice(2, 14)}`, file: null }
  }
  const submission = attempt
  let file: DemoFileMetadata
  if (submission.file) file = submission.file
  else {
    const generated = await createDemoTextFile(session, submission.snapshot, submission.key)
    const confirmed = await ensureDemoSession()
    if (!isCurrent(request, expected, confirmed)) { fail(request, '演示会话已变化，本次结果未载入；请重新进入页面。'); return }
    if (generated.kind !== 'ok') { fail(request, failureMessage(generated.reason)); return }
    file = generated.file
    submission.file = file
  }
  state.value = 'processing'
  for (let poll = 0; poll < 24; poll += 1) {
    const confirmed = await ensureDemoSession()
    if (!isCurrent(request, expected, confirmed)) { fail(request, '演示会话已变化，本次结果未载入；请重新进入页面。'); return }
    if (file.expiresAtMs <= Date.now() || file.status === 'DELETED') { fail(request, '暂存文件已过期，请修改文字后重新生成。'); return }
    if (file.status === 'FAILED') { fail(request, '服务端未能安全解析生成文件，请修改文字后重试。'); return }
    if (file.status === 'READY' && file.ready && file.pageCount) {
      state.value = 'ready'
      parsedPages.value = file.pageCount
      saveDraft(createDraft('SERVER_UPLOAD', file.displayName, file.sizeBytes, file.pageCount, file.id))
      uni.navigateTo({ url: '/pages/document-settings/index' })
      return
    }
    await new Promise(resolve => setTimeout(resolve, 750))
    if (!alive || generation !== request) return
    const loaded: TextFileResult = await getDemoTextFile(session, file.id)
    const afterRead = await ensureDemoSession()
    if (!isCurrent(request, expected, afterRead)) { fail(request, '演示会话已变化，本次结果未载入；请重新进入页面。'); return }
    if (loaded.kind !== 'ok') { fail(request, failureMessage(loaded.reason)); return }
    file = loaded.file
    submission.file = file
  }
  fail(request, '解析结果暂未就绪；保持文字不变重试可继续查询同一文件。')
}

function showFormattingBoundary() {
  uni.showToast({ title: '排版工具待接入', icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>自定义文字</text><text class="spacer" /></view></view>
    <view class="toolbar"><view v-for="style in styles" :key="style" class="tool" @click="showFormattingBoundary">{{ style }}</view></view>
    <view class="format-boundary">排版工具仅还原入口，当前不改变文字样式</view>
    <textarea v-model="content" class="editor" :disabled="busy" :maxlength="20000" placeholder="点击输入内容..." />
    <view class="counter">文字字数：{{ count }}，字数限制在1万字以内</view>
    <view v-if="busy" class="result-status">{{ state === 'submitting' ? '正在生成受限 PDF，请保持页面打开。' : '文件已提交，正在由服务端安全解析页数。' }}</view>
    <view v-else-if="state === 'ready'" class="result-status">服务端解析完成：{{ parsedPages }}页</view>
    <view v-if="failureText" class="failure-status">{{ failureText }}</view>
    <button class="submit" :disabled="!canSubmit" @click="submit">{{ buttonText }}</button>
    <view class="boundary">点击后文字将发送到新系统，生成文件最多20页、2MB，暂存1小时。正文不保存到本机；离开页面会清空。后续可设置参数并创建模拟订单。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f7f7f7}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.toolbar{display:flex;gap:12rpx;padding:20rpx;background:#fff;border-bottom:1rpx solid #eee}.tool{min-width:84rpx;padding:14rpx 12rpx;color:#999;background:#f3f3f3;border-radius:8rpx;text-align:center;font-size:24rpx}.format-boundary{padding:12rpx 22rpx;color:#a77a50;background:#fff8ee;text-align:center;font-size:21rpx}.editor{box-sizing:border-box;width:calc(100% - 40rpx);height:520rpx;margin:20rpx;padding:24rpx;background:#fff;border:1rpx solid #eee;border-radius:10rpx;font-size:28rpx;line-height:44rpx}.counter{margin:0 28rpx;color:#888;font-size:22rpx}.submit{height:88rpx;margin:32rpx 22rpx 0;color:#fff;background:#ff9f3d;border-radius:10rpx;font-size:31rpx;line-height:88rpx}.submit[disabled]{color:#fff;background:#ffc58f}.boundary{margin:22rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
.result-status,.failure-status{margin:24rpx 28rpx 0;padding:20rpx;color:#42735c;background:#edf8f1;border-radius:10rpx;font-size:24rpx;line-height:38rpx}.failure-status{color:#a96729;background:#fff3e6}
</style>
