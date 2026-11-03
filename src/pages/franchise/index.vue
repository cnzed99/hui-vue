<script setup lang="ts">
function goBack() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.reLaunch({ url: '/pages/profile/index' })
}

function explainReserved(label: string) {
  uni.showToast({ title: `${label}尚未接入`, icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>申请开通</text><text class="spacer" /></view></view>
    <view class="steps"><view class="step active"><text class="number">1</text><text>填写基本信息</text></view><view class="line" /><view class="step"><text class="number">2</text><text>关注公众号获取账号</text></view></view>
    <view class="form-card">
      <view class="field"><text>联系人</text><input disabled placeholder="请输入姓名" /></view>
      <view class="field"><text>联系电话</text><input disabled placeholder="请输入手机号码" /></view>
      <view class="field"><text>门店名称</text><input disabled placeholder="请输入店铺名称" /></view>
      <view class="field" @click="explainReserved('门店类型')"><text>门店类型</text><text class="placeholder">请选择</text><text class="arrow">›</text></view>
      <view class="field" @click="explainReserved('门店定位')"><text>门店位置</text><text class="placeholder">请点击定位获取</text><text class="location">定位</text></view>
    </view>
    <view class="photo-card"><view class="photo-title"><text>门店照片</text><text>门头、门店内照片，最多3张</text></view><view class="photo-placeholder" @click="explainReserved('门店照片上传')">＋</view></view>
    <button class="submit" @click="explainReserved('加盟申请提交')">申请接口待接入</button>
    <view class="boundary">原流程会收集联系人、手机号、门店位置和门店照片，并要求使用手机微信操作。当前运营主体、隐私告知、数据保留和审核流程尚未确认，因此本页不会采集、定位、上传或提交任何资料。</view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:46rpx;background:#f4f4f4}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.steps{display:flex;align-items:flex-start;margin:24rpx 20rpx;padding:30rpx 26rpx;background:#fff;border-radius:18rpx}.step{display:flex;align-items:center;flex:1;flex-direction:column;gap:12rpx;color:#999;font-size:24rpx}.step.active{color:#ef6f32}.number{display:flex;align-items:center;justify-content:center;width:38rpx;height:38rpx;color:#fff;background:#aaa;border-radius:50%;font-size:21rpx}.active .number{background:#ef6f32}.line{height:2rpx;flex:1;margin:19rpx -28rpx 0;background:#ddd}.form-card,.photo-card{margin:0 20rpx 20rpx;padding:0 24rpx;background:#fff;border-radius:18rpx}.field{display:flex;align-items:center;min-height:94rpx;border-bottom:1rpx solid #eee;font-size:26rpx}.field:last-child{border-bottom:0}.field>text:first-child{width:150rpx}.field input{height:80rpx;flex:1;font-size:25rpx}.placeholder{flex:1;color:#aaa}.arrow{color:#aaa;font-size:40rpx}.location{padding:10rpx 18rpx;color:#ef6f32;border:1rpx solid #ef6f32;border-radius:25rpx;font-size:21rpx}.photo-card{padding-top:26rpx;padding-bottom:28rpx}.photo-title{display:flex;align-items:center;justify-content:space-between;font-size:26rpx}.photo-title text:last-child{color:#999;font-size:21rpx}.photo-placeholder{display:flex;align-items:center;justify-content:center;width:120rpx;height:120rpx;margin-top:24rpx;color:#ef6f32;border:2rpx dashed #ef6f32;border-radius:10rpx;font-size:58rpx;font-weight:200}.submit{height:86rpx;margin:30rpx 28rpx 0;color:#fff;background:#c7c7c7;border-radius:44rpx;font-size:29rpx;line-height:86rpx}.submit::after{border:0}.boundary{margin:28rpx 36rpx 0;padding:22rpx;color:#8b6b3d;background:#fff8e9;border-radius:14rpx;font-size:22rpx;line-height:36rpx}
</style>
