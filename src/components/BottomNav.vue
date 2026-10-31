<script setup lang="ts">
import type { NavKey } from '@/data/fixtures'

const props = defineProps<{ active: NavKey }>()

const items: Array<{ key: NavKey; label: string; icon: string; url: string }> = [
  { key: 'home', label: '首页', icon: '⌂', url: '/pages/home/index' },
  { key: 'library', label: '文库', icon: '▧', url: '/pages/library/index' },
  { key: 'profile', label: '我的', icon: '◯', url: '/pages/profile/index' }
]

function navigate(key: NavKey, url: string) {
  if (key === props.active) return
  uni.reLaunch({ url })
}
</script>

<template>
  <view class="bottom-nav" role="navigation" aria-label="主导航">
    <view
      v-for="item in items"
      :key="item.key"
      class="nav-item"
      :class="{ active: item.key === active }"
      @click="navigate(item.key, item.url)"
    >
      <text class="nav-icon">{{ item.icon }}</text>
      <text class="nav-label">{{ item.label }}</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.bottom-nav {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 50;
  display: flex;
  height: calc(110rpx + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: rgba(255, 255, 255, 0.98);
  border-top: 1rpx solid #eeeeee;
  box-shadow: 0 -8rpx 24rpx rgba(0, 0, 0, 0.03);
}

.nav-item {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  color: #b5b5b5;
}

.nav-icon {
  font-size: 46rpx;
  line-height: 50rpx;
}

.nav-label {
  margin-top: 3rpx;
  font-size: 22rpx;
}

.nav-item.active {
  color: #ff9b35;
}
</style>
