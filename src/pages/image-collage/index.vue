<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession } from '@/api/demoSession'
import { waitForDemoFile, type DemoFileMetadata } from '@/api/demoFiles'
import { newCollageIdempotencyKey, uploadDemoImageCollage, type CollageUploadHandle } from '@/api/demoImageCollages'
import { createDraft, saveDraft } from '@/domain/printDraft'

type SourceLabel = '拍照' | '手机相册' | '微信图片'
type PageState = 'selecting' | 'ready' | 'uploading' | 'processing' | 'complete' | 'failed'
interface SelectedImage { path: string; name: string; size: number }

const MAX_COUNT = 9
const MAX_FILE_BYTES = 2 * 1024 * 1024
const MAX_TOTAL_BYTES = 12 * 1024 * 1024
const source = ref<SourceLabel>('手机相册')
const images = ref<SelectedImage[]>([])
const state = ref<PageState>('selecting')
const progress = ref(0)
const failureText = ref('')
const sessionState = ref<'checking' | 'ready' | 'unavailable'>('checking')
const generatedFile = ref<DemoFileMetadata | null>(null)
const submittedToken = ref('')
const activeSessionToken = ref('')
const selectionSessionToken = ref('')
const idempotencyKey = ref('')
const generation = ref(0)
const mounted = ref(true)
const continuing = ref(false)
let uploadHandle: CollageUploadHandle | null = null
let pickerRequest = 0

const totalBytes = computed(() => images.value.reduce((sum, image) => sum + image.size, 0))
const canSubmit = computed(() => images.value.length > 0 && images.value.length <= MAX_COUNT
  && totalBytes.value <= MAX_TOTAL_BYTES && state.value !== 'uploading' && state.value !== 'processing'
  && !continuing.value)
const totalSizeText = computed(() => `${(totalBytes.value / 1024 / 1024).toFixed(2)} MB`)

onLoad((query) => {
  const selected = decodeURIComponent(String(query?.source || '手机相册'))
  if (selected === '拍照' || selected === '手机相册' || selected === '微信图片') source.value = selected
  void initialize()
})
onUnload(disposePage)
onUnmounted(disposePage)

async function initialize() {
  const session = await ensureDemoSession()
  if (!mounted.value) return
  activeSessionToken.value = session?.accessToken || ''
  sessionState.value = session ? 'ready' : 'unavailable'
  await nextTick()
  if (mounted.value && session && images.value.length === 0) chooseImages()
}

function goBack() { uni.navigateBack() }

function chooseImages() {
  if (sessionState.value !== 'ready' || !activeSessionToken.value
    || continuing.value || state.value === 'uploading' || state.value === 'processing'
    || images.value.length >= MAX_COUNT) return
  const count = MAX_COUNT - images.value.length
  const pickerGeneration = generation.value
  const pickerToken = activeSessionToken.value
  const currentPicker = ++pickerRequest
  const accept = (value: unknown) => {
    if (!mounted.value || continuing.value || generation.value !== pickerGeneration
      || activeSessionToken.value !== pickerToken || currentPicker !== pickerRequest) return
    acceptSelection(value, pickerToken)
  }
  // #ifdef MP-WEIXIN
  if (source.value === '微信图片') {
    uni.chooseMessageFile({ count, type: 'image', extension: ['jpg', 'jpeg', 'png'], success: accept })
  } else {
    uni.chooseImage({ count, sourceType: [source.value === '拍照' ? 'camera' : 'album'], success: accept })
  }
  // #endif
  // #ifdef H5
  uni.chooseFile({ count, type: 'image', extension: ['jpg', 'jpeg', 'png'], success: accept })
  // #endif
}

