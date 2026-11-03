<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { resolveSize } from '@/domain/idPhotoSizes'

const PREVIEW_SELECTION_KEY = 'miniapp-id-photo-preview-selection-v1'
const MAX_SELECTION_AGE_MS = 5 * 60 * 1000
const PAPER_WIDTH_MM = 152
const PAPER_HEIGHT_MM = 102
const layouts = {
  one: { label: '1寸证件照', repeat: 10, columns: 5, rotated: false },
  two: { label: '2寸证件照', repeat: 4, columns: 2, rotated: true },
  three: { label: '3寸证件照', repeat: 2, columns: 2, rotated: false }
} as const

type LayoutId = keyof typeof layouts
type Selection = { schemaVersion: 1; path: string; sizeId: LayoutId; createdAtMs: number }

const photo = ref('')
const layoutId = ref<LayoutId>('one')
const copies = ref(1)
const ready = ref(false)
const layout = computed(() => layouts[layoutId.value])
const cells = computed(() => Array.from({ length: layout.value.repeat }, (_, index) => index))
const physicalSize = computed(() => resolveSize(layoutId.value))
const layoutGridStyle = computed(() => {
  const size = physicalSize.value
  if (!size) return {}
  const displayWidth = layout.value.rotated ? size.height : size.width
  const displayHeight = layout.value.rotated ? size.width : size.height
  const rows = layout.value.repeat / layout.value.columns
  return {
    gridTemplateColumns: `repeat(${layout.value.columns}, 1fr)`,
    gridTemplateRows: `repeat(${rows}, 1fr)`,
    width: `${displayWidth * layout.value.columns / PAPER_WIDTH_MM * 100}%`,
    height: `${displayHeight * rows / PAPER_HEIGHT_MM * 100}%`
  }
})
const photoStyle = computed(() => {
  const size = physicalSize.value
  if (!size || !layout.value.rotated) return { width: '100%', height: '100%' }
  return {
    width: `${size.width / size.height * 100}%`,
    height: `${size.height / size.width * 100}%`
  }
})

onLoad(query => {
  const raw = uni.getStorageSync(PREVIEW_SELECTION_KEY) as Partial<Selection> | null
  uni.removeStorageSync(PREVIEW_SELECTION_KEY)
  const requested = String(query?.size || '')
  const rawSizeId = typeof raw?.sizeId === 'string' ? raw.sizeId : ''
  const validId = Object.prototype.hasOwnProperty.call(layouts, rawSizeId) ? rawSizeId as LayoutId : null
  const validPath = typeof raw?.path === 'string' && raw.path.length > 0 ? raw.path : null
  const valid = raw?.schemaVersion === 1 && validId !== null && validId === requested
    && validPath !== null
    && typeof raw?.createdAtMs === 'number' && Number.isSafeInteger(raw.createdAtMs)
    && raw.createdAtMs > 0 && Date.now() - raw.createdAtMs <= MAX_SELECTION_AGE_MS
  if (!valid || validId === null || validPath === null) {
    uni.redirectTo({ url: '/pages/id-photo-sizes/index' })
    return
  }
  layoutId.value = validId
  photo.value = validPath
  ready.value = true
})

function goBack() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.reLaunch({ url: '/pages/id-photo-sizes/index' })
}
function changeCopies(delta: number) { copies.value = Math.min(99, Math.max(1, copies.value + delta)) }
function reservedConfirm() { uni.showToast({ title: '演示计价规则待确认', icon: 'none' }) }
</script>

<template>
  <view v-if="ready" class="page">
    <view class="header"><view class="status" /><view class="title"><text @click="goBack">‹</text><text>拼版预览</text><text /></view></view>
    <view class="preview-area">
      <view class="sheet">
        <view class="layout-grid" :style="layoutGridStyle">
          <view v-for="cell in cells" :key="cell" class="cell">
            <image class="photo" :class="{ rotated: layout.rotated }" :style="photoStyle" :src="photo" mode="aspectFit" />
          </view>
        </view>
        <text class="paper-note">请用六寸相纸打印</text>
      </view>
    </view>
    <view class="settings">
      <view class="row"><text class="label">打印尺寸</text><text>{{ layout.label }}</text></view>
      <view class="row"><text class="label">打印份数</text><view class="stepper"><button @click="changeCopies(-1)">-</button><text>{{ copies }}</text><button @click="changeCopies(1)">+</button></view></view>
      <view class="tip">温馨提示：打印机将按以上排版效果打印，默认打印1份即可</view>
      <view class="boundary">当前仅复刻已观察的1寸、2寸、3寸六寸相纸排版；未接入价格、订单、支付或真实打印。</view>
    </view>
    <view class="footer"><button @click="reservedConfirm">计价规则待确认</button></view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;padding-bottom:130rpx;background:#fff}.header{color:#fff;background:#ff9f3d}.status{height:var(--status-bar-height);min-height:36rpx}.title{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.title text:first-child,.title text:last-child{width:56rpx}.title text:first-child{font-size:56rpx}.preview-area{display:flex;align-items:center;justify-content:center;min-height:520rpx;padding:36rpx 24rpx}.sheet{position:relative;display:flex;align-items:center;justify-content:center;box-sizing:border-box;width:660rpx;height:440rpx;overflow:hidden;background:#fff;box-shadow:0 6rpx 30rpx rgba(39,84,126,.15)}.layout-grid{display:grid}.cell{display:flex;align-items:center;justify-content:center;box-sizing:border-box;overflow:hidden;border:1rpx solid #999}.photo{display:block}.photo :deep(img){object-fit:contain!important}.photo.rotated{transform:rotate(90deg)}.paper-note{position:absolute;right:12rpx;bottom:8rpx;color:#555;font-size:13rpx}.settings{border-top:1rpx solid #eee}.row{display:flex;align-items:center;justify-content:space-between;min-height:96rpx;padding:0 24rpx;border-bottom:1rpx solid #eee;font-size:27rpx}.label{color:#999}.stepper{display:flex;align-items:center}.stepper button{width:58rpx;height:52rpx;margin:0;padding:0;border-radius:6rpx;background:#f4f4f4;font-size:28rpx;line-height:52rpx}.stepper button::after{border:0}.stepper text{display:flex;align-items:center;justify-content:center;width:66rpx;height:50rpx;border:1rpx solid #ffad63}.tip{padding:22rpx 24rpx;color:#999;font-size:22rpx;line-height:34rpx}.boundary{margin:0 24rpx;padding:18rpx 22rpx;color:#9a6a21;background:#fff7e7;border-radius:12rpx;font-size:21rpx;line-height:33rpx}.footer{position:fixed;right:0;bottom:0;left:0;padding:16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));background:#fff;border-top:1rpx solid #eee}.footer button{height:86rpx;color:#fff;background:#ff9f3d;border-radius:44rpx;font-size:29rpx;line-height:86rpx}.footer button::after{border:0}
</style>
