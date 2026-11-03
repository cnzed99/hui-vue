<script setup lang="ts">
const articles = [
  { id: 'copy-clean-background', title: '复印（图片去灰底）' },
  { id: 'document-print', title: '文档打印' },
  { id: 'id-photo', title: '证件照制作' },
  { id: 'image-collage', title: '图片拼版' },
  { id: 'identity-card', title: '身份证打印' },
  { id: 'invoice', title: '电子发票打印' },
  { id: 'photo-paper', title: '照片纸打印' }
]

function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/home/index' }) }
function openArticle(id: string) {
  uni.navigateTo({ url: `/pages/tutorial-detail/index?article=${encodeURIComponent(id)}` })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>用户指南-文章列表</text><text class="spacer" /></view></view>
    <view class="articles">
      <view v-for="article in articles" :key="article.id" class="article" @click="openArticle(article.id)">
        <text>{{ article.title }}</text><text class="arrow">›</text>
      </view>
    </view>
    <view class="boundary">教程内容按原页面只读结构复刻；公众号外链仅说明来源，不跳转原站，也不提交业务数据。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#f5f5f5}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:32rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.articles{background:#fff}.article{display:flex;align-items:center;justify-content:space-between;min-height:94rpx;padding:0 24rpx 0 18rpx;color:#555;border-bottom:1rpx solid #eee;font-size:28rpx}.arrow{color:#bbb;font-size:40rpx}.boundary{padding:30rpx;color:#999;font-size:22rpx;line-height:34rpx;text-align:center}
</style>
