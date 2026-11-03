<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { resolveSize } from '@/domain/idPhotoSizes'

const PREVIEW_SELECTION_KEY = 'miniapp-id-photo-preview-selection-v1'
const observedLayouts = new Set(['one', 'two', 'three'])
const size = ref<ReturnType<typeof resolveSize>>()
const photo = ref('')

onLoad(query => {
  size.value = resolveSize(query?.size)
  if (!size.value) uni.redirectTo({ url: '/pages/id-photo-sizes/index' })
})

function goBack() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: '/pages/id-photo-sizes/index' })
}

function choosePhoto() {
  uni.chooseImage({
    count: 1,
    sourceType: ['album'],
    success(result) {
      if (result.tempFilePaths[0]) photo.value = result.tempFilePaths[0]
    },
    fail(error) {
      if (!error.errMsg.includes('cancel')) uni.showToast({ title: '无法打开相册', icon: 'none' })
    }
  })
}

function next() {
  if (!photo.value) {
    uni.showToast({ title: '请先添加证件照', icon: 'none' })
    return
  }
  if (!size.value || !observedLayouts.has(size.value.id)) {
    uni.showToast({ title: '该尺寸排版待继续观察', icon: 'none' })
    return
  }
  uni.setStorageSync(PREVIEW_SELECTION_KEY, {
    schemaVersion: 1,
    path: photo.value,
    sizeId: size.value.id,
    createdAtMs: Date.now()
  })
  uni.navigateTo({
    url: `/pages/id-photo-preview/index?size=${encodeURIComponent(size.value.id)}`,
    fail: () => uni.removeStorageSync(PREVIEW_SELECTION_KEY)
  })
}
</script>

<template>
  <view v-if="size" class="page">
    <view class="header">
      <view class="status" />
      <view class="title"><text @click="goBack">‹</text><text>上传证件照</text><text /></view>
    </view>
    <view class="content">
      <view class="hint"><text>●</text> 建议图片铺满边框并把人像对准虚线</view>
      <view class="name">{{ size.name }}</view>
      <view class="frame" :style="{ width: (520 * size.width / size.height) + 'rpx' }" @click="choosePhoto">
        <image v-if="photo" :src="photo" mode="aspectFill" class="preview" />
        <view class="head" />
        <view class="shoulders" />
        <text v-if="!photo" class="plus">＋</text>
      </view>
      <text v-if="photo" class="preview-note">本地预览，点击图片可更换</text>
    </view>
    <view class="footer"><button @click="next">下一步</button></view>
  </view>
</template>

<style scoped>
.page { display: flex; min-height: 100vh; flex-direction: column; background: #fff; }
.header { color: #fff; background: #ff9f3d; }
.status { height: var(--status-bar-height); min-height: 36rpx; }
.title { display: flex; justify-content: space-between; align-items: center; padding: 0 26rpx; height: 86rpx; font-size: 34rpx; }
.title text:first-child, .title text:last-child { width: 56rpx; }
.title text:first-child { font-size: 56rpx; }
.content { display: flex; flex: 1; align-items: center; flex-direction: column; padding: 48rpx 22rpx 40rpx; }
.hint { font-size: 25rpx; text-align: center; }
.hint text { color: #ff9f3d; }
.name { margin: 36rpx 0 20rpx; font-size: 30rpx; }
.frame { position: relative; height: 520rpx; overflow: hidden; box-shadow: 0 0 20rpx #d8e6fb; }
.preview { position: absolute; width: 100%; height: 100%; }
.head, .shoulders { position: absolute; box-sizing: border-box; border: 4rpx dashed #ff9f3d; pointer-events: none; }
.head { top: 8%; left: 25%; width: 50%; height: 53%; border-radius: 48%; }
.shoulders { top: 62%; left: 6%; width: 88%; height: 24%; border-bottom: 0; border-radius: 50% 50% 0 0; }
.plus { position: absolute; left: 50%; bottom: 13%; transform: translateX(-50%); width: 100rpx; height: 100rpx; border: 3rpx solid #f6d172; border-radius: 50%; color: #f6d172; background: #fff; text-align: center; font-size: 70rpx; line-height: 100rpx; }
.preview-note { margin-top: 24rpx; color: #777; font-size: 24rpx; }
.footer { padding: 20rpx 26rpx calc(16rpx + env(safe-area-inset-bottom)); }
.footer button { border-radius: 48rpx; background: #ff9f3d; color: #fff; font-size: 32rpx; }
.footer button::after { border: 0; }
</style>
