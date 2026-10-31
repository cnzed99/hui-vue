<script setup lang="ts">
const certificates = [
  { label: '身份证 / 银行卡', icon: '证' }, { label: '户口本复印', icon: '户' },
  { label: '营业执照', icon: '照' }, { label: '驾驶证 / 行驶证', icon: '驾' }
]
const combos = [
  { label: '身份证 + 户口本', description: '组合复印，减少重复操作', icon: '证+户' },
  { label: '身份证 + 银行卡', description: '组合复合，排版更省心', icon: '证+卡' }
]
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/home/index' }) }
function reserve(label: string) { uni.showToast({ title: `${label}选图接口待接入`, icon: 'none' }) }
</script>

<template><view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>扫描复印</text><text class="spacer" /></view></view>
  <view class="file-card" @click="reserve('文件复印')"><view class="round">文</view><view class="copy"><text class="name">文件复印</text><text class="description">将文件拍照或选择图片打印</text></view><text class="arrow">›</text></view>
  <view class="section-head"><text>证件复印</text><text>常用证件</text></view><view class="cert-grid"><view v-for="item in certificates" :key="item.label" class="cert" @click="reserve(item.label)"><view class="cert-icon">{{ item.icon }}</view><text>{{ item.label }}</text></view></view>
  <view class="combo-list"><view v-for="item in combos" :key="item.label" class="combo" @click="reserve(item.label)"><view class="combo-icon">{{ item.icon }}</view><view class="copy"><text class="name">{{ item.label }}</text><text class="description">{{ item.description }}</text></view><text class="arrow">›</text></view></view>
  <view class="boundary">本批只复刻已观察目录，不拍照、不选择证件图片。</view>
</view></template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:42rpx;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.file-card,.combo{display:flex;align-items:center;background:#fff;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05)}.file-card{margin:30rpx 28rpx 36rpx;padding:25rpx;border-radius:24rpx}.round{display:flex;align-items:center;justify-content:center;width:82rpx;height:82rpx;margin-right:22rpx;color:#f2a344;background:#fff8e9;border-radius:24rpx;font-weight:700}.copy{display:flex;min-width:0;flex:1;flex-direction:column}.name{font-size:29rpx;font-weight:650}.description{margin-top:9rpx;color:#999;font-size:22rpx}.arrow{color:#bbb;font-size:42rpx}.section-head{display:flex;justify-content:space-between;margin:0 30rpx 20rpx;font-size:31rpx;font-weight:650}.section-head text:last-child{color:#999;font-size:22rpx;font-weight:400}.cert-grid{display:grid;grid-template-columns:1fr 1fr;gap:18rpx;margin:0 28rpx}.cert{display:flex;align-items:center;flex-direction:column;justify-content:center;min-height:180rpx;background:#fff;border-radius:22rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05);font-size:25rpx;font-weight:600}.cert-icon{display:flex;align-items:center;justify-content:center;width:70rpx;height:56rpx;margin-bottom:18rpx;color:#ff9435;background:#fff5df;border-radius:10rpx;font-size:22rpx}.combo-list{display:flex;flex-direction:column;gap:18rpx;margin:22rpx 28rpx}.combo{padding:22rpx;border-radius:22rpx}.combo-icon{display:flex;align-items:center;justify-content:center;width:90rpx;height:58rpx;margin-right:18rpx;color:#ff8e31;background:#fff6e8;border-radius:12rpx;font-size:20rpx;font-weight:700}.boundary{margin:24rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
