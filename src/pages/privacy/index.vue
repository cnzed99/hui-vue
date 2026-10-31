<script setup lang="ts">
const agreements = ['用户隐私政策', '会员卡用户使用协议', '用户注册协议']
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/profile/index' }) }
function showAgreement(label: string) { uni.showToast({ title: `${label}正文待主体确认`, icon: 'none' }) }
function showCancellationBoundary() { uni.showToast({ title: '注销功能待正式身份体系接入', icon: 'none' }) }
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>隐私协议</text><text class="spacer" /></view></view>
    <view class="list"><view v-for="agreement in agreements" :key="agreement" class="item" @click="showAgreement(agreement)"><text>{{ agreement }}</text><text class="arrow">›</text></view></view>
    <button class="cancel-account" @click="showCancellationBoundary">用户注销</button>
    <view class="boundary">协议正文、注销身份核验与数据处理范围须由正式运营主体确认；当前按钮不会注销账户或删除数据。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:40rpx;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.list{margin:20rpx 14rpx 0;background:#fff;border-radius:12rpx;overflow:hidden}.item{display:flex;align-items:center;justify-content:space-between;height:96rpx;padding:0 28rpx;border-bottom:1rpx solid #eee;font-size:27rpx}.item:last-child{border-bottom:0}.arrow{color:#aaa;font-size:40rpx}.cancel-account{height:88rpx;margin:500rpx 22rpx 0;color:#fff;background:#ff9f3d;border-radius:44rpx;font-size:30rpx;line-height:88rpx}.boundary{margin:24rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
