<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ensureDemoSession } from '@/api/demoSession'
import { uploadDemoImage, waitForDemoFile } from '@/api/demoFiles'
import { createDraft, saveDraft } from '@/domain/printDraft'

const IMAGE_SELECTION_KEY = 'miniapp-image-selection-v1'
type ImageState = 'selected' | 'uploading' | 'ready' | 'failed'
type SelectionPurpose = 'image-print' | 'document-copy'
interface StoredSelection { schemaVersion: 1; path: string; name: string; size: number; purpose?: SelectionPurpose }

const state = ref<ImageState>('selected')
const selection = ref<StoredSelection | null>(null)
const serverFileId = ref('')
const generatedSize = ref(0)
const failureText = ref('')
const sessionState = ref<'checking' | 'ready' | 'unavailable'>('checking')
const isDocumentCopy = computed(() => selection.value?.purpose === 'document-copy')
const pageTitle = computed(() => isDocumentCopy.value ? '文件复印' : '图片打印')
const sizeText = computed(() => {
  const bytes = state.value === 'ready' ? generatedSize.value : selection.value?.size || 0
  return bytes ? `${(bytes / 1024).toFixed(1)} KB` : '大小将在上传时校验'
})

onMounted(async () => {
  const stored = uni.getStorageSync(IMAGE_SELECTION_KEY) as Partial<StoredSelection> | null
  if (!stored || stored.schemaVersion !== 1 || typeof stored.path !== 'string' || !stored.path
    || typeof stored.name !== 'string' || !stored.name || !Number.isFinite(Number(stored.size)) || Number(stored.size) < 0) {
    failureText.value = '图片选择已失效，请返回重新选择。'
    state.value = 'failed'
  } else {
    selection.value = stored as StoredSelection
    const lower = stored.name.toLowerCase()
    if (!['.jpg', '.jpeg', '.png'].some(extension => lower.endsWith(extension))) {
      failureText.value = '仅支持 JPG、JPEG 或 PNG 图片。'
      state.value = 'failed'
    } else if (Number(stored.size) > 2 * 1024 * 1024) {
      failureText.value = '图片超过 2MB 演示限制。'
      state.value = 'failed'
    }
  }
  sessionState.value = await ensureDemoSession() ? 'ready' : 'unavailable'
})

function goBack() { uni.navigateBack() }

async function uploadSelected() {
  if (state.value !== 'selected' || !selection.value || failureText.value) return
  state.value = 'uploading'
  const session = await ensureDemoSession()
  if (!session) {
    sessionState.value = 'unavailable'
    failureText.value = '安全演示会话不可用，请稍后重试。'
    state.value = 'failed'
    return
  }
  const uploaded = await uploadDemoImage(session, selection.value.path)
  const result = uploaded ? await waitForDemoFile(session, uploaded) : null
  if (!result || result.status !== 'READY' || result.pageCount !== 1) {
    state.value = 'failed'
    failureText.value = result?.failureCode ? `服务端转换失败：${result.failureCode}` : '上传或转换超时，请返回重新选择。'
    return
  }
  serverFileId.value = result.id
  generatedSize.value = result.sizeBytes
  state.value = 'ready'
}

function continueToSettings() {
  if (state.value !== 'ready' || !selection.value || !serverFileId.value) return
  const base = selection.value.name.replace(/\.(jpe?g|png)$/i, '') || '图片打印'
  saveDraft(createDraft('SERVER_UPLOAD', `${base}.pdf`, generatedSize.value, 1, serverFileId.value))
  uni.removeStorageSync(IMAGE_SELECTION_KEY)
  uni.navigateTo({ url: '/pages/document-settings/index' })
}
</script>

