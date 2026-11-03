<script setup lang="ts">
import { computed, ref } from 'vue'

const MAX_PHOTOS = 30
const photos = ref<string[]>([])
const remaining = computed(() => MAX_PHOTOS - photos.value.length)

function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/home/index' }) }
function addPhoto() {
  if (remaining.value <= 0) {
    uni.showToast({ title: '最多选择30张照片', icon: 'none' })
    return
  }
  uni.chooseImage({
    count: remaining.value,
    success(result) {
      const paths = Array.isArray(result.tempFilePaths) ? result.tempFilePaths : [result.tempFilePaths]
      const selected = paths.filter((path): path is string => typeof path === 'string' && path.length > 0)
      photos.value = [...photos.value, ...selected].slice(0, MAX_PHOTOS)
    },
    fail(error) {
      if (!error.errMsg.includes('cancel')) uni.showToast({ title: '无法打开照片选择器', icon: 'none' })
    }
  })
}
function submit() {
  uni.showToast({
    title: photos.value.length > 0 ? '照片仅在本地预览，排版和计价待接入' : '请先添加照片',
    icon: 'none'
  })
}
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>3寸照片上传</text><text class="spacer" /></view></view>
    <view v-if="photos.length === 0" class="empty"><view class="empty-icon">⌒</view><text>暂无照片哦~</text></view>
    <view v-else class="photo-grid"><image v-for="(photo, index) in photos" :key="`${photo}-${index}`" class="photo" :src="photo" mode="aspectFill" /></view>
    <view class="boundary">所选照片只保存在当前页面本地预览，不上传服务端；照片纸排版和权威计价待接入。</view>
    <view class="footer"><view class="upload" @click="addPhoto">上传照片({{ photos.length }}/{{ MAX_PHOTOS }})</view><view class="submit" :class="{ ready: photos.length > 0 }" @click="submit">提交打印</view></view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#fff}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.empty{display:flex;align-items:center;flex-direction:column;justify-content:center;min-height:720rpx;color:#333;font-size:26rpx}.empty-icon{display:flex;align-items:center;justify-content:center;width:150rpx;height:110rpx;margin-bottom:24rpx;color:#cfd7df;background:#f3f6f8;border-radius:44rpx;font-size:70rpx}.photo-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx;padding:28rpx 24rpx}.photo{width:100%;height:220rpx;background:#f3f3f3;border-radius:10rpx}.boundary{margin:24rpx 38rpx 145rpx;color:#aaa;font-size:22rpx;line-height:35rpx;text-align:center}.footer{position:fixed;right:0;bottom:0;left:0;display:grid;grid-template-columns:1fr 1fr;gap:20rpx;padding:14rpx 24rpx calc(14rpx + env(safe-area-inset-bottom));background:#fff;border-top:1rpx solid #eee}.upload,.submit{height:86rpx;border-radius:10rpx;text-align:center;font-size:29rpx;line-height:86rpx}.upload{color:#ff9a3d;border:2rpx solid #ff9a3d}.submit{color:#fff;background:#d8d8d8}.submit.ready{background:#ff9a3d}
</style>
