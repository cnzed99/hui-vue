<script setup lang="ts">
const entries = [
  { id: 'design', label: '快速设计', badge: '在线PS文字', description: '文字旋转缩放，快速打印', icon: 'T', tone: 'mint' },
  { id: 'custom', label: '自定义文字', badge: '', description: '复制文字粘贴，快速打印', icon: '✎', tone: 'cyan' }
]

function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/home/index' })
}

function openEntry(id: string) {
  if (id === 'custom') { uni.navigateTo({ url: '/pages/text-editor/index' }); return }
  uni.showToast({ title: '在线设计器待正式适配', icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>文字速印</text><text class="spacer" /></view></view>
    <view class="entry-list">
      <view v-for="item in entries" :key="item.id" class="entry-card" @click="openEntry(item.id)">
        <view class="entry-icon" :class="item.tone">{{ item.icon }}</view>
        <view class="entry-copy"><view><text class="entry-title">{{ item.label }}</text><text v-if="item.badge" class="badge">{{ item.badge }}</text></view><text class="entry-description">{{ item.description }}</text></view>
        <text class="arrow">›</text>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f7f7f7}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.entry-list{display:flex;flex-direction:column;gap:18rpx;padding:28rpx 22rpx}.entry-card{display:flex;align-items:center;min-height:164rpx;padding:0 32rpx;background:#fff;border-radius:12rpx;box-shadow:0 4rpx 14rpx rgba(0,0,0,.06)}.entry-icon{display:flex;align-items:center;justify-content:center;width:86rpx;height:86rpx;margin-right:26rpx;color:#fff;border-radius:50%;font-size:42rpx}.mint{background:#54d5a8}.cyan{background:#28cfc9}.entry-copy{display:flex;flex:1;flex-direction:column}.entry-title{font-size:31rpx}.badge{margin-left:10rpx;padding:4rpx 8rpx;color:#fff;background:#f06c34;border-radius:10rpx;font-size:18rpx}.entry-description{margin-top:12rpx;color:#999;font-size:24rpx}.arrow{color:#bbb;font-size:48rpx}
</style>
