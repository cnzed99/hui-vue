<script setup lang="ts">
import { ref } from 'vue'
import { onUnload } from '@dcloudio/uni-app'

const selectionKey = 'miniapp-image-selection-v1'
const sourceSheetVisible = ref(false)
let unloaded = false
onUnload(() => { unloaded = true })

function goBack() {
  if (sourceSheetVisible.value) {
    sourceSheetVisible.value = false
    return
  }
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/scan-copy/index' })
}

function acceptSelection(value: unknown) {
  if (unloaded) return
  const result = value as { tempFiles?: Array<{ path?: string; size?: number; name?: string }>; tempFilePaths?: string[] }
  const file = result.tempFiles?.[0]
  const path = file?.path || result.tempFilePaths?.[0]
  if (!path) {
    uni.showToast({ title: '没有取得可上传的图片', icon: 'none' })
    return
  }
  const pathName = path.split('/').pop()?.split('?')[0] || ''
  let inferredName = 'scan-copy.jpg'
  try { inferredName = decodeURIComponent(pathName) || inferredName } catch { /* Keep the fallback file name. */ }
  try {
    uni.setStorageSync(selectionKey, {
      schemaVersion: 1, path, name: file?.name || inferredName, size: Number(file?.size || 0), purpose: 'document-copy'
    })
  } catch {
    uni.showToast({ title: '无法暂存所选图片，请重试', icon: 'none' })
    return
  }
  uni.navigateTo({ url: '/pages/image-upload/index', fail: () => uni.removeStorageSync(selectionKey) })
}

function chooseSource(source: 'camera' | 'album') {
  if (!sourceSheetVisible.value) return
  sourceSheetVisible.value = false
  // #ifdef MP-WEIXIN
  uni.chooseImage({ count: 1, sourceType: [source], success: acceptSelection })
  // #endif
  // #ifdef H5
  uni.chooseFile({ count: 1, type: 'image', extension: ['jpg', 'jpeg', 'png'], success: acceptSelection })
  // #endif
}
</script>

<template>
  <view class="guide">
    <view class="header">
      <button class="back" aria-label="返回扫描复印" @click="goBack">‹</button>
      <text>文件复印</text>
      <view class="spacer" />
    </view>
    <view class="hero">
      <text class="heading">智能文件复印</text>
      <text class="tagline">手机拍文件 拍完就打印</text>
      <view class="illustration" aria-hidden="true">
        <view class="paper">
          <view class="paper-heading" />
          <view class="paper-line" />
          <view class="paper-line short" />
          <view class="paper-line" />
          <view class="paper-line short" />
        </view>
        <view class="corner top-left" />
        <view class="corner top-right" />
        <view class="corner bottom-left" />
        <view class="corner bottom-right" />
      </view>
    </view>
    <view class="details">
      <text class="advice">为了您的复印效果，建议将文件放置在白色的背景下拍摄</text>
      <text class="boundary">当前支持单张照片转 A4 PDF，自动裁边和透视矫正暂未开放。</text>
    </view>
    <view class="footer">
      <button class="start" @click="sourceSheetVisible = true">开始使用</button>
    </view>

    <view v-if="sourceSheetVisible" class="sheet-mask" @click="sourceSheetVisible = false">
      <view class="source-sheet" @click.stop>
        <view class="warning">严禁上传反动、色情、暴力、非法及涉密资料</view>
        <!-- #ifdef MP-WEIXIN -->
        <button class="source-option" @click="chooseSource('camera')">拍照</button>
        <button class="source-option" @click="chooseSource('album')">手机相册</button>
        <!-- #endif -->
        <!-- #ifdef H5 -->
        <button class="source-option" @click="chooseSource('album')">选择图片</button>
        <!-- #endif -->
        <button class="cancel" @click="sourceSheetVisible = false">取消</button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.guide { min-height: 100vh; display: flex; flex-direction: column; background: #fff; color: #222; }
.header {
  display: flex; align-items: center; justify-content: space-between;
  padding: calc(12rpx + var(--status-bar-height)) 24rpx 12rpx;
  background: #ff9f3d; color: #fff; font-size: 34rpx; font-weight: 600;
}
.back, .spacer { box-sizing: border-box; width: 64rpx; height: 64rpx; }
.back { margin: 0; padding: 0; border: 0; background: transparent; color: #fff; font-size: 52rpx; line-height: 60rpx; }
.back::after, .source-option::after, .cancel::after { border: 0; }
.hero {
  display: flex; flex-direction: column; align-items: center; padding: 42rpx 30rpx 52rpx;
  background: linear-gradient(180deg, #ff9f3d 0%, #ffb25c 100%); color: #fff;
}
.heading { font-size: 54rpx; font-weight: 750; }
.tagline { margin-top: 12rpx; font-size: 28rpx; }
.illustration { position: relative; display: grid; place-items: center; width: 340rpx; height: 330rpx; margin-top: 34rpx; }
.paper { box-sizing: border-box; width: 178rpx; height: 236rpx; padding: 46rpx 25rpx; border-radius: 12rpx; background: #fff; box-shadow: 0 12rpx 34rpx #c2692c50; transform: rotate(-8deg); }
.paper-heading, .paper-line { height: 8rpx; margin-bottom: 16rpx; border-radius: 6rpx; background: #f2d4b4; }
.paper-heading { width: 70%; margin-bottom: 24rpx; background: #ef9c4d; }
.paper-line.short { width: 76%; }
.corner { position: absolute; width: 48rpx; height: 48rpx; border: 6rpx solid #fff; }
.top-left { top: 14rpx; left: 26rpx; border-right: 0; border-bottom: 0; }
.top-right { top: 14rpx; right: 26rpx; border-left: 0; border-bottom: 0; }
.bottom-left { bottom: 14rpx; left: 26rpx; border-right: 0; border-top: 0; }
.bottom-right { bottom: 14rpx; right: 26rpx; border-left: 0; border-top: 0; }
.details { display: flex; flex-direction: column; align-items: center; gap: 20rpx; padding: 38rpx 40rpx; text-align: center; }
.advice { font-size: 26rpx; line-height: 40rpx; }
.boundary { color: #666; font-size: 22rpx; line-height: 36rpx; }
.footer { margin-top: auto; padding: 24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom)); }
.start { margin: 0; border-radius: 44rpx; background: #ff9f3d; color: #27211b; font-size: 30rpx; font-weight: 650; }
.sheet-mask { position: fixed; z-index: 60; inset: 0; display: flex; align-items: flex-end; background: rgba(0, 0, 0, .46); }
.source-sheet { box-sizing: border-box; width: 100%; padding-bottom: calc(18rpx + env(safe-area-inset-bottom)); overflow: hidden; background: #f2f2f2; border-radius: 24rpx 24rpx 0 0; }
.warning { padding: 25rpx 30rpx; color: #ad3b2f; background: #fff; text-align: center; font-size: 22rpx; }
.source-option, .cancel { margin: 0; border-radius: 0; border-top: 1rpx solid #eee; background: #fff; color: #222; font-size: 30rpx; line-height: 104rpx; }
.cancel { margin-top: 14rpx; }
</style>
