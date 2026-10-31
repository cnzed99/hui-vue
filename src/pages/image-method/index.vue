<script setup lang="ts">
import { ref } from 'vue'

interface ImageMethod {
  id: 'image-print' | 'document-copy' | 'image-collage' | 'paper-renewal'
  label: string
  description: string
  icon: string
  tone: string
  action: 'source' | 'scan-copy' | 'paper-renewal'
}

const imageMethods: ImageMethod[] = [
  { id: 'image-print', label: '图片打印', description: 'AI智能高清、黑白、裁切等', icon: '▧', tone: 'pink', action: 'source' },
  { id: 'document-copy', label: '证件复印', description: '身份证、户口本、银行卡等证件复印', icon: '证', tone: 'blue', action: 'scan-copy' },
  { id: 'image-collage', label: '图片拼版打印', description: '最多将9张图片拼在一张纸上打印', icon: '拼', tone: 'orange', action: 'source' },
  { id: 'paper-renewal', label: '试卷翻新', description: '智能擦除手写字迹，还原空白卷', icon: '卷', tone: 'green', action: 'paper-renewal' }
]

const sourceSheetVisible = ref(false)

function goBack() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.reLaunch({ url: '/pages/home/index' })
}

function openMethod(method: ImageMethod) {
  if (method.action === 'scan-copy') {
    uni.navigateTo({ url: '/pages/scan-copy/index' })
    return
  }
  if (method.action === 'paper-renewal') {
    uni.navigateTo({ url: '/pages/paper-renewal/index' })
    return
  }
  sourceSheetVisible.value = true
}

function closeSourceSheet() {
  sourceSheetVisible.value = false
}

function chooseSource(label: string) {
  closeSourceSheet()
  uni.showToast({ title: `${label}暂未接入，不会上传`, icon: 'none' })
}
</script>

<template>
  <view class="image-page">
    <view class="header">
      <view class="status-space" />
      <view class="title-row"><text class="back" @click="goBack">‹</text><text>图片打印</text><text class="spacer" /></view>
    </view>

    <view class="method-list">
      <view v-for="method in imageMethods" :key="method.id" class="method-card" @click="openMethod(method)">
        <view class="method-icon" :class="`tone-${method.tone}`">{{ method.icon }}</view>
        <view class="method-copy">
          <text class="method-name">{{ method.label }}</text>
          <text class="method-description">{{ method.description }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
    </view>

    <view class="boundary-note">已确认的目录与跳转均可查看；来源按钮不会读取相册、拍照或上传文件。</view>

    <view v-if="sourceSheetVisible" class="sheet-mask" @click="closeSourceSheet">
      <view class="source-sheet" @click.stop>
        <view class="warning">严禁上传反动、色情、暴力、非法及涉密资料</view>
        <view class="source-option" @click="chooseSource('拍照')"><text class="source-icon">◉</text><text>拍照</text></view>
        <view class="source-option" @click="chooseSource('手机相册')"><text class="source-icon">▧</text><text>手机相册</text></view>
        <view class="source-option" @click="chooseSource('微信图片')"><text class="source-icon">微</text><text>微信图片</text></view>
        <view class="sheet-gap" />
        <view class="cancel" @click="closeSourceSheet">取消</view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.image-page { min-height: 100vh; padding-bottom: 48rpx; background: #f6f6f6; }
.header { color: #fff; background: linear-gradient(135deg, #ff9634, #ffad48); }
.status-space { height: var(--status-bar-height); min-height: 36rpx; }
.title-row { display: flex; align-items: center; justify-content: space-between; height: 86rpx; padding: 0 26rpx; font-size: 34rpx; font-weight: 600; }
.back, .spacer { width: 56rpx; }
.back { font-size: 56rpx; line-height: 56rpx; }
.method-list { display: flex; flex-direction: column; gap: 22rpx; padding: 30rpx 22rpx 0; }
.method-card { display: flex; align-items: center; min-height: 154rpx; padding: 22rpx 24rpx; background: #fff; border-radius: 15rpx; box-shadow: 0 5rpx 18rpx rgba(0, 0, 0, .06); }
.method-icon { display: flex; flex: 0 0 auto; align-items: center; justify-content: center; width: 82rpx; height: 82rpx; margin-right: 22rpx; border-radius: 50%; font-size: 30rpx; font-weight: 700; }
.tone-pink { color: #ff6e93; background: #fff0f5; }
.tone-blue { color: #35a7e5; background: #eaf7ff; }
.tone-orange { color: #ff8d31; background: #fff1e4; }
.tone-green { color: #29b987; background: #e9faf4; }
.method-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.method-name { color: #222; font-size: 31rpx; font-weight: 650; }
.method-description { margin-top: 13rpx; color: #999; font-size: 24rpx; line-height: 34rpx; }
.arrow { padding-left: 14rpx; color: #bbb; font-size: 44rpx; }
.boundary-note { margin: 28rpx 34rpx 0; color: #999; font-size: 22rpx; line-height: 36rpx; }
.sheet-mask { position: fixed; z-index: 60; inset: 0; display: flex; align-items: flex-end; background: rgba(0, 0, 0, .46); }
.source-sheet { box-sizing: border-box; width: 100%; padding-bottom: calc(18rpx + env(safe-area-inset-bottom)); overflow: hidden; background: #f2f2f2; border-radius: 24rpx 24rpx 0 0; }
.warning { padding: 25rpx 30rpx; color: #f04d42; background: #fff; border-bottom: 1rpx solid #eee; text-align: center; font-size: 22rpx; line-height: 34rpx; }
.source-option { display: flex; align-items: center; justify-content: center; min-height: 104rpx; gap: 15rpx; color: #222; background: #fff; border-bottom: 1rpx solid #eee; font-size: 30rpx; }
.source-icon { display: inline-flex; align-items: center; justify-content: center; width: 36rpx; height: 36rpx; color: #ff9634; font-size: 24rpx; font-weight: 700; }
.sheet-gap { height: 14rpx; }
.cancel { min-height: 104rpx; color: #333; background: #fff; text-align: center; font-size: 30rpx; line-height: 104rpx; }
</style>
