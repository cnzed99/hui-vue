<script setup lang="ts">
import { onBeforeUnmount } from 'vue'

const props = defineProps<{ mode: 'smart' | 'marriage' | 'vehicle' }>()
const titles = { smart: '智能证件照', marriage: '上传结婚照', vehicle: '行驶证制作' }
const sizes = [
  ['教师资格证', '25×35'], ['一寸', '25×35'], ['二寸', '35×49'],
  ['计算机等级', '33×48'], ['大一寸', '33×48'], ['社保证', '30×40'],
  ['小二寸', '35×45'], ['小一寸', '22×32'], ['简历照片', '25×35']
].map(([label, size]) => ({ label, size }))
const backgrounds = [
  { label: '原图', color: '#fff' }, { label: '红色一', color: '#f34e59' },
  { label: '红色二', color: '#db293b' }, { label: '红色三', color: '#b92233' }
] as const
const vehicleTips = [
  '从车辆前方 45 度拍摄。',
  '车头、车尾和底盘按示例对齐，机动车影像约占标准图片的三分之二。',
  '照片应能清晰辨别车牌和车身颜色。'
]

function goBack() {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/id-photo/index' })
}
function explain(action: string) { uni.showToast({ title: action + '尚未接入', icon: 'none' }) }
onBeforeUnmount(() => uni.hideToast())
</script>

