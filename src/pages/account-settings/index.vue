<script setup lang="ts">
function goBack() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.reLaunch({ url: '/pages/profile/index' })
}

function reserved(action: string) {
  uni.showToast({ title: `${action}尚未接入`, icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>账户设置</text><text class="spacer" /></view></view>
    <view class="form-card">
      <view class="row avatar-row" @click="reserved('头像修改')"><text>头像</text><view class="avatar">●</view><text class="arrow">›</text></view>
      <view class="row"><text>昵称</text><input disabled placeholder="请输入昵称" /></view>
      <view class="row"><text>姓名</text><input disabled placeholder="请输入姓名" /></view>
      <view class="row"><text>手机号</text><input disabled placeholder="请输入您的手机号" /></view>
      <view class="row"><text>性别</text><view class="gender"><view class="radio" /><text>男</text><view class="radio" /><text>女</text></view></view>
    </view>
    <view class="boundary">原页面可修改头像、昵称、姓名、手机号和性别。当前微信登录、头像存储、手机号授权、资料权限与持久化接口尚未接入，本页不会读取、采集或保存个人资料。</view>
    <button class="save" @click="reserved('账户资料保存')">保存接口待接入</button>
  </view>
</template>

<style scoped lang="scss">
.page{display:flex;min-height:100vh;box-sizing:border-box;flex-direction:column;padding-bottom:env(safe-area-inset-bottom);background:#f5f5f5}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.form-card{margin:18rpx 14rpx 0;background:#fff;border-radius:18rpx}.row{display:flex;align-items:center;min-height:92rpx;padding:0 24rpx;border-bottom:1rpx solid #eee;font-size:27rpx}.row:last-child{border-bottom:0}.row>text:first-child{width:145rpx}.row input{height:80rpx;flex:1;text-align:right;font-size:25rpx}.avatar-row{min-height:116rpx}.avatar{display:flex;align-items:flex-end;justify-content:center;width:72rpx;height:72rpx;margin-left:auto;color:#3d3d3d;background:linear-gradient(#f9edcb 48%,#91bfbe 49%);border-radius:50%;font-size:45rpx;overflow:hidden}.arrow{margin-left:16rpx;color:#aaa;font-size:38rpx}.gender{display:flex;align-items:center;justify-content:flex-end;flex:1;gap:12rpx}.radio{width:30rpx;height:30rpx;margin-left:22rpx;border:2rpx solid #ccc;border-radius:50%}.boundary{margin:30rpx 28rpx;padding:22rpx;color:#8b6b3d;background:#fff8e9;border-radius:14rpx;font-size:22rpx;line-height:36rpx}.save{height:88rpx;margin-top:auto;color:#fff;background:#c7c7c7;border-radius:0;font-size:31rpx;line-height:88rpx}.save::after{border:0}
</style>
