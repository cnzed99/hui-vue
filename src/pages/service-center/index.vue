<script setup lang="ts">
import { ref } from 'vue'

type Entry = { id: string; label: string; description: string; icon: string; route?: string }
const helpEntries: Entry[] = [
  { id: 'tutorials', label: '新手教程', description: '查看打印教程与历史指南', icon: '教', route: '/pages/tutorial-list/index' },
  { id: 'orders', label: '订单与售后', description: '查看本人订单、取消及模拟退款状态', icon: '单', route: '/pages/orders/index' }
]
const showPhoneNotice = ref(false)
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/profile/index' }) }
function openEntry(entry: Entry) {
  if (entry.route) {
    uni.navigateTo({ url: entry.route, fail: () => uni.showToast({ title: '入口暂时无法打开', icon: 'none' }) })
    return
  }
}
function showFeedbackBoundary() { uni.showToast({ title: '请在微信小程序中使用官方反馈', icon: 'none' }) }
function showPhoneBoundary() { showPhoneNotice.value = true }
function closePhoneBoundary() { showPhoneNotice.value = false }
function reservedPhoneAuth() { uni.showToast({ title: '微信手机号授权尚未接入', icon: 'none' }) }
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>服务中心</text><text class="spacer" /></view></view>
    <view class="section-title">新系统只读帮助</view>
    <view class="list"><view v-for="entry in helpEntries" :key="entry.id" class="item" @click="openEntry(entry)"><view class="entry-icon">{{ entry.icon }}</view><view class="copy"><text class="name">{{ entry.label }}</text><text class="description">{{ entry.description }}</text></view><text class="arrow">›</text></view></view>
    <view class="section-title">微信平台能力</view>
    <view class="list">
      <!-- #ifdef MP-WEIXIN -->
      <button class="item platform-button" open-type="feedback"><view class="entry-icon">意</view><view class="copy"><text class="name">意见反馈</text><text class="description">打开微信官方反馈与投诉入口</text></view><text class="arrow">›</text></button>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <view class="item" @click="showFeedbackBoundary"><view class="entry-icon muted">意</view><view class="copy"><text class="name">意见反馈</text><text class="description">微信官方入口，仅在微信小程序中可用</text></view><text class="reserved">微信端</text></view>
      <!-- #endif -->
      <view class="item" @click="showPhoneBoundary"><view class="entry-icon muted">机</view><view class="copy"><text class="name">绑定手机</text><text class="description">查看手机号授权与服务协议边界</text></view><text class="reserved">待接入</text></view>
    </view>
    <view class="boundary">教程和订单入口只访问新系统演示环境；订单页可能创建隔离演示会话，但不会因此创建订单或付款。意见反馈由微信平台承载；当前不读取、上传或绑定手机号。</view>
    <view v-if="showPhoneNotice" class="mask" @click="closePhoneBoundary"><view class="dialog" @click.stop><text class="dialog-title">温馨提示</text><text class="dialog-copy">当前账号尚未绑定手机号。正式版需在阅读服务协议后，由用户主动授权微信手机号。当前 AppID、微信登录及手机号接口尚未接入，不会读取或上传手机号。</text><button class="phone-button" @click="reservedPhoneAuth">手机号授权待接入</button><text class="dialog-close" @click="closePhoneBoundary">×</text></view></view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.section-title{padding:30rpx 34rpx 16rpx;color:#888;font-size:23rpx}.list{background:#fff}.item{display:flex;align-items:center;box-sizing:border-box;width:100%;min-height:108rpx;padding:0 32rpx;border:0;border-bottom:1rpx solid #eee;border-radius:0;background:#fff;text-align:left}.platform-button::after{border:0}.entry-icon{display:flex;align-items:center;justify-content:center;width:58rpx;height:58rpx;margin-right:22rpx;color:#f28c27;background:#fff1df;border-radius:50%;font-size:23rpx;font-weight:700}.entry-icon.muted{color:#999;background:#f0f0f0}.copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:8rpx}.name{font-size:28rpx}.description{color:#999;font-size:21rpx;line-height:30rpx}.arrow{color:#aaa;font-size:40rpx}.reserved{color:#aaa;font-size:21rpx}.boundary{margin:38rpx;color:#999;text-align:center;font-size:22rpx;line-height:36rpx}.mask{position:fixed;z-index:20;top:0;right:0;bottom:0;left:0;display:flex;align-items:center;justify-content:center;padding:40rpx;background:rgba(0,0,0,.55)}.dialog{position:relative;display:flex;width:620rpx;box-sizing:border-box;flex-direction:column;padding:40rpx 42rpx 34rpx;border-radius:22rpx;background:#fff}.dialog-title{text-align:center;font-size:34rpx;font-weight:700}.dialog-copy{margin-top:32rpx;font-size:26rpx;line-height:44rpx}.phone-button{height:78rpx;margin-top:34rpx;color:#fff;background:#ff9f3d;border-radius:8rpx;font-size:28rpx;line-height:78rpx}.phone-button::after{border:0}.dialog-close{position:absolute;top:20rpx;right:24rpx;width:46rpx;height:46rpx;color:#777;border:2rpx solid #999;border-radius:50%;text-align:center;font-size:38rpx;line-height:40rpx}
</style>
