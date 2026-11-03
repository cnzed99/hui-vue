<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

type TutorialStep = { number: number; text: string }
type TutorialArticle = {
  id: string
  title: string
  publishedAt: string
  kind: 'steps' | 'external-video'
  steps: TutorialStep[]
}

const tutorials: Record<string, TutorialArticle> = {
  'copy-clean-background': { id: 'copy-clean-background', title: '复印（图片去灰底）', publishedAt: '2026-01-30 12:29:48', kind: 'external-video', steps: [] },
  'document-print': { id: 'document-print', title: '文档打印', publishedAt: '2026-01-30 12:21:16', kind: 'external-video', steps: [] },
  'id-photo': { id: 'id-photo', title: '证件照制作', publishedAt: '2026-01-30 12:20:29', kind: 'external-video', steps: [] },
  'image-collage': { id: 'image-collage', title: '图片拼版', publishedAt: '2026-01-30 12:18:01', kind: 'external-video', steps: [] },
  'identity-card': { id: 'identity-card', title: '身份证打印', publishedAt: '2026-01-30 12:16:56', kind: 'external-video', steps: [] },
  invoice: {
    id: 'invoice', title: '电子发票打印', publishedAt: '2021-03-09 15:29:15', kind: 'steps',
    steps: [
      { number: 1, text: '扫码进入小程序后，选择电子发票功能' },
      { number: 2, text: '选择发票文件来源（文件只支持PDF格式）' },
      { number: 3, text: '上传文件' },
      { number: 4, text: '设置打印参数' },
      { number: 5, text: '下单付款后，打印机执行打印任务' }
    ]
  },
  'photo-paper': {
    id: 'photo-paper', title: '照片纸打印', publishedAt: '2021-03-09 15:24:19', kind: 'steps',
    steps: [
      { number: 1, text: '扫码进入小程序后，选择照片纸打印' },
      { number: 2, text: '选择要打印的照片' },
      { number: 3, text: '上传照片' },
      { number: 4, text: '下单付款后，打印机执行打印任务' }
    ]
  }
}

const selectedArticle = ref<TutorialArticle>(tutorials['photo-paper'])
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/tutorial-list/index' }) }
onLoad(query => {
  const article = tutorials[String(query?.article || '')]
  if (!article) { uni.redirectTo({ url: '/pages/tutorial-list/index' }); return }
  selectedArticle.value = article
})
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>{{ selectedArticle.title }}</text><text class="spacer" /></view></view>
    <view class="article">
      <text class="article-title">{{ selectedArticle.title }}</text>
      <text class="date">发布日期：{{ selectedArticle.publishedAt }}</text>
      <view class="history">历史指南仅用于还原页面结构，流程和截图可能已过时；其中展示的旧金额不作为新系统价格。</view>
      <view v-if="selectedArticle.kind === 'external-video'" class="external-card">
        <text class="external-icon">▶</text>
        <text class="external-title">原版为公众号外链视频教程</text>
        <text class="external-copy">原文章页没有公开文字步骤。新系统保留教程类型和发布日期，但不复用原公众号链接或视频；正式教程内容待运营方提供。</text>
      </view>
      <view v-for="step in selectedArticle.steps" v-else :key="step.number" class="step">
        <view class="number">{{ step.number }}</view>
        <view class="copy"><text class="step-title">{{ step.text }}</text></view>
      </view>
      <view class="boundary">本页无上传、下单、付款或外部跳转。教程只读展示不代表对应打印业务已经完成或通过验收。</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:32rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.article{display:flex;flex-direction:column;padding:34rpx 26rpx 60rpx}.article-title{text-align:center;font-size:36rpx;font-weight:700}.date{margin-top:14rpx;color:#888;text-align:center;font-size:23rpx}.history{margin-top:26rpx;padding:22rpx;color:#9a6a21;background:#fff5df;border:1rpx solid #ffd99b;border-radius:12rpx;font-size:23rpx;line-height:36rpx}.step{display:flex;gap:20rpx;margin-top:24rpx;padding:24rpx;background:#fff;border-radius:14rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05)}.number{display:flex;align-items:center;justify-content:center;width:52rpx;height:52rpx;flex:0 0 52rpx;color:#fff;background:#ff9f3d;border-radius:50%;font-size:26rpx;font-weight:700}.copy{display:flex;min-width:0;flex:1;flex-direction:column;justify-content:center}.step-title{font-size:28rpx;font-weight:650;line-height:42rpx}.external-card{display:flex;align-items:center;flex-direction:column;margin-top:24rpx;padding:44rpx 30rpx;background:#fff;border-radius:14rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05)}.external-icon{display:flex;align-items:center;justify-content:center;width:92rpx;height:92rpx;color:#6c5ce7;border:6rpx solid #333;border-radius:50%;font-size:44rpx}.external-title{margin-top:28rpx;font-size:30rpx;font-weight:700}.external-copy{margin-top:16rpx;color:#777;font-size:24rpx;line-height:38rpx;text-align:center}.boundary{margin-top:28rpx;color:#999;font-size:22rpx;line-height:35rpx;text-align:center}
</style>