<template>
  <view class="page">
    <view class="header">
      <view class="status" />
      <view class="title">
        <button class="back" aria-label="返回证件照目录" @click="goBack">‹</button>
        <text>{{ titles[props.mode] }}</text>
        <view class="spacer" />
      </view>
    </view>
    <view v-if="props.mode === 'smart'" class="content">
      <button class="search" @click="explain('规格搜索')">⌕ 搜索证件照规格</button>
      <view class="tabs">
        <button class="tab active" @click="explain('热门规格')">热门规格</button>
        <button class="tab" @click="explain('自定义规格')">自定义规格</button>
        <button class="tab" @click="explain('操作指南')">操作指南</button>
      </view>
      <view class="sizes">
        <button v-for="item in sizes" :key="item.label" class="size" @click="explain('智能证件照制作')">
          <text>{{ item.label }}</text><text class="dimension">{{ item.size }}mm</text>
        </button>
      </view>
      <text class="boundary">当前可查看常用规格。搜索、自定义规格和证件照制作暂未接入。</text>
    </view>
    <template v-else>
      <view v-if="props.mode === 'marriage'" class="content">
        <text class="hint">建议照片铺满边框，无空白，并尽量将人像对准虚线。</text>
        <button class="frame marriage" aria-label="添加结婚照" @click="explain('添加结婚照')">
          <view v-for="position in [1, 2]" :key="position" class="person" aria-hidden="true">
            <view class="head" /><view class="shoulders" />
          </view>
          <text class="plus">＋</text>
        </button>
        <text class="label">背景色</text>
        <view class="backgrounds">
          <button v-for="background in backgrounds" :key="background.label" class="background"
            @click="explain('背景处理')">
            <view class="swatch" :style="{ backgroundColor: background.color }" />
            <text>{{ background.label }}</text>
          </button>
        </view>
        <text class="hint">纯色背景处理效果最佳。</text>
      </view>
      <view v-else class="content">
        <button class="frame vehicle" aria-label="添加车辆照片" @click="explain('添加车辆照片')">
          <text class="measure">88×60mm</text>
          <view class="vehicle-guide">
            <text class="front-line">车头线</text>
            <text class="rear-line">车尾线</text>
            <text class="chassis-line">底盘线</text>
          </view>
          <text class="plus">＋</text>
        </button>
        <view class="vehicle-tips">
          <text v-for="(tip, index) in vehicleTips" :key="tip">{{ index + 1 }}. {{ tip }}</text>
        </view>
      </view>
      <text class="boundary centered">照片添加与处理暂未接入。</text>
      <view class="footer"><button class="next" @click="explain('证件照制作')">下一步</button></view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page { display: flex; min-height: 100vh; flex-direction: column; background: #fff; color: #26231f; }
.header { color: #fff; background: #ff9f3d; }
.status { height: var(--status-bar-height); min-height: 36rpx; }
.title { display: flex; align-items: center; justify-content: space-between; min-height: 86rpx; padding: 0 26rpx; font-size: 34rpx; font-weight: 600; }
.back, .spacer { box-sizing: border-box; width: 60rpx; height: 60rpx; }
.back { margin: 0; padding: 0; background: transparent; color: #fff; font-size: 54rpx; line-height: 55rpx; }
button::after { border: 0; }
.content { padding: 32rpx 28rpx; }
.search { padding: 0 28rpx; border-radius: 44rpx; background: #f3f3f3; color: #696969; text-align: left; font-size: 26rpx; }
.tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12rpx; margin: 26rpx 0; }
.tab { width: 100%; padding: 0 8rpx; background: #f8f8f8; color: #333; font-size: 24rpx; }
.tab.active { background: #fff2df; color: #9b4c0b; }
.sizes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14rpx; }
.size { display: flex; align-items: center; flex-direction: column; justify-content: center; width: 100%; min-height: 106rpx; padding: 12rpx 4rpx; border-radius: 12rpx; background: #fff8ef; color: #34312c; font-size: 24rpx; line-height: 34rpx; }
.dimension { color: #735f4c; font-size: 20rpx; }
.hint, .boundary { display: block; color: #6b6259; font-size: 23rpx; line-height: 36rpx; }
.boundary { margin-top: 22rpx; }
.centered { margin: 0 28rpx 20rpx; text-align: center; }
.frame { position: relative; box-sizing: border-box; overflow: hidden; margin: 24rpx auto; border: 2rpx solid #efc491; border-radius: 16rpx; background: #fff9f0; }
.marriage { display: flex; align-items: center; justify-content: center; gap: 26rpx; width: 100%; max-width: 550rpx; height: 350rpx; }
.person { position: relative; width: 34%; height: 78%; }
.head, .shoulders { position: absolute; box-sizing: border-box; border: 3rpx dashed #dc9251; }
.head { top: 8%; left: 22%; width: 56%; height: 46%; border-radius: 48%; }
.shoulders { top: 58%; left: 2%; width: 96%; height: 29%; border-bottom: 0; border-radius: 50% 50% 0 0; }
.plus { position: absolute; top: 48%; left: 50%; width: 66rpx; height: 66rpx; transform: translate(-50%, -50%); border-radius: 50%; background: #fff; color: #a34f0e; font-size: 46rpx; line-height: 62rpx; }
.label { font-size: 27rpx; font-weight: 650; }
.backgrounds { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12rpx; margin: 14rpx 0; }
.background { display: flex; align-items: center; flex-direction: column; width: 100%; padding: 12rpx 0; background: #fafafa; color: #333; font-size: 22rpx; line-height: 35rpx; }
.swatch { width: 40rpx; height: 40rpx; border: 1rpx solid #ddd; border-radius: 50%; }
.vehicle { width: 100%; max-width: 528rpx; height: 360rpx; }
.measure { position: absolute; top: 14rpx; left: 20rpx; color: #694625; font-size: 24rpx; line-height: 34rpx; }
.vehicle-guide { position: absolute; top: 22%; right: 11%; bottom: 18%; left: 11%; border-bottom: 3rpx dashed #bf8555; }
.vehicle-guide text { position: absolute; padding: 0 8rpx; color: #694625; font-size: 21rpx; line-height: 32rpx; }
.front-line, .rear-line { top: 3%; height: 95%; }
.front-line { left: 0; border-left: 3rpx dashed #bf8555; }
.rear-line { right: 0; border-right: 3rpx dashed #bf8555; }
.chassis-line { right: 0; bottom: -35rpx; }
.vehicle-tips { display: flex; flex-direction: column; gap: 10rpx; color: #504841; font-size: 24rpx; line-height: 38rpx; }
.footer { margin-top: auto; padding: 18rpx 28rpx calc(18rpx + env(safe-area-inset-bottom)); }
.next { background: #ff9f3d; color: #302318; border-radius: 44rpx; font-size: 30rpx; }
</style>
