<script setup lang="ts">
function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/profile/index' })
}
function showReserved(label: string) { uni.showToast({ title: `${label}需微信身份与资金接口`, icon: 'none' }) }
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>会员卡</text><text class="spacer" /></view></view>
    <view class="card">
      <view class="card-top"><text>普通会员</text><view><text class="balance-label">余额：</text><text class="balance">0元</text></view></view>
      <view class="store"><view class="tag">◇</view><text>板栗打印工作室</text></view>
      <view class="actions"><text @click="showReserved('购买折扣')">购买折扣</text><text @click="showReserved('余额充值')">余额充值 ›</text></view>
    </view>
    <view class="detail-link" @click="showReserved('消费明细')">消费明细 <text>›</text></view>
    <view class="boundary">页面使用本地空余额展示，不读取原系统会员编号、余额或消费记录。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f7f7f7}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.card{margin:30rpx 22rpx;padding:26rpx 28rpx;color:#6a4814;background:linear-gradient(135deg,#f1d296,#fff0c7);border-radius:18rpx;box-shadow:0 8rpx 22rpx rgba(157,109,31,.18)}.card-top{display:flex;justify-content:space-between;font-size:23rpx}.balance-label{color:#86652a}.balance{font-size:28rpx;font-weight:700}.store{display:flex;align-items:center;gap:20rpx;height:145rpx;font-size:31rpx;font-weight:700}.tag{display:flex;align-items:center;justify-content:center;width:70rpx;height:70rpx;border:4rpx solid #fff4d3;border-radius:12rpx;font-size:40rpx}.actions{display:flex;justify-content:flex-end;gap:20rpx}.actions text{padding:12rpx 22rpx;background:rgba(255,255,255,.65);border-radius:24rpx;font-size:23rpx}.detail-link{display:flex;justify-content:space-between;margin:0 22rpx;padding:28rpx;background:#fff;border-radius:12rpx;font-size:27rpx}.detail-link text{color:#aaa;font-size:38rpx}.boundary{margin:28rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
