<script setup lang="ts">
defineProps<{ text: string }>()
</script>

<template>
  <view class="empty-state">
    <view class="illustration" aria-hidden="true">
      <view class="soft soft-one" />
      <view class="soft soft-two" />
      <view class="plane" />
      <view class="parcel"><view class="parcel-lid" /><view class="parcel-handle" /></view>
    </view>
    <text class="empty-text">{{ text }}</text>
  </view>
</template>

<style scoped lang="scss">
.empty-state{display:flex;align-items:center;flex-direction:column;padding-top:118rpx;color:#555;font-size:27rpx}.illustration{position:relative;width:230rpx;height:210rpx;margin-bottom:28rpx}.soft{position:absolute;background:#edf2f5;border-radius:50%}.soft-one{right:5rpx;bottom:12rpx;width:190rpx;height:130rpx;transform:rotate(-12deg)}.soft-two{left:10rpx;bottom:18rpx;width:135rpx;height:100rpx;transform:rotate(18deg)}.plane{position:absolute;top:2rpx;right:18rpx;width:0;height:0;border-top:23rpx solid transparent;border-bottom:12rpx solid transparent;border-left:56rpx solid #c4d0d8;transform:rotate(-35deg)}.parcel{position:absolute;right:55rpx;bottom:29rpx;box-sizing:border-box;width:105rpx;height:76rpx;border:4rpx solid #b9c7d0;border-radius:6rpx}.parcel-lid{position:absolute;top:-4rpx;right:-4rpx;left:-4rpx;height:24rpx;border:4rpx solid #b9c7d0;border-radius:6rpx}.parcel-handle{position:absolute;top:12rpx;left:36rpx;width:28rpx;height:13rpx;border:4rpx solid #b9c7d0;border-top:0;border-radius:0 0 12rpx 12rpx}.empty-text{position:relative;color:#555}.empty-text::before{position:absolute;top:50%;left:-34rpx;width:20rpx;height:2rpx;background:#c9d3da;content:''}
</style>
