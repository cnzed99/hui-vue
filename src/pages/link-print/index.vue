<script setup lang="ts">
import { computed, ref } from 'vue'

const link = ref('')
const supported = [
  { label: '微信公众号文章', icon: '●●', tone: 'wechat' },
  { label: '百度文章链接', icon: 'du', tone: 'baidu' },
  { label: '学习强国链接', icon: '学', tone: 'study' }
]
const canFetch = computed(() => /^https?:\/\//i.test(link.value.trim()))

function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/home/index' })
}

function fetchArticle() {
  if (!canFetch.value) return
  uni.showToast({ title: '文章解析接口待安全接入', icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>链接打印</text><text class="spacer" /></view></view>
    <view class="section-title"><text />复制链接打印</view>
    <textarea v-model="link" class="link-input" maxlength="2048" placeholder="请将复制的链接粘贴在此处" />
    <button class="fetch-button" :disabled="!canFetch" @click="fetchArticle">获取文章打印</button>
    <view class="guide"><view class="avatar">i</view><text>在微信、百度、学习强国上看到需要打印的文章，复制文章链接，手动粘贴到页面输入框中。</text></view>
    <view class="support-title">目前支持的文章链接类型如下：</view>
    <view class="support-grid"><view v-for="item in supported" :key="item.label" class="support-item"><view class="support-icon" :class="item.tone">{{ item.icon }}</view><text>{{ item.label }}</text></view></view>
    <view class="boundary">为保护隐私，新系统进入页面时不会自动读取剪贴板。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#fff}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.section-title{display:flex;align-items:center;margin:30rpx 24rpx 15rpx;font-size:27rpx}.section-title text{width:7rpx;height:30rpx;margin-right:10rpx;background:#ff9f3d;border-radius:4rpx}.link-input{box-sizing:border-box;width:calc(100% - 40rpx);height:220rpx;margin:0 20rpx;padding:22rpx;background:#f5f5f5;border-radius:6rpx;font-size:25rpx}.fetch-button{height:82rpx;margin:28rpx 70rpx;color:#fff;background:#ff9f3d;border-radius:10rpx;font-size:29rpx;line-height:82rpx}.fetch-button[disabled]{color:#fff;background:#ffc58f}.guide{display:flex;align-items:center;gap:24rpx;margin:90rpx 22rpx 0;padding:22rpx;color:#777;background:#fafafa;border-radius:10rpx;font-size:24rpx;line-height:40rpx}.avatar{display:flex;flex:0 0 auto;align-items:center;justify-content:center;width:86rpx;height:86rpx;color:#fff;background:#4d88a3;border-radius:50%;font-size:50rpx;font-weight:700}.support-title{margin:74rpx 28rpx 35rpx;color:#555;font-size:25rpx}.support-grid{display:grid;grid-template-columns:repeat(3,1fr);padding:0 25rpx}.support-item{display:flex;align-items:center;gap:14rpx;color:#666;flex-direction:column;font-size:20rpx}.support-icon{display:flex;align-items:center;justify-content:center;width:64rpx;height:64rpx;font-weight:700}.wechat{color:#28bd4b;font-size:22rpx}.baidu{color:#3478ee;font-size:25rpx}.study{color:#c92b2b;font-size:36rpx}.boundary{margin:58rpx 34rpx 0;color:#999;font-size:22rpx;line-height:36rpx}
</style>
