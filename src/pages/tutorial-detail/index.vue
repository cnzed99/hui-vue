<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'

const steps = [
  { number: 1, title: '进入照片纸打印', description: '从首页选择照片冲印／照片纸打印入口。' },
  { number: 2, title: '选择照片来源', description: '历史指南展示“手机相册照片打印”和“微信聊天照片打印”两个来源。' },
  { number: 3, title: '检查已选照片', description: '照片卡片展示编辑、删除和份数减／加，默认份数为1；底部可继续添加照片。' },
  { number: 4, title: '确认下单', description: '历史指南展示现场立即打印、远程预约打印、订单详情和应付金额；新系统金额必须以后端权威报价为准。' }
]

function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/tutorial-list/index' }) }
onLoad(query => {
  if (query?.article !== 'photo-paper') uni.redirectTo({ url: '/pages/tutorial-list/index' })
})
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>照片纸打印</text><text class="spacer" /></view></view>
    <view class="article">
      <text class="article-title">照片纸打印</text>
      <text class="date">发布日期：2021-03-09 15:24:19</text>
      <view class="history">历史指南仅用于还原页面结构，流程和截图可能已过时；其中展示的旧金额不作为新系统价格。</view>
      <view v-for="step in steps" :key="step.number" class="step">
        <view class="number">{{ step.number }}</view>
        <view class="copy"><text class="step-title">{{ step.title }}</text><text class="description">{{ step.description }}</text></view>
      </view>
      <view class="boundary">本页无上传、下单、付款或外部链接。照片纸排版、权威计价和打印闭环仍待设计与验证。</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:32rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.article{display:flex;flex-direction:column;padding:34rpx 26rpx 60rpx}.article-title{text-align:center;font-size:36rpx;font-weight:700}.date{margin-top:14rpx;color:#888;text-align:center;font-size:23rpx}.history{margin-top:26rpx;padding:22rpx;color:#9a6a21;background:#fff5df;border:1rpx solid #ffd99b;border-radius:12rpx;font-size:23rpx;line-height:36rpx}.step{display:flex;gap:20rpx;margin-top:24rpx;padding:24rpx;background:#fff;border-radius:14rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05)}.number{display:flex;align-items:center;justify-content:center;width:52rpx;height:52rpx;color:#fff;background:#ff9f3d;border-radius:50%;font-size:26rpx;font-weight:700}.copy{display:flex;min-width:0;flex:1;flex-direction:column}.step-title{font-size:29rpx;font-weight:650}.description{margin-top:10rpx;color:#777;font-size:24rpx;line-height:38rpx}.boundary{margin-top:28rpx;color:#999;font-size:22rpx;line-height:35rpx;text-align:center}
</style>
