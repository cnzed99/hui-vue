<script setup lang="ts">
type Entry = { id: string; label: string; description: string; icon: string; route?: string }
const helpEntries: Entry[] = [
  { id: 'tutorials', label: '新手教程', description: '查看打印教程与历史指南', icon: '教', route: '/pages/tutorial-list/index' },
  { id: 'orders', label: '订单与售后', description: '查看本人订单、取消及模拟退款状态', icon: '单', route: '/pages/orders/index' }
]
const reservedEntries: Entry[] = [
  { id: 'feedback', label: '意见反馈', description: '需要后端写入和运营处理流程', icon: '意' },
  { id: 'phone', label: '绑定手机', description: '需要微信身份与手机号授权接口', icon: '机' }
]
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/profile/index' }) }
function openEntry(entry: Entry) {
  if (entry.route) {
    uni.navigateTo({ url: entry.route, fail: () => uni.showToast({ title: '入口暂时无法打开', icon: 'none' }) })
    return
  }
  uni.showToast({ title: `${entry.label}暂未接入`, icon: 'none' })
}
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>服务中心</text><text class="spacer" /></view></view>
    <view class="section-title">新系统只读帮助</view>
    <view class="list"><view v-for="entry in helpEntries" :key="entry.id" class="item" @click="openEntry(entry)"><view class="entry-icon">{{ entry.icon }}</view><view class="copy"><text class="name">{{ entry.label }}</text><text class="description">{{ entry.description }}</text></view><text class="arrow">›</text></view></view>
    <view class="section-title">原版入口（待接入）</view>
    <view class="list"><view v-for="entry in reservedEntries" :key="entry.id" class="item" @click="openEntry(entry)"><view class="entry-icon muted">{{ entry.icon }}</view><view class="copy"><text class="name">{{ entry.label }}</text><text class="description">{{ entry.description }}</text></view><text class="reserved">未接入</text></view></view>
    <view class="boundary">教程和订单入口仅读取新系统数据；当前不提交反馈，不读取或绑定手机号。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.section-title{padding:30rpx 34rpx 16rpx;color:#888;font-size:23rpx}.list{background:#fff}.item{display:flex;align-items:center;min-height:108rpx;padding:0 32rpx;border-bottom:1rpx solid #eee}.entry-icon{display:flex;align-items:center;justify-content:center;width:58rpx;height:58rpx;margin-right:22rpx;color:#f28c27;background:#fff1df;border-radius:50%;font-size:23rpx;font-weight:700}.entry-icon.muted{color:#999;background:#f0f0f0}.copy{display:flex;min-width:0;flex:1;flex-direction:column;gap:8rpx}.name{font-size:28rpx}.description{color:#999;font-size:21rpx;line-height:30rpx}.arrow{color:#aaa;font-size:40rpx}.reserved{color:#aaa;font-size:21rpx}.boundary{margin:38rpx;color:#999;text-align:center;font-size:22rpx;line-height:36rpx}
</style>
