<script setup lang="ts">
const slots = [
  { step: 1, group: '身份证', side: '正面', action: '添加身份证正面照' },
  { step: 2, group: '身份证', side: '反面', action: '添加身份证背面照' },
  { step: 3, group: '户口本', side: '', action: '添加户口本内页' },
  { step: 4, group: '户口本', side: '', action: '添加户口本内页' }
] as const

function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/scan-copy/index' })
}
function explainClosed(action: string) {
  uni.showToast({ title: `${action}尚未接入`, icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>身份证+户口本组合复印</text><text class="spacer" /></view></view>
    <view class="hint">证件与拍摄背景的颜色请尽量区分开</view>
    <view class="section-title">身份证照片</view>
    <view v-for="item in slots.slice(0, 2)" :key="item.step" class="upload-card" @click="explainClosed(item.action)">
      <view class="meta"><text>{{ item.step }}</text><text>{{ item.group }}</text><text class="side">{{ item.side }}</text></view>
      <view class="plus">＋</view><text class="action">{{ item.action }}</text><text class="description">添加清晰原图，自动识别证件边缘</text>
    </view>
    <view class="section-title household">户口本照片</view>
    <view v-for="item in slots.slice(2)" :key="item.step" class="upload-card" @click="explainClosed(item.action)">
      <view class="meta"><text>{{ item.step }}</text><text>{{ item.group }}</text></view>
      <view class="plus">＋</view><text class="action">{{ item.action }}</text><text class="description">添加清晰原图，自动识别证件边缘</text>
    </view>
    <view class="boundary">当前仅复刻已观察的4个证件图片位置；不会打开相机、相册或微信图片，不上传、识别、保存个人证件资料，也未接入排版、计价、订单或打印。</view>
    <view class="footer"><button @click="explainClosed('证件上传与识别')">下一步</button></view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:150rpx;background:#f7f7f7}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 22rpx;font-size:29rpx;font-weight:600}.back,.spacer{width:48rpx}.back{font-size:56rpx;line-height:56rpx}.hint{margin:18rpx 28rpx 22rpx;padding:15rpx 20rpx;color:#8d6f58;background:#fffaf4;border:1rpx solid #f3dcc4;border-radius:34rpx;text-align:center;font-size:23rpx}.section-title{margin:0 28rpx 16rpx;color:#222;font-size:30rpx;font-weight:650}.section-title.household{margin-top:28rpx}.upload-card{position:relative;display:flex;align-items:center;flex-direction:column;justify-content:center;box-sizing:border-box;min-height:285rpx;margin:0 28rpx 18rpx;padding:52rpx 18rpx 32rpx;background:#fff;border:2rpx dashed #f1dfcc;border-radius:22rpx}.meta{position:absolute;top:20rpx;right:20rpx;left:20rpx;display:flex;align-items:center;gap:18rpx;color:#f19a3e;font-size:23rpx;font-weight:650}.meta text:first-child{color:#ee973c}.meta .side{margin-left:auto;padding:7rpx 17rpx;color:#fff;background:#f4a650;border-radius:20rpx}.plus{display:flex;align-items:center;justify-content:center;width:78rpx;height:78rpx;margin-bottom:18rpx;color:#efb365;border:5rpx solid #f8dfbd;border-radius:50%;font-size:50rpx;line-height:78rpx}.action{color:#161616;font-size:29rpx;font-weight:650}.description{margin-top:12rpx;color:#aaa;font-size:22rpx}.boundary{margin:28rpx 34rpx;color:#986c3a;background:#fff7e9;border-radius:12rpx;padding:20rpx 22rpx;font-size:21rpx;line-height:34rpx}.footer{position:fixed;right:0;bottom:0;left:0;padding:14rpx 28rpx calc(14rpx + env(safe-area-inset-bottom));background:#fff;border-top:1rpx solid #eee}.footer button{height:82rpx;color:#fff;background:#c9c9c9;border-radius:42rpx;font-size:30rpx;line-height:82rpx}.footer button::after{border:0}
</style>