function acceptSelection(value: unknown, pickerToken: string) {
  if (!mounted.value || !pickerToken || activeSessionToken.value !== pickerToken) return
  const result = value as {
    tempFiles?: Array<{ path?: string; size?: number; name?: string }>
    tempFilePaths?: string[]
  }
  const candidates = result.tempFiles?.length
    ? result.tempFiles
    : (result.tempFilePaths || []).map(path => ({ path, size: 0, name: '' }))
  const accepted: SelectedImage[] = []
  for (const candidate of candidates) {
    const path = String(candidate.path || '')
    if (!path) continue
    const inferred = decodeURIComponent(path.split('/').pop()?.split('?')[0] || 'image.jpg')
    const name = String(candidate.name || inferred)
    const size = Number(candidate.size || 0)
    if (!/\.(jpe?g|png)$/i.test(name)) {
      uni.showToast({ title: '仅支持 JPG、JPEG 或 PNG', icon: 'none' }); continue
    }
    if (!Number.isFinite(size) || size < 0 || size > MAX_FILE_BYTES) {
      uni.showToast({ title: '单张图片不能超过 2MB', icon: 'none' }); continue
    }
    if (images.value.some(image => image.path === path) || accepted.some(image => image.path === path)) continue
    accepted.push({ path, name, size })
  }
  if (images.value.length + accepted.length > MAX_COUNT) accepted.splice(MAX_COUNT - images.value.length)
  const nextTotal = totalBytes.value + accepted.reduce((sum, image) => sum + image.size, 0)
  if (nextTotal > MAX_TOTAL_BYTES) {
    uni.showToast({ title: '所选图片合计不能超过 12MB', icon: 'none' }); return
  }
  if (accepted.length > 0) {
    if (!activeSessionToken.value
      || (selectionSessionToken.value && selectionSessionToken.value !== activeSessionToken.value)) {
      resetForSessionChange(activeSessionToken.value); return
    }
    invalidateSubmission()
    if (!selectionSessionToken.value) selectionSessionToken.value = activeSessionToken.value
    images.value.push(...accepted)
    state.value = 'ready'
  }
}

function removeImage(index: number) {
  if (index < 0 || index >= images.value.length) return
  invalidateSubmission()
  images.value.splice(index, 1)
  if (images.value.length === 0) selectionSessionToken.value = ''
  state.value = images.value.length ? 'ready' : 'selecting'
}

function invalidateSubmission() {
  generation.value += 1
  uploadHandle?.abort(); uploadHandle = null
  continuing.value = false
  idempotencyKey.value = ''
  generatedFile.value = null
  submittedToken.value = ''
  failureText.value = ''
  progress.value = 0
}

async function generateCollage() {
  if (!canSubmit.value || images.value.length < 1) return
  const currentGeneration = ++generation.value
  const selection = images.value.map(image => ({ ...image }))
  const selectionKey = selectionFingerprint(selection)
  const retrying = Boolean(idempotencyKey.value)
  const retryToken = retrying ? submittedToken.value : ''
  state.value = 'uploading'; failureText.value = ''; progress.value = 0
  const session = await ensureDemoSession()
  if (!isCurrentSelection(currentGeneration, selectionKey)) return
  if (!session) {
    sessionState.value = 'unavailable'; state.value = 'failed'
    failureText.value = '安全演示会话不可用，请稍后重试。'; return
  }
  activeSessionToken.value = session.accessToken
  if (!selectionSessionToken.value || session.accessToken !== selectionSessionToken.value
    || (retrying && (!retryToken || session.accessToken !== retryToken))) {
    resetForSessionChange(session.accessToken); return
  }
  sessionState.value = 'ready'
  if (!idempotencyKey.value) idempotencyKey.value = newCollageIdempotencyKey()
  const currentToken = session.accessToken
  submittedToken.value = currentToken
  const handle = uploadDemoImageCollage(session, selection.map(image => image.path), idempotencyKey.value,
    value => { if (isCurrent(currentGeneration, currentToken)) progress.value = value })
  uploadHandle = handle
  const uploaded = await handle.promise
  if (uploadHandle === handle) uploadHandle = null
  if (!isCurrent(currentGeneration, currentToken)) return
  if (!await confirmSession(currentGeneration, currentToken)) return
  if (!uploaded) {
    state.value = 'failed'; failureText.value = '上传或拼版失败，可使用原选择和原请求键重试。'; return
  }
  state.value = 'processing'
  const result = await waitForDemoFile(session, uploaded)
  if (!isCurrent(currentGeneration, currentToken)) return
  if (!await confirmSession(currentGeneration, currentToken)) return
  if (!result || result.status !== 'READY' || result.pageCount !== 1) {
    state.value = 'failed'
    failureText.value = result?.failureCode ? `服务端校验失败：${result.failureCode}` : '服务端校验超时，可使用原请求键重试。'
    return
  }
  generatedFile.value = result
  state.value = 'complete'
}

