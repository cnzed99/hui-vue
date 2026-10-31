<script setup lang="ts">
import { computed, ref } from 'vue'

const content = ref('')
const styles = ['14px', 'H1', 'H2', 'H3']
const count = computed(() => content.value.length)
const canSubmit = computed(() => count.value > 0 && count.value <= 10000)

function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/home/index' })
}

function submit() {
  if (!canSubmit.value) return
  uni.showToast({ title: '文字解析与计价接口待接入', icon: 'none' })
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
    <textarea v-model="content" class="editor" maxlength="10000" placeholder="点击输入内容..." />
    <view class="counter">文字字数：{{ count }}，字数限制在1万字以内</view>
    <button class="submit" :disabled="!canSubmit" @click="submit">提交打印</button>
    <view class="boundary">内容只保存在当前页面内存中，不会上传、下单或写入原系统。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f7f7f7}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.toolbar{display:flex;gap:12rpx;padding:20rpx;background:#fff;border-bottom:1rpx solid #eee}.tool{min-width:84rpx;padding:14rpx 12rpx;color:#999;background:#f3f3f3;border-radius:8rpx;text-align:center;font-size:24rpx}.format-boundary{padding:12rpx 22rpx;color:#a77a50;background:#fff8ee;text-align:center;font-size:21rpx}.editor{box-sizing:border-box;width:calc(100% - 40rpx);height:520rpx;margin:20rpx;padding:24rpx;background:#fff;border:1rpx solid #eee;border-radius:10rpx;font-size:28rpx;line-height:44rpx}.counter{margin:0 28rpx;color:#888;font-size:22rpx}.submit{height:88rpx;margin:32rpx 22rpx 0;color:#fff;background:#ff9f3d;border-radius:10rpx;font-size:31rpx;line-height:88rpx}.submit[disabled]{color:#fff;background:#ffc58f}.boundary{margin:22rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