<template>
  <view class="upload-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>{{ pageTitle }}</text><text class="spacer" /></view></view>
    <view class="body">
      <view class="session-state" :class="sessionState">
        {{ sessionState === 'checking' ? '正在建立安全演示会话…' : sessionState === 'ready' ? '安全演示会话已建立' : '安全演示会话暂不可用' }}
      </view>
      <view v-if="selection" class="preview-card">
        <image class="preview" :src="selection.path" mode="aspectFit" />
        <text class="file-name">{{ selection.name }}</text>
        <text class="file-size">{{ sizeText }}</text>
      </view>
      <view class="rule-card">
        <text>{{ isDocumentCopy ? '文件复印规则' : '本批转换规则' }}</text>
        <text v-if="isDocumentCopy">单张文件照片 · A4 纵向 · 等比完整显示 · 不裁剪</text>
        <text v-else>单张图片 · A4 纵向 · 等比完整显示 · 不裁剪 · 透明区域白底</text>
        <text>生成 1 页 PDF 后复用现有单双面、份数与按纸张计价。</text>
        <text v-if="isDocumentCopy">证件组合、自动矫正和去阴影仍未开放。</text>
      </view>
      <view v-if="state === 'uploading'" class="status warning"><text>正在安全转换</text><text>图片解码在受限子进程中完成，请保持页面打开。</text></view>
      <view v-else-if="state === 'ready'" class="status success"><text>转换完成</text><text>已生成 1 页 A4 PDF，原图与中间文件将在 1 小时内清理。</text></view>
      <view v-else-if="state === 'failed'" class="status warning"><text>未完成转换</text><text>{{ failureText }}</text></view>
      <button v-if="state === 'selected'" class="primary" :class="{ disabled: sessionState !== 'ready' || !!failureText }" @click="uploadSelected">
        {{ sessionState === 'ready' ? '上传并生成 A4 文档' : '等待安全会话' }}
      </button>
      <button v-else-if="state === 'ready'" class="primary" @click="continueToSettings">继续设置打印参数</button>
      <button v-else-if="state === 'failed'" class="secondary" @click="goBack">返回重新选择</button>
      <view class="limits"><text>演示限制</text><text>JPG/PNG ≤ 2MB · 单张 · 暂存 1 小时</text></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.upload-page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.body{display:flex;align-items:center;flex-direction:column;padding:28rpx 28rpx 80rpx}.session-state{box-sizing:border-box;width:100%;padding:16rpx 20rpx;color:#8c6a3d;background:#fff7e9;border-radius:10rpx;text-align:center;font-size:21rpx}.session-state.ready{color:#26765c;background:#eaf9f3}.preview-card{box-sizing:border-box;width:100%;margin-top:26rpx;padding:24rpx;background:#fff;border-radius:16rpx;box-shadow:0 5rpx 18rpx rgba(0,0,0,.05)}.preview{width:100%;height:440rpx;background:#f2f2f2;border-radius:10rpx}.file-name{display:block;margin-top:20rpx;overflow:hidden;color:#222;font-size:27rpx;font-weight:650;text-overflow:ellipsis;white-space:nowrap}.file-size{display:block;margin-top:8rpx;color:#999;font-size:22rpx}.rule-card{box-sizing:border-box;display:flex;width:100%;margin-top:22rpx;padding:22rpx;flex-direction:column;gap:8rpx;color:#777;background:#fff;border-radius:14rpx;font-size:22rpx;line-height:34rpx}.rule-card text:first-child{color:#333;font-size:25rpx;font-weight:700}.status{box-sizing:border-box;display:flex;width:100%;margin-top:22rpx;padding:18rpx;flex-direction:column;border-radius:10rpx;font-size:23rpx;line-height:36rpx}.status text:first-child{font-weight:700}.warning{color:#986719;background:#fff6df}.success{color:#237a5a;background:#eaf9f3}.primary,.secondary{width:100%;height:84rpx;margin-top:24rpx;color:#fff;background:linear-gradient(90deg,#ff9736,#ffad45);border:0;border-radius:44rpx;font-size:29rpx;line-height:84rpx}.primary.disabled{color:#999;background:#e7e8ea}.secondary{color:#d97b24;background:#fff;border:1rpx solid #ffc98e}.limits{box-sizing:border-box;display:flex;justify-content:space-between;width:100%;margin-top:26rpx;color:#999;font-size:21rpx}
</style>
