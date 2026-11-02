<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onShareAppMessage, onShow, onUnload } from '@dcloudio/uni-app'
import { createDemoLibraryFile, getDemoLibraryEntry, validLibraryId } from '@/api/demoLibrary'
import type { LibraryDetail } from '@/api/demoLibrary'
import { ensureDemoSession } from '@/api/demoSession'
import type { DemoSession } from '@/api/demoSession'
import { getDemoTextFile } from '@/api/demoTextFiles'
import type { TextFileFailure } from '@/api/demoTextFiles'
import type { DemoFileMetadata } from '@/api/demoFiles'
import { createDraft, saveDraft } from '@/domain/printDraft'

const entryId = ref('')
const detail = ref<LibraryDetail | null>(null)
const loading = ref(true)
const loadError = ref('')
const state = ref<'idle' | 'submitting' | 'processing' | 'ready' | 'failed' | 'terminal'>('idle')
const failureText = ref('')
const busy = computed(() => state.value === 'submitting' || state.value === 'processing')
const buttonText = computed(() => state.value === 'submitting' ? '正在生成…' : state.value === 'processing' ? '正在解析…'
  : state.value === 'failed' ? '继续查询/重试' : state.value === 'terminal' ? '重新开始' : '去打印')
let alive = false
let generation = 0
let loadGeneration = 0
let attempt: { entryId: string; version: string; key: string; context: string; file: DemoFileMetadata | null } | null = null

onLoad(query => { const id = query?.id; entryId.value = validLibraryId(id) ? id : '' })
onShow(() => { alive = true; loadDetail() })
onHide(hide)
onUnload(() => { hide(); attempt = null })

onShareAppMessage(() => ({
  title: detail.value?.entry.title || '原创演示文库',
  path: `/pages/library-detail/index?id=${encodeURIComponent(detail.value?.entry.id || '')}`
}))

