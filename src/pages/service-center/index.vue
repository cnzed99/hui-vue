<script setup lang="ts">
const entries = ['意见反馈', '绑定手机']
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/profile/index' }) }
function showReserved(label: string) { uni.showToast({ title: `${label}需微信身份接口`, icon: 'none' }) }
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>服务中心</text><text class="spacer" /></view></view>
    <view class="list"><view v-for="entry in entries" :key="entry" class="item" @click="showReserved(entry)"><text>{{ entry }}</text><text class="arrow">›</text></view></view>
    <view class="boundary">当前不提交反馈，不读取或绑定手机号。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.list{background:#fff}.item{display:flex;align-items:center;justify-content:space-between;height:96rpx;padding:0 40rpx;border-bottom:1rpx solid #eee;font-size:28rpx}.arrow{color:#aaa;font-size:40rpx}.boundary{margin:38rpx;color:#999;text-align:center;font-size:22rpx;line-height:36rpx}
</style>
