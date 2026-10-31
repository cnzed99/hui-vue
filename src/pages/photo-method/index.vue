<script setup lang="ts">
const methods = [
  { id: 'photo-6', label: '6寸照片打印', description: '在6寸纸上打印6寸照片', icon: '6', tone: 'blue' },
  { id: 'photo-5', label: '5寸照片打印', description: '在6寸纸上打印5寸照片', icon: '5', tone: 'peach' },
  { id: 'photo-4', label: '4寸照片打印', description: '在6寸纸上打印4寸照片', icon: '4', tone: 'orange' },
  { id: 'photo-3', label: '3寸照片打印', description: '在6寸纸上打印3寸照片', icon: '3', tone: 'pink' },
  { id: 'photo-collage', label: '6寸照片拼版打印', description: '最多9张照片拼在一张6寸照片纸上打印', icon: '拼', tone: 'yellow' }
]

function goBack() { if (getCurrentPages().length > 1) { uni.navigateBack(); return } uni.reLaunch({ url: '/pages/home/index' }) }
function openMethod(id: string, label: string) {
  if (id === 'photo-3') {
    uni.navigateTo({ url: '/pages/photo-upload/index' })
    return
  }
  uni.showToast({ title: `${label}待继续观察`, icon: 'none' })
}
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>照片冲印</text><text class="spacer" /></view></view>
    <view class="list"><view v-for="item in methods" :key="item.id" class="card" @click="openMethod(item.id, item.label)"><view class="icon" :class="`tone-${item.tone}`">{{ item.icon }}</view><view class="copy"><text class="name">{{ item.label }}</text><text class="description">{{ item.description }}</text></view><text class="arrow">›</text></view></view>
    <view class="note">仅“3寸照片上传”空态已继续观察；其它规格暂不推测后续参数。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:44rpx;background:#f6f6f6}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.list{display:flex;flex-direction:column;gap:18rpx;padding:28rpx 28rpx 0}.card{display:flex;align-items:center;min-height:132rpx;padding:20rpx 24rpx;background:#fff;border-radius:13rpx;box-shadow:0 4rpx 16rpx rgba(0,0,0,.06)}.icon{display:flex;align-items:center;justify-content:center;width:70rpx;height:82rpx;margin-right:24rpx;color:#fff;border-radius:10rpx;font-size:48rpx;font-weight:700}.tone-blue{background:linear-gradient(#77c4ff,#4c93e6)}.tone-peach{background:linear-gradient(#ffb793,#ff7e55)}.tone-orange{background:linear-gradient(#ffba43,#ff8200)}.tone-pink{background:linear-gradient(#f6a6a2,#df6e70)}.tone-yellow{color:#fff;background:#ffd324}.copy{display:flex;min-width:0;flex:1;flex-direction:column}.name{font-size:30rpx;font-weight:650}.description{margin-top:12rpx;color:#999;font-size:23rpx;line-height:32rpx}.arrow{color:#bbb;font-size:42rpx}.note{margin:28rpx 34rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
