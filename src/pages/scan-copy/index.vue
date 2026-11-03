<script setup lang="ts">
import { ref } from 'vue'

const IMAGE_SELECTION_KEY = 'miniapp-image-selection-v1'
const sourceSheetVisible = ref(false)
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
function openFileCopy() { sourceSheetVisible.value = true }
function closeSourceSheet() { sourceSheetVisible.value = false }
function acceptSelection(value: unknown) {
  const result = value as { tempFiles?: Array<{ path?: string; size?: number; name?: string }>; tempFilePaths?: string[] }
  const file = result.tempFiles?.[0]
  const path = file?.path || result.tempFilePaths?.[0] || ''
  if (!path) {
    uni.showToast({ title: '没有取得可上传的图片', icon: 'none' })
    return
  }
  const inferredName = decodeURIComponent(path.split('/').pop()?.split('?')[0] || 'scan-copy.jpg')
  uni.setStorageSync(IMAGE_SELECTION_KEY, {
    schemaVersion: 1,
    path,
    name: file?.name || inferredName,
    size: Number(file?.size || 0),
    purpose: 'document-copy'
  })
  uni.navigateTo({
    url: '/pages/image-upload/index',
    fail: () => uni.removeStorageSync(IMAGE_SELECTION_KEY)
  })
}
function chooseSource(label: '拍照' | '手机相册') {
  closeSourceSheet()
  // #ifdef MP-WEIXIN
  uni.chooseImage({ count: 1, sourceType: [label === '拍照' ? 'camera' : 'album'], success: acceptSelection })
  // #endif
  // #ifdef H5
  uni.chooseFile({ count: 1, type: 'image', extension: ['jpg', 'jpeg', 'png'], success: acceptSelection })
  // #endif
}
</script>

<template><view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>扫描复印</text><text class="spacer" /></view></view>
  <view class="file-card" @click="openFileCopy"><view class="round">文</view><view class="copy"><text class="name">文件复印</text><text class="description">将文件拍照或选择图片打印</text></view><text class="arrow">›</text></view>
  <view class="section-head"><text>证件复印</text><text>常用证件</text></view><view class="cert-grid"><view v-for="item in certificates" :key="item.label" class="cert" @click="reserve(item.label)"><view class="cert-icon">{{ item.icon }}</view><text>{{ item.label }}</text></view></view>
  <view class="combo-list"><view v-for="item in combos" :key="item.label" class="combo" @click="reserve(item.label)"><view class="combo-icon">{{ item.icon }}</view><view class="copy"><text class="name">{{ item.label }}</text><text class="description">{{ item.description }}</text></view><text class="arrow">›</text></view></view>
  <view class="boundary">文件复印已开放单张图片安全演示；证件组合排版仍待继续观察。</view>
  <view v-if="sourceSheetVisible" class="sheet-mask" @click="closeSourceSheet">
    <view class="source-sheet" @click.stop>
      <view class="warning">严禁上传反动、色情、暴力、非法及涉密资料</view>
      <!-- #ifdef MP-WEIXIN -->
      <view class="source-option" @click="chooseSource('拍照')"><text class="source-icon">◉</text><text>拍照</text></view>
      <view class="source-option" @click="chooseSource('手机相册')"><text class="source-icon">▧</text><text>手机相册</text></view>
      <!-- #endif -->
      <!-- #ifdef H5 -->
      <view class="source-option" @click="chooseSource('手机相册')"><text class="source-icon">▧</text><text>选择图片</text></view>
      <!-- #endif -->
      <view class="sheet-gap" />
      <view class="cancel" @click="closeSourceSheet">取消</view>
    </view>
  </view>
</view></template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:42rpx;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.file-card,.combo{display:flex;align-items:center;background:#fff;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05)}.file-card{margin:30rpx 28rpx 36rpx;padding:25rpx;border-radius:24rpx}.round{display:flex;align-items:center;justify-content:center;width:82rpx;height:82rpx;margin-right:22rpx;color:#f2a344;background:#fff8e9;border-radius:24rpx;font-weight:700}.copy{display:flex;min-width:0;flex:1;flex-direction:column}.name{font-size:29rpx;font-weight:650}.description{margin-top:9rpx;color:#999;font-size:22rpx}.arrow{color:#bbb;font-size:42rpx}.section-head{display:flex;justify-content:space-between;margin:0 30rpx 20rpx;font-size:31rpx;font-weight:650}.section-head text:last-child{color:#999;font-size:22rpx;font-weight:400}.cert-grid{display:grid;grid-template-columns:1fr 1fr;gap:18rpx;margin:0 28rpx}.cert{display:flex;align-items:center;flex-direction:column;justify-content:center;min-height:180rpx;background:#fff;border-radius:22rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05);font-size:25rpx;font-weight:600}.cert-icon{display:flex;align-items:center;justify-content:center;width:70rpx;height:56rpx;margin-bottom:18rpx;color:#ff9435;background:#fff5df;border-radius:10rpx;font-size:22rpx}.combo-list{display:flex;flex-direction:column;gap:18rpx;margin:22rpx 28rpx}.combo{padding:22rpx;border-radius:22rpx}.combo-icon{display:flex;align-items:center;justify-content:center;width:90rpx;height:58rpx;margin-right:18rpx;color:#ff8e31;background:#fff6e8;border-radius:12rpx;font-size:20rpx;font-weight:700}.boundary{margin:24rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
.sheet-mask{position:fixed;z-index:60;inset:0;display:flex;align-items:flex-end;background:rgba(0,0,0,.46)}
.source-sheet{box-sizing:border-box;width:100%;padding-bottom:calc(18rpx + env(safe-area-inset-bottom));overflow:hidden;background:#f2f2f2;border-radius:24rpx 24rpx 0 0}
.warning{padding:25rpx 30rpx;color:#f04d42;background:#fff;border-bottom:1rpx solid #eee;text-align:center;font-size:22rpx;line-height:34rpx}
.source-option{display:flex;align-items:center;justify-content:center;min-height:104rpx;gap:15rpx;color:#222;background:#fff;border-bottom:1rpx solid #eee;font-size:30rpx}
.source-icon{display:inline-flex;align-items:center;justify-content:center;width:36rpx;height:36rpx;color:#ff9634;font-size:24rpx;font-weight:700}
.sheet-gap{height:14rpx}
.cancel{min-height:104rpx;color:#333;background:#fff;text-align:center;font-size:30rpx;line-height:104rpx}
</style>
