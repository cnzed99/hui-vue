<script setup lang="ts">
import { sizes } from '@/domain/idPhotoSizes'
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/home/index' }) }
function chooseSize(id: typeof sizes[number]['id']) { uni.navigateTo({ url: '/pages/id-photo-upload/index?size=' + id }) }
</script>

<template><view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>普通证件照</text><text class="spacer" /></view></view><view class="section-title">常用尺寸</view><view class="grid"><view v-for="item in sizes" :key="item.id" class="size" @click="chooseSize(item.id)"><view class="portrait"><view class="head" /><view class="body" /></view><text class="name">{{ item.name }}</text><text class="mm">{{ item.width }}*{{ item.height }}mm</text></view></view></view></template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:40rpx;background:#fff}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.section-title{padding:45rpx 0 34rpx;text-align:center;font-size:34rpx;font-weight:650}.grid{display:grid;grid-template-columns:repeat(3,1fr);row-gap:50rpx;padding:0 42rpx}.size{display:flex;align-items:center;flex-direction:column}.portrait{position:relative;width:90rpx;height:112rpx;overflow:hidden;background:#9ed0f4}.head{position:absolute;top:18rpx;left:28rpx;width:34rpx;height:42rpx;background:#ffd6bc;border-radius:50%}.body{position:absolute;right:12rpx;bottom:-9rpx;left:12rpx;height:68rpx;background:#fff;border-radius:36rpx 36rpx 0 0}.name{margin-top:12rpx;color:#555;font-size:25rpx}.mm{margin-top:8rpx;color:#999;font-size:20rpx}.boundary{margin:50rpx 38rpx;color:#aaa;text-align:center;font-size:22rpx;line-height:36rpx}
</style>