async function continueToSettings() {
  const file = generatedFile.value
  if (state.value !== 'complete' || !file || !mounted.value || continuing.value) return
  const currentGeneration = generation.value
  const currentToken = submittedToken.value
  const currentFileId = file.id
  continuing.value = true
  const session = await ensureDemoSession()
  if (!mounted.value || generation.value !== currentGeneration
    || state.value !== 'complete' || generatedFile.value?.id !== currentFileId) return
  if (!session || session.accessToken !== currentToken || submittedToken.value !== currentToken) {
    resetForSessionChange(session?.accessToken || ''); return
  }
  continuing.value = false
  saveDraft(createDraft('SERVER_UPLOAD', '图片拼版.pdf', file.sizeBytes, 1, file.id))
  uni.navigateTo({ url: '/pages/document-settings/index' })
}

function isCurrent(value: number, token: string) {
  return mounted.value && generation.value === value && submittedToken.value === token
}

function isCurrentSelection(value: number, fingerprint: string) {
  return mounted.value && generation.value === value && selectionFingerprint(images.value) === fingerprint
}

async function confirmSession(value: number, token: string) {
  const confirmed = await ensureDemoSession()
  if (!isCurrent(value, token)) return false
  if (!confirmed || confirmed.accessToken !== token) {
    resetForSessionChange(confirmed?.accessToken || ''); return false
  }
  return true
}

function selectionFingerprint(value: SelectedImage[]) {
  return JSON.stringify(value.map(image => [image.path, image.name, image.size]))
}

function resetForSessionChange(currentToken = '') {
  invalidateSubmission()
  pickerRequest += 1
  continuing.value = false
  images.value = []
  activeSessionToken.value = currentToken
  selectionSessionToken.value = ''
  sessionState.value = currentToken ? 'ready' : 'unavailable'
  state.value = 'failed'
  failureText.value = '会话已变化，请重新选择图片。'
}

function disposePage() {
  if (!mounted.value) return
  mounted.value = false
  generation.value += 1
  pickerRequest += 1
  continuing.value = false
  uploadHandle?.abort(); uploadHandle = null
  // uni tempFilePath is platform-owned; this page creates no browser object URL.
  images.value = []
}
</script>

