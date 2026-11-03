<script setup lang="ts">
import { ref } from 'vue'
import ArchiveEmptyState from '@/components/ArchiveEmptyState.vue'
const tabs = ['已收藏', '已购买']
const active = ref('已收藏')
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/profile/index' }) }
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>我的文库</text><text class="spacer" /></view></view>
    <view class="tabs"><view v-for="tab in tabs" :key="tab" class="tab" :class="{ active: active === tab }" @click="active = tab">{{ tab }}</view></view>
    <ArchiveEmptyState text="暂无文件哦~" />
    <view class="boundary">{{ active }}列表将在微信登录与文库权益接口接入后加载。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f7f7f7}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.tabs{display:grid;grid-template-columns:repeat(2,1fr);height:92rpx;background:#fff}.tab{position:relative;display:flex;align-items:center;justify-content:center;font-size:28rpx}.tab.active{color:#ee8c21}.tab.active::after{position:absolute;right:80rpx;bottom:0;left:80rpx;height:3rpx;background:#ee8c21;content:''}.boundary{margin:52rpx 34rpx 0;color:#999;text-align:center;font-size:22rpx;line-height:36rpx}
</style>
