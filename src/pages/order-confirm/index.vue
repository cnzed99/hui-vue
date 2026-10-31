<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { loadDraft } from '@/domain/printDraft'
import type { PrintDraft } from '@/domain/printDraft'

const draft = ref<PrintDraft | null>(null)
const sideText = computed(() => draft.value?.side === 'DUPLEX' ? '双面' : '单面')
const colorText = computed(() => draft.value?.color === 'COLOR' ? '彩色' : '黑白')

onLoad(() => {
  draft.value = loadDraft()
  if (!draft.value) uni.redirectTo({ url: '/pages/document-upload/index' })
})

function goBack() { uni.navigateBack() }
function explainBoundary() {
  uni.showModal({ title: '服务端闭环建设中', content: '安全演示会依次接入短期演示会话、真实 PDF 上传解析、服务端报价、创建模拟订单和模拟支付。当前没有上传文件，也没有创建订单或扣款。', showCancel: false })
}
</script>

<template>
  <view class="confirm-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>确认订单</text><text class="spacer" /></view></view>
    <view v-if="draft" class="body">
      <view class="card file-card"><view class="file-icon">PDF</view><view class="file-copy"><text>{{ draft.fileName }}</text><text>{{ draft.pages }} 页 · {{ colorText }} · {{ sideText }} · {{ draft.copies }} 份</text></view></view>
      <view class="card rows"><view><text>纸张规格</text><text>A4</text></view><view><text>计价范围</text><text>本文件单独计算</text></view><view><text>份数</text><text>{{ draft.copies }}</text></view><view><text>金额</text><text class="pending">待服务端报价</text></view></view>
      <view class="notice"><text>当前进度</text><text>页面参数闭环已完成；文件仍未上传，订单仍未创建，支付仍未发生。</text></view>
      <button class="disabled" @click="explainBoundary">等待安全服务端接口开放</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.confirm-page{min-height:100vh;background:#f6f6f6}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.body{padding:24rpx}.card{margin-bottom:22rpx;padding:26rpx;background:#fff;border-radius:16rpx}.file-card{display:flex;align-items:center}.file-icon{display:flex;align-items:center;justify-content:center;width:72rpx;height:72rpx;color:#fff;background:#ef6e5f;border-radius:10rpx;font-size:20rpx;font-weight:700}.file-copy{display:flex;min-width:0;flex:1;flex-direction:column;margin-left:18rpx}.file-copy text:first-child{overflow:hidden;font-size:27rpx;font-weight:600;text-overflow:ellipsis;white-space:nowrap}.file-copy text:last-child{margin-top:10rpx;color:#999;font-size:22rpx}.rows view{display:flex;justify-content:space-between;padding:22rpx 0;border-bottom:1rpx solid #eee;font-size:25rpx}.rows view:last-child{border-bottom:0}.rows view text:last-child{font-weight:600}.rows .pending{color:#ee8528}.notice{display:flex;flex-direction:column;padding:22rpx;color:#8d6d43;background:#fff7e9;border-radius:12rpx;font-size:22rpx;line-height:36rpx}.notice text:first-child{font-weight:700}.disabled{height:86rpx;margin-top:28rpx;color:#888;background:#e7e9eb;border-radius:44rpx;font-size:28rpx;line-height:86rpx}
</style>
