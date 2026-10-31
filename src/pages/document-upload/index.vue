<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { localCatalog } from '@/api/catalog'
import { ensureDemoSession } from '@/api/demoSession'
import { uploadDemoPdf, waitForDemoFile } from '@/api/demoFiles'
import { createDraft, saveDraft } from '@/domain/printDraft'

type FileState = 'empty' | 'selected' | 'uploading' | 'ready' | 'failed' | 'demo-ready'
const state = ref<FileState>('empty')
const fileName = ref('')
const fileSize = ref(0)
const supported = localCatalog.supportedExtensions
const sizeText = computed(() => fileSize.value ? `${(fileSize.value / 1024).toFixed(1)} KB` : '')
const sessionState = ref<'checking' | 'ready' | 'unavailable'>('checking')
const tempPath = ref('')
const serverFileId = ref('')
const parsedPages = ref(0)
const failureText = ref('')

onMounted(async () => {
  sessionState.value = await ensureDemoSession() ? 'ready' : 'unavailable'
})

function acceptSelection(value: unknown) {
  const tempFiles = Array.isArray(value) ? value : [value]
  const file = tempFiles[0] as { name?: string; size?: number; path?: string } | undefined
  if (!file) return
  fileName.value = file.name || '未命名文件'
  fileSize.value = file.size || 0
  tempPath.value = file.path || ''
  serverFileId.value = ''
  parsedPages.value = 0
  failureText.value = ''
  state.value = 'selected'
  if (!fileName.value.toLowerCase().endsWith('.pdf')) failureText.value = 'M3a 当前仅开放 PDF；其它格式仍为预留能力。'
  else if (fileSize.value > localCatalog.demoLimits.maxFileBytes) failureText.value = '文件超过 2MB 演示限制。'
  else if (!tempPath.value) failureText.value = '当前设备未返回可上传的临时文件路径。'
}

function goBack() {
  uni.navigateBack()
}

function chooseFile() {
  // #ifdef MP-WEIXIN
  uni.chooseMessageFile({ count: 1, type: 'file', extension: supported, success: result => acceptSelection(result.tempFiles) })
  // #endif
  // #ifdef H5
  uni.chooseFile({ count: 1, type: 'all', extension: supported, success: result => acceptSelection(result.tempFiles) })
  // #endif
}

function useDemoFile() {
  fileName.value = '打印演示文档.pdf'
  fileSize.value = 184320
  state.value = 'demo-ready'
}

async function uploadSelected() {
  if (state.value !== 'selected' || failureText.value) return
  const session = await ensureDemoSession()
  if (!session) {
    sessionState.value = 'unavailable'
    failureText.value = '安全演示会话不可用，请稍后重试。'
    return
  }
  state.value = 'uploading'
  const uploaded = await uploadDemoPdf(session, tempPath.value)
  const result = uploaded ? await waitForDemoFile(session, uploaded) : null
  if (!result || result.status !== 'READY' || !result.pageCount) {
    state.value = 'failed'
    failureText.value = result?.failureCode ? `服务端解析失败：${result.failureCode}` : '上传或解析超时，请重新选择文件。'
    return
  }
  serverFileId.value = result.id
  parsedPages.value = result.pageCount
  state.value = 'ready'
}

function continueToSettings() {
  if (state.value !== 'demo-ready' && state.value !== 'ready') {
    uni.showToast({ title: '请先完成文件解析', icon: 'none' })
    return
  }
  saveDraft(state.value === 'ready'
    ? createDraft('SERVER_UPLOAD', fileName.value, fileSize.value, parsedPages.value, serverFileId.value)
    : createDraft('FIXTURE', fileName.value, fileSize.value, 3))
  uni.navigateTo({ url: '/pages/document-settings/index' })
}
</script>

