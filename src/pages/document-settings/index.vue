<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { loadDraft, saveDraft } from '@/domain/printDraft'
import type { PrintColor, PrintDraft, PrintSide } from '@/domain/printDraft'

const draft = ref<PrintDraft | null>(null)
const copies = ref(1)
const color = ref<PrintColor>('BLACK_WHITE')
const side = ref<PrintSide>('SIMPLEX')
const sizeText = computed(() => draft.value ? `${(draft.value.sizeBytes / 1024).toFixed(1)} KB` : '')

onLoad(() => {
  draft.value = loadDraft()
  if (!draft.value) uni.redirectTo({ url: '/pages/document-upload/index' })
  else {
    copies.value = draft.value.copies
    color.value = draft.value.color
    side.value = draft.value.side
  }
})

function goBack() { uni.navigateBack() }
function changeCopies(delta: number) { copies.value = Math.min(99, Math.max(1, copies.value + delta)) }
function continueToConfirm() {
  if (!draft.value) return
  const next = { ...draft.value, copies: copies.value, color: color.value, side: side.value }
  saveDraft(next)
  uni.navigateTo({ url: '/pages/order-confirm/index' })
}
</script>

<template>
  <view class="settings-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>打印设置</text><text class="spacer" /></view></view>
    <view v-if="draft" class="body">
      <view class="file-card"><view class="file-icon">PDF</view><view class="file-copy"><text>{{ draft.fileName }}</text><text>{{ sizeText }} · {{ draft.pages }} 页</text></view><text class="ready">{{ draft.source === 'SERVER_UPLOAD' ? '服务端解析完成' : '演示解析完成' }}</text></view>
      <view class="section"><text class="section-title">纸张规格</text><view class="choice-grid single"><view class="choice active"><text>A4</text><text>普通打印纸</text></view></view></view>
      <view class="section"><text class="section-title">打印颜色</text><view class="choice-grid"><view class="choice" :class="{ active: color === 'BLACK_WHITE' }" @click="color='BLACK_WHITE'"><text>黑白</text><text>经济清晰</text></view><view class="choice" :class="{ active: color === 'COLOR' }" @click="color='COLOR'"><text>彩色</text><text>保留原色</text></view></view></view>
      <view class="section"><text class="section-title">单双面</text><view class="choice-grid"><view class="choice" :class="{ active: side === 'SIMPLEX' }" @click="side='SIMPLEX'"><text>单面打印</text><text>每张纸打印 1 页</text></view><view class="choice" :class="{ active: side === 'DUPLEX' }" @click="side='DUPLEX'"><text>双面打印</text><text>每张纸最多 2 页</text></view></view></view>
      <view class="section copies-row"><view><text class="section-title">打印份数</text><text class="copies-note">份数计入该文件的总张数</text></view><view class="stepper"><text @click="changeCopies(-1)">−</text><text class="number">{{ copies }}</text><text @click="changeCopies(1)">＋</text></view></view>
      <view class="pricing-boundary">金额不在小程序本地计算；下一步由服务端按门店价格版本、每文件张数和阶梯规则生成报价。</view>
      <button class="primary" @click="continueToConfirm">确认参数</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.settings-page{min-height:100vh;background:#f6f6f6;padding-bottom:54rpx}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.body{padding:24rpx}.file-card{display:flex;align-items:center;padding:24rpx;background:#fff;border-radius:16rpx}.file-icon{display:flex;align-items:center;justify-content:center;width:72rpx;height:72rpx;color:#fff;background:#ee6f5f;border-radius:10rpx;font-size:20rpx;font-weight:700}.file-copy{display:flex;min-width:0;flex:1;flex-direction:column;margin-left:18rpx}.file-copy text:first-child{overflow:hidden;font-size:27rpx;font-weight:600;text-overflow:ellipsis;white-space:nowrap}.file-copy text:last-child{margin-top:8rpx;color:#999;font-size:21rpx}.ready{color:#2b9f79;font-size:21rpx}.section{margin-top:22rpx;padding:26rpx;background:#fff;border-radius:16rpx}.section-title{font-size:29rpx;font-weight:700}.choice-grid{display:grid;grid-template-columns:1fr 1fr;gap:18rpx;margin-top:22rpx}.choice-grid.single{grid-template-columns:1fr}.choice{display:flex;flex-direction:column;padding:22rpx;border:2rpx solid #eee;border-radius:12rpx}.choice text:first-child{font-size:27rpx;font-weight:600}.choice text:last-child{margin-top:8rpx;color:#999;font-size:21rpx}.choice.active{border-color:#ff9a36;background:#fff8ef}.choice.active text:first-child{color:#ee8424}.copies-row{display:flex;align-items:center;justify-content:space-between}.copies-note{display:block;margin-top:10rpx;color:#999;font-size:21rpx}.stepper{display:flex;align-items:center;overflow:hidden;border:1rpx solid #eee;border-radius:10rpx}.stepper text{display:flex;align-items:center;justify-content:center;width:62rpx;height:58rpx;font-size:28rpx}.stepper .number{width:72rpx;background:#fafafa;font-weight:700}.pricing-boundary{margin-top:24rpx;padding:20rpx;color:#8d6d43;background:#fff7e9;border-radius:12rpx;font-size:22rpx;line-height:36rpx}.primary{height:86rpx;margin-top:28rpx;color:#fff;background:linear-gradient(90deg,#ff9736,#ffad45);border-radius:44rpx;font-size:30rpx;line-height:86rpx}
</style>
