<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'

type CertificateMode = 'business-license' | 'driver-license' | 'identity-card' | 'household-register' | 'identity-bank'
type Slot = { step?: number; side?: string; action: string }

const mode = ref<CertificateMode>('business-license')
const pageConfig: Record<CertificateMode, { title: string; hint: string; slots: Slot[]; boundarySubject: string }> = {
  'business-license': {
    title: '添加营业执照',
    hint: '证件与拍摄背景的颜色请尽量区分开',
    slots: [{ action: '添加营业执照' }],
    boundarySubject: '营业执照'
  },
  'driver-license': {
    title: '添加证件',
    hint: '证件与拍摄背景的颜色请尽量区分开',
    slots: [
      { step: 1, side: '正面', action: '添加行驶证或驾驶证正面' },
      { step: 2, side: '反面', action: '添加行驶证或驾驶证反面' }
    ],
    boundarySubject: '驾驶证或行驶证'
  },
  'identity-card': {
    title: '添加身份证',
    hint: '可一次选择最多2张身份证照片，按正面、反面顺序添加',
    slots: [
      { step: 1, side: '正面', action: '添加正面照' },
      { step: 2, side: '反面', action: '添加背面照' }
    ],
    boundarySubject: '身份证正反面'
  },
  'household-register': {
    title: '添加户口本',
    hint: '根据需要添加户口本，常用是2张，最多支持4张',
    slots: [
      { step: 1, action: '添加户口本页' },
      { step: 2, action: '添加户口本页' },
      { step: 3, action: '添加户口本页' },
      { step: 4, action: '添加户口本页' }
    ],
    boundarySubject: '户口本内页'
  },
  'identity-bank': {
    title: '添加身份证+银行卡',
    hint: '证件与拍摄背景的颜色请尽量区分开',
    slots: [
      { step: 1, action: '添加身份证正面照' },
      { step: 2, action: '添加身份证背面照' },
      { step: 3, action: '添加银行卡正面照' },
      { step: 4, action: '添加银行卡背面照' }
    ],
    boundarySubject: '身份证与银行卡正反面'
  }
}
const config = computed(() => pageConfig[mode.value])
const isSingleCard = computed(() => mode.value === 'business-license')

onLoad(query => {
  const value = query?.type
  mode.value = value === 'driver-license' || value === 'identity-card' || value === 'household-register' || value === 'identity-bank'
    ? value
    : 'business-license'
})

function goBack() {
  if (getCurrentPages().length > 1) { uni.navigateBack(); return }
  uni.reLaunch({ url: '/pages/scan-copy/index' })
}
function explainClosed(action: string) {
  uni.showToast({ title: `${action}尚未接入`, icon: 'none' })
}
function showSwapAfter(index: number) {
  if (isSingleCard.value) return false
  return index < config.value.slots.length - 1
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>{{ config.title }}</text><text class="spacer" /></view></view>
    <view class="hint">{{ config.hint }}</view>
    <template v-for="(item, index) in config.slots" :key="`${item.action}-${index}`">
      <view class="upload-card" :class="{ single: isSingleCard }" @click="explainClosed(item.action)">
        <view v-if="item.step" class="meta"><text>{{ item.step }}</text><text v-if="item.side" class="side">{{ item.side }}</text></view>
        <view class="plus">＋</view><text class="action">{{ item.action }}</text><text class="description">添加清晰原图，自动识别证件边缘</text>
      </view>
      <button v-if="showSwapAfter(index)" class="swap" @click="explainClosed('证件顺序交换')">↕ 交换顺序</button>
    </template>
    <view class="boundary">当前仅复刻已观察的{{ config.boundarySubject }}图片位置；不会打开相机、相册或微信图片，不上传、识别、保存个人证件资料，也未接入排版、计价、订单或打印。</view>
    <view class="footer"><button @click="explainClosed('证件上传与识别')">下一步</button></view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:150rpx;background:#f7f7f7}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 22rpx;font-size:30rpx;font-weight:600}.back,.spacer{width:48rpx}.back{font-size:56rpx;line-height:56rpx}.hint{margin:18rpx 28rpx 22rpx;padding:15rpx 20rpx;color:#8d6f58;background:#fffaf4;border:1rpx solid #f3dcc4;border-radius:34rpx;text-align:center;font-size:23rpx}.upload-card{position:relative;display:flex;align-items:center;flex-direction:column;justify-content:center;box-sizing:border-box;min-height:390rpx;margin:0 28rpx 18rpx;padding:60rpx 18rpx 42rpx;background:#fff;border:2rpx dashed #f1dfcc;border-radius:22rpx}.upload-card.single{min-height:660rpx}.meta{position:absolute;top:20rpx;right:20rpx;left:20rpx;display:flex;align-items:center;color:#ee973c;font-size:23rpx;font-weight:650}.meta .side{margin-left:auto;padding:7rpx 17rpx;color:#fff;background:#f4a650;border-radius:20rpx}.plus{display:flex;align-items:center;justify-content:center;width:78rpx;height:78rpx;margin-bottom:18rpx;color:#efb365;border:5rpx solid #f8dfbd;border-radius:50%;font-size:50rpx;line-height:78rpx}.action{color:#161616;font-size:29rpx;font-weight:650}.description{margin-top:12rpx;color:#aaa;font-size:22rpx}.swap{height:66rpx;margin:-4rpx auto 18rpx;padding:0 32rpx;color:#ee9a44;background:#fff;border-radius:34rpx;box-shadow:0 4rpx 14rpx rgba(0,0,0,.06);font-size:23rpx;line-height:66rpx}.swap::after{border:0}.boundary{margin:28rpx 34rpx;padding:20rpx 22rpx;color:#986c3a;background:#fff7e9;border-radius:12rpx;font-size:21rpx;line-height:34rpx}.footer{position:fixed;right:0;bottom:0;left:0;padding:14rpx 28rpx calc(14rpx + env(safe-area-inset-bottom));background:#fff;border-top:1rpx solid #eee}.footer button{height:82rpx;color:#fff;background:#ff9f3d;border-radius:42rpx;font-size:30rpx;line-height:82rpx}.footer button::after{border:0}
</style>
