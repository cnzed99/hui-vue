<script setup lang="ts">
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/home/index' }) }
function addPhoto() { uni.showToast({ title: '照片选择接口待接入', icon: 'none' }) }
function submit() { uni.showToast({ title: '请先添加照片', icon: 'none' }) }
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>3寸照片上传</text><text class="spacer" /></view></view>
    <view class="empty"><view class="empty-icon">⌒</view><text>暂无照片哦~</text></view>
    <view class="boundary">本页不会打开相册、拍照或上传照片；0/30 为原页面已观察上限展示。</view>
    <view class="footer"><view class="upload" @click="addPhoto">上传照片(0/30)</view><view class="submit" @click="submit">提交打印</view></view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#fff}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.empty{display:flex;align-items:center;flex-direction:column;justify-content:center;min-height:720rpx;color:#333;font-size:26rpx}.empty-icon{display:flex;align-items:center;justify-content:center;width:150rpx;height:110rpx;margin-bottom:24rpx;color:#cfd7df;background:#f3f6f8;border-radius:44rpx;font-size:70rpx}.boundary{margin:0 38rpx 145rpx;color:#aaa;font-size:22rpx;line-height:35rpx;text-align:center}.footer{position:fixed;right:0;bottom:0;left:0;display:grid;grid-template-columns:1fr 1fr;gap:20rpx;padding:14rpx 24rpx calc(14rpx + env(safe-area-inset-bottom));background:#fff;border-top:1rpx solid #eee}.upload,.submit{height:86rpx;border-radius:10rpx;text-align:center;font-size:29rpx;line-height:86rpx}.upload{color:#ff9a3d;border:2rpx solid #ff9a3d}.submit{color:#fff;background:#d8d8d8}
</style>