<template>
  <view class="upload-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>上传本地文件</text><text class="spacer" /></view></view>
    <view class="body">
      <view class="session-state" :class="sessionState">
        {{ sessionState === 'checking' ? '正在建立安全演示会话…' : sessionState === 'ready' ? '安全演示会话已建立' : '演示会话接口待开启；仍可查看本地页面流程' }}
      </view>
      <view class="illustration"><view class="paper back-paper" /><view class="paper front-paper"><text>W</text><text class="paper-lines">≡</text></view><view class="upload-mark">↑</view></view>
      <text class="support-title">M3a 当前开放 PDF 安全演示上传</text>
      <text class="boundary">Word、PPT、Excel 和图片仍为预留能力；文件只进入新系统私有开发存储，1 小时内清理。</text>
      <button class="choose-button" @click="chooseFile">选择本地文件打印</button>
      <button class="demo-button" @click="useDemoFile">使用演示文件查看解析状态</button>
      <view v-if="state !== 'empty'" class="result-card" :class="state">
        <view class="result-head"><view class="file-icon">PDF</view><view class="file-copy"><text>{{ fileName }}</text><text>{{ sizeText }}</text></view></view>
        <view v-if="state === 'selected'" class="status warning"><text>已在本地选择</text><text>{{ failureText || '尚未上传；点击下方按钮后才会发送到新系统。' }}</text></view>
        <view v-else-if="state === 'uploading'" class="status warning"><text>正在安全上传并解析</text><text>请保持页面打开，通常数秒内完成。</text></view>
        <view v-else-if="state === 'ready'" class="status success"><text>服务端解析完成</text><text>PDF · {{ parsedPages }} 页 · 文件将在 1 小时内清理。</text></view>
        <view v-else-if="state === 'failed'" class="status warning"><text>未完成解析</text><text>{{ failureText }}</text></view>
        <view v-else class="status success"><text>演示解析完成</text><text>PDF · 3 页 · A4；这是固定演示结果，不代表真实文件已解析。</text></view>
      </view>
      <button v-if="state === 'selected'" class="continue-button" :class="{ disabled: !!failureText || sessionState !== 'ready' }" @click="uploadSelected">
        {{ failureText ? '请重新选择 PDF' : sessionState === 'ready' ? '上传并安全解析' : '等待安全会话' }}
      </button>
      <button v-else-if="state !== 'empty'" class="continue-button" :class="{ disabled: !['ready','demo-ready'].includes(state) }" @click="continueToSettings">
        {{ state === 'uploading' ? '正在解析…' : state === 'failed' ? '请重新选择文件' : '继续设置打印参数' }}
      </button>
      <view class="limits"><text>演示限制</text><text>≤ 2MB · ≤ 20 页 · 暂存 1 小时</text></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.upload-page{min-height:100vh;background:#fff}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.body{display:flex;align-items:center;flex-direction:column;padding:30rpx 44rpx 86rpx}.session-state{width:100%;margin-bottom:54rpx;padding:16rpx 20rpx;color:#8c6a3d;background:#fff7e9;border-radius:10rpx;text-align:center;font-size:21rpx}.session-state.ready{color:#26765c;background:#eaf9f3}.illustration{position:relative;width:240rpx;height:210rpx}.paper{position:absolute;width:130rpx;height:160rpx;border-radius:10rpx;box-shadow:0 8rpx 20rpx rgba(48,128,178,.16)}.back-paper{left:40rpx;top:18rpx;transform:rotate(-12deg);background:#dff2ff}.front-paper{left:72rpx;top:4rpx;display:flex;align-items:center;flex-direction:column;padding-top:26rpx;color:#4ca5de;background:#fff;font-size:54rpx;font-weight:700}.paper-lines{margin-top:8rpx;color:#a6d7f1;font-size:58rpx}.upload-mark{position:absolute;right:18rpx;bottom:12rpx;display:flex;align-items:center;justify-content:center;width:78rpx;height:78rpx;color:#fff;background:#ff9a37;border:8rpx solid #fff;border-radius:50%;font-size:46rpx}.support-title{margin-top:28rpx;text-align:center;font-size:28rpx;line-height:44rpx}.boundary{margin-top:14rpx;color:#999;text-align:center;font-size:22rpx;line-height:34rpx}.choose-button,.demo-button{width:100%;height:86rpx;margin-top:50rpx;color:#fff;background:linear-gradient(90deg,#ff9736,#ffad45);border:0;border-radius:44rpx;font-size:30rpx;line-height:86rpx}.demo-button{margin-top:18rpx;color:#dc7e27;background:#fff7ed;border:1rpx solid #ffd4a8}.result-card{width:100%;margin-top:34rpx;padding:24rpx;background:#f8fafc;border:1rpx solid #edf0f3;border-radius:14rpx}.result-head{display:flex;align-items:center}.file-icon{display:flex;align-items:center;justify-content:center;width:72rpx;height:72rpx;color:#fff;background:#f06b5d;border-radius:10rpx;font-size:20rpx;font-weight:700}.file-copy{display:flex;min-width:0;flex:1;flex-direction:column;margin-left:18rpx}.file-copy text:first-child{overflow:hidden;font-size:27rpx;font-weight:600;text-overflow:ellipsis;white-space:nowrap}.file-copy text:last-child{margin-top:8rpx;color:#999;font-size:21rpx}.status{display:flex;flex-direction:column;margin-top:22rpx;padding:18rpx;border-radius:10rpx;font-size:23rpx;line-height:36rpx}.status text:first-child{font-weight:700}.warning{color:#986719;background:#fff6df}.success{color:#237a5a;background:#eaf9f3}.limits{display:flex;justify-content:space-between;width:100%;margin-top:28rpx;color:#999;font-size:22rpx}
.continue-button{width:100%;height:82rpx;margin-top:20rpx;color:#fff;background:#2ba97f;border-radius:42rpx;font-size:28rpx;line-height:82rpx}.continue-button.disabled{color:#999;background:#eceff1}.limits{margin-bottom:40rpx}
</style>
