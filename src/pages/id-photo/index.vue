<script setup lang="ts">
const entries = [
  { id: 'smart', label: '智能证件照', note: '（没有证件照选我）', description: '电子版保存/打印、智能换底、换装', icon: '智', preview: true },
  { id: 'normal', label: '普通证件照', note: '（已有证件照选我）', description: '将已有的证件照排版打印', icon: '▦', preview: true },
  { id: 'marriage', label: '结婚证件照', note: '', description: '将婚姻的美好记录打印出来', icon: '囍', preview: false },
  { id: 'vehicle', label: '车辆行驶证', note: '', description: '为您的车辆制作证件照', icon: '车', preview: false }
]
function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/home/index' }) }
function openEntry(id: string, label: string) {
  if (id === 'normal') { uni.navigateTo({ url: '/pages/id-photo-sizes/index' }); return }
  uni.showToast({ title: `${label}待继续观察`, icon: 'none' })
}
</script>

<template><view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>证件照</text><text class="spacer" /></view></view>
  <view class="list"><view v-for="item in entries" :key="item.id" class="card" @click="openEntry(item.id,item.label)"><view class="row"><view class="icon">{{ item.icon }}</view><view class="copy"><view><text class="name">{{ item.label }}</text><text class="note">{{ item.note }}</text></view><text class="description">{{ item.description }}</text></view><text class="arrow">›</text></view><view v-if="item.preview" class="preview"><view v-for="n in item.id==='smart'?4:3" :key="n" class="portrait" :class="`p-${n}`"><view class="head" /><view class="body" /></view><view v-if="item.id==='normal'" class="layout">▦<text>排版</text></view></view></view></view>
  <view class="boundary">示例图形为本地 CSS 占位，不复制原小程序人物照片。</view>
</view></template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:40rpx;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.list{display:flex;flex-direction:column;gap:20rpx;padding:28rpx 20rpx 0}.card{padding:24rpx;background:#fff;border-radius:13rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.05)}.row{display:flex;align-items:center}.icon{display:flex;align-items:center;justify-content:center;width:72rpx;height:72rpx;margin-right:20rpx;color:#f08a24;background:#fff1dc;border-radius:50%;font-size:26rpx;font-weight:700}.copy{display:flex;min-width:0;flex:1;flex-direction:column}.name{font-size:30rpx;font-weight:650}.note{margin-left:8rpx;color:#999;font-size:22rpx}.description{margin-top:8rpx;color:#555;font-size:24rpx}.arrow{color:#aaa;font-size:42rpx}.preview{display:flex;align-items:center;justify-content:center;gap:12rpx;margin-top:24rpx;padding:12rpx;background:#fafafa}.portrait{position:relative;width:86rpx;height:125rpx;overflow:hidden;background:#d9efff}.p-2{background:#ff2e2e}.p-3{background:#4b99dc}.p-4{background:#f3f3f3}.head{position:absolute;top:23rpx;left:29rpx;width:30rpx;height:38rpx;background:#ffd5bd;border-radius:50%}.body{position:absolute;right:17rpx;bottom:-8rpx;left:17rpx;height:68rpx;background:#fff;border-radius:30rpx 30rpx 0 0}.layout{display:flex;align-items:center;flex-direction:column;justify-content:center;width:130rpx;height:125rpx;color:#ff8c2e;background:#fff;font-size:55rpx}.layout text{font-size:20rpx}.boundary{margin:25rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
