<script setup lang="ts">
import BottomNav from '@/components/BottomNav.vue'
import { commonPrintEntries, morePrintEntries } from '@/data/fixtures'

function showReserved(label: string) {
  uni.showToast({ title: `${label}接口待接入`, icon: 'none' })
}

function openPrintEntry(label: string) {
  if (label === '文档打印') {
    uni.navigateTo({ url: '/pages/document-method/index' })
    return
  }
  if (label === '图片打印') {
    uni.navigateTo({ url: '/pages/image-method/index' })
    return
  }
  showReserved(label)
}
</script>

<template>
  <view class="page-shell">
    <view class="hero">
      <view class="status-space" />
      <view class="hero-title">云途智印</view>
      <view class="store-row">
        <view class="store-pill"><text class="pin">⌖</text> 板栗打印工作室 <text class="chevron">›</text></view>
        <view class="price-pill" @click="showReserved('价目表')">▤ 价目表 <text class="chevron">›</text></view>
      </view>
      <view class="store-detail">▣ 4号机</view>
      <view class="store-detail">● 惠州学院</view>
      <view class="notice">◖ 板栗打印一天多趟免费配送全校，送达通知请添加门店客服</view>
    </view>

    <view class="content">
      <view class="section-title"><text class="title-mark" />常见打印</view>
      <view class="common-grid">
        <view v-for="item in commonPrintEntries" :key="item.label" class="print-card" @click="openPrintEntry(item.label)">
          <view class="card-copy">
            <text class="card-title">{{ item.label }}</text>
            <text class="card-description">{{ item.description }}</text>
          </view>
          <view class="entry-icon" :class="`tone-${item.tone}`">{{ item.icon }}</view>
        </view>
      </view>

      <view class="section-title more-title"><text class="title-mark" />更多打印</view>
      <view class="more-grid">
        <view v-for="item in morePrintEntries" :key="item.label" class="more-item" @click="showReserved(item.label)">
          <view class="entry-icon large" :class="`tone-${item.tone}`">{{ item.icon }}</view>
          <text>{{ item.label }}</text>
        </view>
      </view>

      <view class="tutorial" @click="showReserved('新手教程')">
        <view class="entry-icon tone-orange">?</view>
        <view><text class="tutorial-title">新手教程</text><text class="tutorial-copy">第一次使用？从这里开始</text></view>
        <text class="tutorial-arrow">›</text>
      </view>
    </view>

    <BottomNav active="home" />
  </view>
</template>

<style scoped lang="scss">
.page-shell { min-height: 100vh; padding-bottom: 130rpx; }
.hero { min-height: 470rpx; padding: 0 32rpx 34rpx; color: #fff; background: linear-gradient(155deg, #ff9c3d 0%, #ffa445 58%, #ffd862 100%); border-radius: 0 0 20rpx 20rpx; }
.status-space { height: var(--status-bar-height); min-height: 36rpx; }
.hero-title { height: 76rpx; line-height: 76rpx; text-align: center; font-size: 30rpx; font-weight: 500; }
.store-row { display: flex; justify-content: space-between; gap: 20rpx; margin-top: 10rpx; }
.store-pill, .price-pill { display: flex; align-items: center; min-height: 66rpx; padding: 0 22rpx; background: rgba(229, 126, 32, 0.33); border-radius: 34rpx; font-size: 30rpx; font-weight: 600; }
.price-pill { flex-shrink: 0; font-weight: 400; }
.pin { margin-right: 6rpx; }.chevron { margin-left: 12rpx; font-size: 40rpx; font-weight: 300; }
.store-detail { margin-top: 22rpx; font-size: 28rpx; }
.notice { margin-top: 20rpx; font-size: 26rpx; line-height: 40rpx; }
.content { padding: 30rpx 20rpx 16rpx; }
.section-title { display: flex; align-items: center; margin: 2rpx 4rpx 22rpx; font-size: 34rpx; font-weight: 700; }
.title-mark { width: 7rpx; height: 34rpx; margin-right: 14rpx; background: #1fc8ec; border-radius: 6rpx; }
.common-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18rpx; }
.print-card { display: flex; align-items: flex-start; justify-content: space-between; min-height: 145rpx; padding: 25rpx 20rpx; background: #fff; border-radius: 12rpx; box-shadow: 0 5rpx 18rpx rgba(0, 0, 0, 0.08); }
.card-copy { display: flex; min-width: 0; flex-direction: column; }.card-title { font-size: 31rpx; font-weight: 700; }.card-description { margin-top: 16rpx; color: #888; font-size: 23rpx; white-space: nowrap; }
.entry-icon { display: flex; flex: 0 0 auto; align-items: center; justify-content: center; width: 56rpx; height: 56rpx; color: #ff9235; background: #fff2e5; border-radius: 50%; font-size: 27rpx; font-weight: 700; }
.tone-pink { color: #ff6e93; background: #fff0f5; }.tone-blue { color: #31aeea; background: #eaf8ff; }.tone-green { color: #28c997; background: #e8fbf5; }.tone-cyan { color: #22c6bc; background: #e6fbfa; }
.more-title { margin-top: 32rpx; }.more-grid { display: grid; grid-template-columns: repeat(4, 1fr); padding: 10rpx 4rpx 26rpx; }.more-item { display: flex; align-items: center; flex-direction: column; gap: 12rpx; color: #555; font-size: 23rpx; }.entry-icon.large { width: 74rpx; height: 74rpx; font-size: 32rpx; }
.tutorial { display: flex; align-items: center; gap: 18rpx; margin: 6rpx 4rpx; padding: 22rpx 24rpx; background: #fff; border-radius: 14rpx; }.tutorial > view:nth-child(2) { display: flex; flex: 1; flex-direction: column; }.tutorial-title { font-size: 29rpx; font-weight: 600; }.tutorial-copy { margin-top: 5rpx; color: #999; font-size: 22rpx; }.tutorial-arrow { color: #bbb; font-size: 42rpx; }
</style>
