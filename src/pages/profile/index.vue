<script setup lang="ts">
import BottomNav from '@/components/BottomNav.vue'
import { profileMenus } from '@/data/fixtures'

function showReserved(label: string) {
  uni.showToast({ title: `${label}接口待接入`, icon: 'none' })
}
</script>

<template>
  <view class="page-shell">
    <view class="profile-hero">
      <view class="status-space" />
      <view class="hero-title">我的</view>
      <view class="identity-row">
        <view class="avatar"><text>●</text></view>
        <view class="identity-copy"><text class="nickname">微信用户</text><text class="user-id">用户ID：MYT_******</text></view>
        <view class="settings" @click="showReserved('设置')">⚙</view>
      </view>
    </view>

    <view class="member-card" @click="showReserved('会员详情')">
      <view><text class="member-mark">V</text><text> 普通会员 @ 微晓—板栗打印工作室</text></view>
      <view class="member-action">查看详情 ›</view>
    </view>

    <view class="menu-list">
      <view v-for="item in profileMenus" :key="item.label" class="menu-item" @click="showReserved(item.label)">
        <view class="menu-icon" :class="`tone-${item.tone}`">{{ item.icon }}</view>
        <text class="menu-label">{{ item.label }}</text>
        <text v-if="item.note" class="menu-note">{{ item.note }}</text>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <BottomNav active="profile" />
  </view>
</template>

<style scoped lang="scss">
.page-shell { min-height: 100vh; padding-bottom: 130rpx; background: #f6f6f6; }
.profile-hero { min-height: 310rpx; padding: 0 32rpx 42rpx; color: #fff; background: linear-gradient(150deg, #ffa13e 0%, #ffa33f 68%, #ffc356 100%); }.status-space { height: var(--status-bar-height); min-height: 36rpx; }.hero-title { height: 74rpx; line-height: 74rpx; text-align: center; font-size: 30rpx; }
.identity-row { display: flex; align-items: center; margin-top: 18rpx; }.avatar { display: flex; align-items: flex-end; justify-content: center; width: 112rpx; height: 112rpx; color: #3d3d3d; background: linear-gradient(#f9edcb 48%, #91bfbe 49%); border: 5rpx solid #fff6d1; border-radius: 50%; font-size: 66rpx; overflow: hidden; }.identity-copy { display: flex; flex: 1; margin-left: 22rpx; flex-direction: column; }.nickname { font-size: 34rpx; font-weight: 700; }.user-id { margin-top: 15rpx; font-size: 24rpx; }.settings { padding: 20rpx 4rpx 20rpx 20rpx; font-size: 45rpx; }
.member-card { position: relative; z-index: 2; display: flex; align-items: center; justify-content: space-between; min-height: 78rpx; margin: -34rpx 22rpx 16rpx; padding: 0 26rpx; color: #fff; background: linear-gradient(110deg, #545454, #6d6d6d); border-radius: 40rpx 40rpx 8rpx 8rpx; font-size: 22rpx; }.member-mark { font-size: 30rpx; font-style: italic; font-weight: 700; }.member-action { padding: 10rpx 18rpx; color: #7e6021; background: #fff1bd; border-radius: 24rpx; }
.menu-list { background: #fff; }.menu-item { display: flex; align-items: center; height: 92rpx; padding: 0 30rpx 0 44rpx; border-bottom: 1rpx solid #eeeeee; }.menu-icon { display: flex; align-items: center; justify-content: center; width: 42rpx; height: 42rpx; color: #ff8f34; font-size: 28rpx; font-weight: 700; }.tone-blue { color: #35afe7; }.tone-green { color: #28c886; }.menu-label { margin-left: 26rpx; color: #3e3e3e; font-size: 29rpx; }.menu-note { margin-left: auto; color: #999; font-size: 23rpx; }.menu-arrow { margin-left: 12rpx; color: #aaa; font-size: 38rpx; }
</style>
