<script setup lang="ts">
const sources = [
  { label: '微信卡包', description: '从微信卡包选择发票', icon: '▣', tone: 'wallet' },
  { label: '微信文件', description: '从微信聊天记录中选择发票', icon: '●●', tone: 'wechat' }
]

function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/home/index' })
}

function showBoundary(label: string) {
  uni.showToast({ title: `${label}接口待微信资质接入`, icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>发票打印</text><text class="spacer" /></view></view>
    <view class="source-list">
      <view v-for="source in sources" :key="source.label" class="source-card" @click="showBoundary(source.label)">
        <view class="source-icon" :class="source.tone">{{ source.icon }}</view>
        <view class="source-copy"><text class="source-title">{{ source.label }}</text><text class="source-description">{{ source.description }}</text></view>
        <text class="arrow">›</text>
      </view>
    </view>
    <view class="boundary">当前不会读取微信卡包或聊天记录，也不会选择、上传发票。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.source-list{display:flex;flex-direction:column;gap:22rpx;padding:28rpx 22rpx}.source-card{display:flex;align-items:center;min-height:158rpx;padding:0 32rpx;background:#fff;border-radius:12rpx;box-shadow:0 4rpx 14rpx rgba(0,0,0,.06)}.source-icon{display:flex;align-items:center;justify-content:center;width:86rpx;height:72rpx;margin-right:28rpx;color:#fff;border-radius:16rpx;font-size:28rpx;font-weight:700}.wallet{background:#ffac22}.wechat{background:#12bd32;border-radius:50%;font-size:21rpx}.source-copy{display:flex;flex:1;flex-direction:column}.source-title{color:#333;font-size:31rpx;font-weight:650}.source-description{margin-top:12rpx;color:#999;font-size:24rpx}.arrow{color:#bbb;font-size:48rpx}.boundary{margin:10rpx 42rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