function hide() {
  alive = false
  generation += 1
  loadGeneration += 1
  if (busy.value) { state.value = 'failed'; failureText.value = '操作已暂停，返回后可继续查询同一次提交。' }
}
async function loadDetail() {
  const request = ++loadGeneration
  loading.value = true
  loadError.value = ''
  if (!entryId.value) { loading.value = false; loadError.value = '文库条目地址无效。'; return }
  const result = await getDemoLibraryEntry(entryId.value)
  if (!alive || request !== loadGeneration) return
  loading.value = false
  if (result.kind !== 'ok') {
    detail.value = null
    loadError.value = result.reason === 'not-found' ? '该文库条目不存在。' : '文件预览暂时不可用，请重试。'
    return
  }
  if (attempt && (attempt.entryId !== result.data.entry.id || attempt.version !== result.data.entry.contentVersion)) {
    attempt = null
    state.value = 'idle'
    failureText.value = '文库内容版本已变化，请重新开始打印。'
  }
  detail.value = result.data
}
function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/library/index' })
}
function favorite() { uni.showToast({ title: '收藏将在微信身份接入后开放', icon: 'none', duration: 2500 }) }
function shareBoundary() { uni.showToast({ title: '微信分享待 AppID 配置及真机验证', icon: 'none', duration: 2500 }) }
function context(session: DemoSession) { return `${session.sessionId}:${session.principalId}` }
function storedContext() {
  const stored = uni.getStorageSync('miniapp-demo-session-v1') as Partial<DemoSession> | null
  return stored && typeof stored.sessionId === 'string' && typeof stored.principalId === 'string'
    && Number(stored.expiresAtMs) > Date.now() ? `${stored.sessionId}:${stored.principalId}` : null
}
function current(request: number, expected: string, session: DemoSession | null) {
  return alive && generation === request && session !== null && session.expiresAtMs > Date.now()
    && context(session) === expected && storedContext() === expected
}
function fail(request: number, message: string, terminal = false) {
  if (!alive || generation !== request) return
  state.value = terminal ? 'terminal' : 'failed'
  failureText.value = message
}
function failureMessage(reason: TextFileFailure) {
  const messages: Record<TextFileFailure, string> = {
    unavailable: '文件生成服务暂时不可用，请稍后重试。',
    network: '网络未能确认结果；重试会继续同一次提交。',
    invalid: '文库生成请求无效，请重新进入该条目。',
    'too-large': '文件超过演示限制，暂时无法打印。',
    unsupported: '文库排版包含暂不支持的字符。',
    busy: '服务繁忙，请稍后重试同一次提交。',
    conflict: '文库内容与原提交版本不一致，请重新开始。',
    session: '演示会话已变化，请重新进入该条目。',
    'file-limit': '当前演示会话已达到3个文件限制，请等待文件过期。',
    capacity: '服务器暂存空间不足，请稍后重试。',
    'not-found': '文库条目或暂存文件不可用，请重新进入页面。'
  }
  return messages[reason]
}
async function startPrint() {
  if (!alive || busy.value || !detail.value || loading.value) return
  if (state.value === 'terminal') {
    uni.showModal({ title: '重新开始打印', content: '上一次文件已结束。重新开始将生成一个新的暂存文件，是否继续？', success: result => {
      if (!result.confirm || !alive) return
      attempt = null
      state.value = 'idle'
      submit()
    } })
    return
  }
  await submit()
}
async function submit() {
  const entry = detail.value?.entry
  if (!alive || busy.value || !entry) return
  const request = ++generation
  state.value = 'submitting'
  failureText.value = ''
  const session = await ensureDemoSession()
  if (!alive || generation !== request) return
  if (!session || storedContext() !== context(session)) {
    attempt = null
    fail(request, '安全演示会话不可用，请重新进入页面。')
    return
  }
  const expected = context(session)
  if (attempt && attempt.context !== expected) {
    attempt = null
    fail(request, '演示会话已变化，本次没有沿用旧文件；请再次点击开始。')
    return
  }
  if (!attempt) attempt = {
    entryId: entry.id, version: entry.contentVersion, context: expected,
    key: `miniapp-library-${Date.now()}-${Math.random().toString(36).slice(2, 14)}`, file: null
  }
  const submission = attempt
  if (submission.entryId !== entry.id || submission.version !== entry.contentVersion) { fail(request, '文库版本已变化，请重新进入页面。', true); return }
  let file: DemoFileMetadata
  if (submission.file) file = submission.file
  else {
    const generated = await createDemoLibraryFile(session, submission.entryId, submission.key)
    const confirmed = await ensureDemoSession()
    if (!current(request, expected, confirmed)) { fail(request, '演示会话已变化，本次结果未载入。'); return }
    if (generated.kind !== 'ok') {
      fail(request, failureMessage(generated.reason), ['conflict', 'invalid', 'not-found', 'unsupported', 'too-large'].includes(generated.reason))
      return
    }
    file = generated.file
    submission.file = file
  }
  state.value = 'processing'
  for (let poll = 0; poll < 24; poll += 1) {
    const confirmed = await ensureDemoSession()
    if (!current(request, expected, confirmed)) { fail(request, '演示会话已变化，本次结果未载入。'); return }
    if (file.expiresAtMs <= Date.now() || file.status === 'DELETED') { fail(request, '暂存文件已过期，本次文件已经结束。', true); return }
    if (file.status === 'FAILED') { fail(request, '服务端未能安全解析此文件，本次文件已经结束。', true); return }
    if (file.status === 'READY' && file.ready) {
      if (file.pageCount !== entry.pages) { fail(request, '解析页数与文库预览不一致，已停止进入打印设置。', true); return }
      state.value = 'ready'
      saveDraft(createDraft('SERVER_UPLOAD', file.displayName, file.sizeBytes, file.pageCount, file.id))
      uni.navigateTo({ url: '/pages/document-settings/index' })
      return
    }
    await new Promise(resolve => setTimeout(resolve, 750))
    if (!alive || generation !== request) return
    const loaded = await getDemoTextFile(session, file.id)
    const afterRead = await ensureDemoSession()
    if (!current(request, expected, afterRead)) { fail(request, '演示会话已变化，本次结果未载入。'); return }
    if (loaded.kind !== 'ok') { fail(request, failureMessage(loaded.reason), loaded.reason === 'not-found'); return }
    file = loaded.file
    submission.file = file
  }
  fail(request, '解析结果暂未就绪；重试将继续查询同一个文件。')
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>文件预览</text><text class="spacer" /></view></view>
    <view v-if="loading" class="empty">正在加载文件预览…</view>
    <view v-else-if="loadError" class="empty"><text>{{ loadError }}</text><button class="retry" @click="loadDetail">重新加载</button></view>
    <template v-else-if="detail">
      <view class="file-info"><text class="file-title">{{ detail.entry.title }}</text><text class="file-note">原创演示排版预览 · 共{{ detail.entry.pages }}页</text></view>
      <view class="pages">
        <view v-for="page in detail.previewPages" :key="page.pageNo" class="paper">
          <view class="paper-lines"><text v-for="(line, index) in page.lines" :key="index" class="paper-line">{{ line || ' ' }}</text></view>
          <text class="page-number">第 {{ page.pageNo }} 页</text>
        </view>
      </view>
      <view v-if="busy" class="result-status">{{ state === 'submitting' ? '正在生成当前演示会话的私有 PDF…' : '服务端正在安全解析文件，请稍候。' }}</view>
      <view v-if="failureText" class="failure-status">{{ failureText }}</view>
      <view class="boundary">文件最多2MB、20页，暂存1小时。后续使用服务端报价和模拟订单。收藏、微信分享等待正式接入。</view>
      <view class="bottom-actions">
        <view class="small-action" @click="favorite"><text class="action-icon">☆</text><text>收藏</text></view>
        <!-- #ifdef MP-WEIXIN -->
        <button class="small-action share-button" open-type="share"><text class="action-icon">↗</text><text>分享</text></button>
        <!-- #endif -->
        <!-- #ifndef MP-WEIXIN -->
        <view class="small-action" @click="shareBoundary"><text class="action-icon">↗</text><text>分享</text></view>
        <!-- #endif -->
        <button class="print-button" :disabled="busy" @click="startPrint">{{ buttonText }}</button>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:calc(150rpx + env(safe-area-inset-bottom));background:#e9e9e9}.header{position:sticky;top:0;z-index:3;color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:80rpx;padding:0 26rpx;font-size:32rpx}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.file-info{display:flex;flex-direction:column;padding:22rpx 26rpx;background:#fff}.file-title{color:#444;font-size:28rpx;font-weight:600}.file-note{margin-top:8rpx;color:#a67c4b;font-size:21rpx}.pages{padding:18rpx}.paper{position:relative;min-height:910rpx;box-sizing:border-box;margin-bottom:18rpx;padding:40rpx 32rpx 64rpx;background:#fff;box-shadow:0 3rpx 12rpx rgba(0,0,0,.1)}.paper-lines{display:flex;flex-direction:column}.paper-line{display:block;min-height:19rpx;color:#333;font-family:monospace;font-size:16rpx;line-height:19rpx;white-space:pre-wrap;overflow-wrap:anywhere}.page-number{position:absolute;right:18rpx;bottom:18rpx;padding:6rpx 16rpx;color:#fff;background:rgba(0,0,0,.45);border-radius:6rpx;font-size:22rpx}.bottom-actions{position:fixed;z-index:5;right:0;bottom:0;left:0;display:flex;align-items:center;gap:16rpx;padding:16rpx 22rpx calc(16rpx + env(safe-area-inset-bottom));background:#fff;box-shadow:0 -2rpx 10rpx rgba(0,0,0,.06)}.small-action{display:flex;flex:0 0 82rpx;align-items:center;justify-content:center;flex-direction:column;color:#777;font-size:22rpx;line-height:30rpx}.action-icon{color:#999;font-size:38rpx;line-height:42rpx}.share-button{height:auto;margin:0;padding:0;border:0;border-radius:0;background:#fff;font-weight:400}.share-button::after{border:0}.print-button{flex:1;height:80rpx;margin:0;color:#fff;background:#ff9f3d;border-radius:42rpx;font-size:30rpx;line-height:80rpx}.print-button[disabled]{background:#ffc68f;color:#fff}.empty{padding:120rpx 24rpx;color:#888;text-align:center;font-size:27rpx}.retry{width:220rpx;margin:24rpx auto;color:#fff;background:#ff9f3d;border-radius:40rpx;font-size:25rpx}.result-status,.failure-status{margin:18rpx;padding:20rpx;color:#39785c;background:#eef8f1;border-radius:10rpx;font-size:24rpx;line-height:36rpx}.failure-status{color:#a96729;background:#fff4e7}.boundary{padding:10rpx 28rpx 24rpx;color:#888;font-size:21rpx;line-height:34rpx}
</style>
