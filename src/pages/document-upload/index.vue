<script setup lang="ts">
import { computed, ref } from 'vue'
import { localCatalog } from '@/api/catalog'

type FileState = 'empty' | 'selected' | 'demo-ready'
const state = ref<FileState>('empty')
const fileName = ref('')
const fileSize = ref(0)
const supported = localCatalog.supportedExtensions
const sizeText = computed(() => fileSize.value ? `${(fileSize.value / 1024).toFixed(1)} KB` : '')

function acceptSelection(value: unknown) {
  const tempFiles = Array.isArray(value) ? value : [value]
  const file = tempFiles[0] as { name?: string; size?: number } | undefined
  if (!file) return
  fileName.value = file.name || '未命名文件'
  fileSize.value = file.size || 0
  state.value = 'selected'
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
</script>

<template>
  <view class="upload-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>上传本地文件</text><text class="spacer" /></view></view>
    <view class="body">
      <view class="illustration"><view class="paper back-paper" /><view class="paper front-paper"><text>W</text><text class="paper-lines">≡</text></view><view class="upload-mark">↑</view></view>
      <text class="support-title">支持上传 Word、PPT、PDF、Excel、图片格式文件</text>
      <text class="boundary">当前仅在设备本地读取文件名和大小，不向服务器传输。</text>
      <button class="choose-button" @click="chooseFile">选择本地文件打印</button>
      <button class="demo-button" @click="useDemoFile">使用演示文件查看解析状态</button>
      <view v-if="state !== 'empty'" class="result-card" :class="state">
        <view class="result-head"><view class="file-icon">PDF</view><view class="file-copy"><text>{{ fileName }}</text><text>{{ sizeText }}</text></view></view>
        <view v-if="state === 'selected'" class="status warning"><text>已在本地选择</text><text>上传与服务端解析尚未开放，未发送文件。</text></view>
        <view v-else class="status success"><text>演示解析完成</text><text>PDF · 3 页 · A4；这是固定演示结果，不代表真实文件已解析。</text></view>
      </view>
      <view class="limits"><text>演示限制</text><text>≤ 2MB · ≤ 20 页 · 暂存 1 小时</text></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.upload-page{min-height:100vh;background:#fff}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.body{display:flex;align-items:center;flex-direction:column;padding:86rpx 44rpx}.illustration{position:relative;width:240rpx;height:210rpx}.paper{position:absolute;width:130rpx;height:160rpx;border-radius:10rpx;box-shadow:0 8rpx 20rpx rgba(48,128,178,.16)}.back-paper{left:40rpx;top:18rpx;transform:rotate(-12deg);background:#dff2ff}.front-paper{left:72rpx;top:4rpx;display:flex;align-items:center;flex-direction:column;padding-top:26rpx;color:#4ca5de;background:#fff;font-size:54rpx;font-weight:700}.paper-lines{margin-top:8rpx;color:#a6d7f1;font-size:58rpx}.upload-mark{position:absolute;right:18rpx;bottom:12rpx;display:flex;align-items:center;justify-content:center;width:78rpx;height:78rpx;color:#fff;background:#ff9a37;border:8rpx solid #fff;border-radius:50%;font-size:46rpx}.support-title{margin-top:28rpx;text-align:center;font-size:28rpx;line-height:44rpx}.boundary{margin-top:14rpx;color:#999;text-align:center;font-size:22rpx;line-height:34rpx}.choose-button,.demo-button{width:100%;height:86rpx;margin-top:50rpx;color:#fff;background:linear-gradient(90deg,#ff9736,#ffad45);border:0;border-radius:44rpx;font-size:30rpx;line-height:86rpx}.demo-button{margin-top:18rpx;color:#dc7e27;background:#fff7ed;border:1rpx solid #ffd4a8}.result-card{width:100%;margin-top:34rpx;padding:24rpx;background:#f8fafc;border:1rpx solid #edf0f3;border-radius:14rpx}.result-head{display:flex;align-items:center}.file-icon{display:flex;align-items:center;justify-content:center;width:72rpx;height:72rpx;color:#fff;background:#f06b5d;border-radius:10rpx;font-size:20rpx;font-weight:700}.file-copy{display:flex;min-width:0;flex:1;flex-direction:column;margin-left:18rpx}.file-copy text:first-child{overflow:hidden;font-size:27rpx;font-weight:600;text-overflow:ellipsis;white-space:nowrap}.file-copy text:last-child{margin-top:8rpx;color:#999;font-size:21rpx}.status{display:flex;flex-direction:column;margin-top:22rpx;padding:18rpx;border-radius:10rpx;font-size:23rpx;line-height:36rpx}.status text:first-child{font-weight:700}.warning{color:#986719;background:#fff6df}.success{color:#237a5a;background:#eaf9f3}.limits{display:flex;justify-content:space-between;width:100%;margin-top:28rpx;color:#999;font-size:22rpx}
</style>
