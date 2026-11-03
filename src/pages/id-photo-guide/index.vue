<script setup lang="ts">
import { onBeforeUnmount } from 'vue'

const steps = [
  { title: '选择证件照规格', icon: '▤' },
  { title: '拍摄/上传照片', icon: '＋' },
  { title: '十秒生成证件照', icon: '◷' },
  { title: '下载/冲印证件照', icon: '↓' }
]
function goBack() {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/id-photo-smart/index' })
}
function explain() { uni.showToast({ title: '相机和证件照制作尚未接入', icon: 'none' }) }
onBeforeUnmount(() => uni.hideToast())
</script>

<template>
  <view class="guide-page">
    <view class="header">
      <button class="guide-back" aria-label="返回智能证件照" @click="goBack">‹</button>
      <text>操作指南</text>
      <view class="spacer" />
    </view>
    <view class="steps">
      <view v-for="(step, index) in steps" :key="step.title" class="step">
        <view class="illustration" aria-hidden="true"><text>{{ step.icon }}</text></view>
        <text class="step-label">{{ index + 1 }}. {{ step.title }}</text>
      </view>
      <text class="boundary">当前可查看操作步骤，拍摄、制作、下载和冲印暂未接入。</text>
    </view>
    <view class="footer"><button @click="explain">我知道了,去拍证件照</button></view>
  </view>
</template>

<style scoped>
.guide-page { display: flex; min-height: 100vh; flex-direction: column; background: #fff; color: #26231f; }
.header { display: flex; align-items: center; justify-content: space-between; min-height: 86rpx; padding: max(18rpx, var(--status-bar-height)) 26rpx 12rpx; color: #fff; background: #ff9f3d; font-size: 34rpx; font-weight: 600; }
.guide-back, .spacer { width: 60rpx; }
.guide-back { margin: 0; padding: 0; background: transparent; color: #fff; font-size: 54rpx; line-height: 60rpx; }
button::after { border: 0; }
.steps { display: flex; flex-direction: column; gap: 18rpx; padding: 26rpx; }
.step { display: flex; align-items: center; min-height: 120rpx; padding: 12rpx 22rpx; border-radius: 16rpx; background: #fff7eb; }
.illustration { display: flex; align-items: center; justify-content: center; width: 90rpx; height: 80rpx; margin-right: 26rpx; border: 2rpx solid #e9b379; border-radius: 12rpx; background: #fff; color: #9a4d0e; font-size: 46rpx; }
.step-label { font-size: 28rpx; font-weight: 650; }
.boundary { color: #6b6259; font-size: 23rpx; line-height: 36rpx; }
.footer { margin-top: auto; padding: 18rpx 28rpx calc(18rpx + env(safe-area-inset-bottom)); }
.footer button { border-radius: 44rpx; background: #ff9f3d; color: #302318; font-size: 30rpx; }
</style>