<template>
  <view class="collage-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>图片拼版打印</text><text class="spacer" /></view></view>
    <view class="body">
      <view class="session-state" :class="sessionState">{{ sessionState === 'checking' ? '正在建立安全演示会话…' : sessionState === 'ready' ? '安全演示会话已建立' : '安全演示会话暂不可用' }}</view>
      <view class="summary"><view><text class="count">已选 {{ images.length }}/9 张</text><text class="size">{{ totalSizeText }}</text></view><button class="add" :disabled="images.length >= 9" @click="chooseImages">继续选择</button></view>
      <view v-if="images.length" class="grid">
        <view v-for="(image, index) in images" :key="image.path" class="tile">
          <image class="thumb" :src="image.path" mode="aspectFit" />
          <text class="order">{{ index + 1 }}</text><text class="remove" @click="removeImage(index)">×</text>
          <text class="name">{{ image.name }}</text>
        </view>
      </view>
      <view v-else class="empty" @click="chooseImages"><text class="empty-icon">拼</text><text>选择 1–9 张图片</text><text>按选择顺序生成单页 A4 固定网格</text></view>
      <view class="rule-card"><text>固定网格开发演示</text><text>完整显示、不裁切、不拉伸；透明区域铺白。</text><text>原图仅用于本次转换，不长期保存；生成 PDF 最多保留 1 小时。</text><text>选图后的原版精确布局尚未观察，本页不冒充完整复刻。</text></view>
      <view v-if="state === 'uploading'" class="status warning"><text>正在上传 {{ progress }}%</text><text>请保持页面打开，离开会取消本次请求。</text></view>
      <view v-else-if="state === 'processing'" class="status warning"><text>正在校验拼版 PDF</text><text>服务端确认单页和文件状态后才能继续。</text></view>
      <view v-else-if="state === 'complete'" class="status success"><text>拼版已完成</text><text>已生成 1 页 A4 PDF。</text></view>
      <view v-else-if="state === 'failed'" class="status warning"><text>本次未完成</text><text>{{ failureText }}</text></view>
      <button v-if="state === 'complete'" class="primary" :disabled="continuing" @click="continueToSettings">{{ continuing ? '正在校验会话…' : '继续设置打印参数' }}</button>
      <button v-else class="primary" :class="{ disabled: !canSubmit || sessionState !== 'ready' }" @click="generateCollage">{{ state === 'failed' && idempotencyKey ? '使用原请求键重试' : '生成拼版并设置打印' }}</button>
      <view class="limits"><text>JPG/PNG · 单张≤2MB</text><text>合计≤12MB</text></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.collage-page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.body{padding:26rpx 24rpx 80rpx}.session-state{padding:16rpx 20rpx;color:#8c6a3d;background:#fff7e9;border-radius:10rpx;text-align:center;font-size:21rpx}.session-state.ready{color:#26765c;background:#eaf9f3}.summary{display:flex;align-items:center;justify-content:space-between;margin-top:22rpx;padding:20rpx 22rpx;background:#fff;border-radius:14rpx}.summary>view{display:flex;flex-direction:column}.count{color:#222;font-size:28rpx;font-weight:700}.size{margin-top:7rpx;color:#999;font-size:21rpx}.add{height:64rpx;margin:0;padding:0 26rpx;color:#f48625;background:#fff5e8;border:1rpx solid #ffc78d;border-radius:32rpx;font-size:24rpx;line-height:62rpx}.grid{display:grid;margin-top:20rpx;grid-template-columns:repeat(3,1fr);gap:14rpx}.tile{position:relative;min-width:0;padding:8rpx;background:#fff;border-radius:12rpx}.thumb{width:100%;height:190rpx;background:#f2f2f2;border-radius:8rpx}.order,.remove{position:absolute;top:14rpx;display:flex;align-items:center;justify-content:center;width:38rpx;height:38rpx;color:#fff;background:rgba(255,145,48,.9);border-radius:50%;font-size:21rpx}.order{left:14rpx}.remove{right:14rpx;background:rgba(40,40,40,.7);font-size:31rpx}.name{display:block;padding:10rpx 4rpx 4rpx;overflow:hidden;color:#666;font-size:19rpx;text-overflow:ellipsis;white-space:nowrap}.empty{display:flex;align-items:center;justify-content:center;margin-top:22rpx;padding:76rpx 20rpx;flex-direction:column;gap:12rpx;color:#999;background:#fff;border:2rpx dashed #ffd0a3;border-radius:16rpx;font-size:23rpx}.empty-icon{display:flex;align-items:center;justify-content:center;width:84rpx;height:84rpx;color:#ff9332;background:#fff1e4;border-radius:50%;font-size:31rpx;font-weight:700}.rule-card{display:flex;margin-top:22rpx;padding:22rpx;flex-direction:column;gap:8rpx;color:#777;background:#fff;border-radius:14rpx;font-size:22rpx;line-height:34rpx}.rule-card text:first-child{color:#333;font-size:25rpx;font-weight:700}.status{display:flex;margin-top:20rpx;padding:18rpx;flex-direction:column;border-radius:10rpx;font-size:22rpx;line-height:34rpx}.status text:first-child{font-weight:700}.warning{color:#986719;background:#fff6df}.success{color:#237a5a;background:#eaf9f3}.primary{width:100%;height:84rpx;margin-top:24rpx;color:#fff;background:linear-gradient(90deg,#ff9736,#ffad45);border:0;border-radius:44rpx;font-size:29rpx;line-height:84rpx}.primary.disabled{color:#999;background:#e7e8ea}.limits{display:flex;justify-content:space-between;margin-top:22rpx;color:#999;font-size:21rpx}
</style>
