<script setup lang="ts">
import { computed, ref } from 'vue'
import BottomNav from '@/components/BottomNav.vue'
import { libraryDocuments } from '@/data/fixtures'

const keyword = ref('')
const submittedKeyword = ref('')

const visibleDocuments = computed(() => {
  const value = submittedKeyword.value.trim().toLowerCase()
  if (!value) return libraryDocuments
  return libraryDocuments.filter((item) => item.title.toLowerCase().includes(value))
})

function search() {
  submittedKeyword.value = keyword.value
}

function showFilter(label: string) {
  uni.showToast({ title: `${label}筛选待接入`, icon: 'none' })
}
</script>

<template>
  <view class="page-shell">
    <view class="topbar"><view class="status-space" /><view class="bar-title">文库</view></view>
    <view class="search-row">
      <input v-model="keyword" class="search-input" placeholder="输入文件名" confirm-type="search" @confirm="search" />
      <button class="search-button" @click="search">搜索</button>
    </view>
    <view class="filters">
      <view v-for="filter in ['分类', '本店', '不限', '排序']" :key="filter" class="filter" @click="showFilter(filter)">{{ filter }} <text>⌄</text></view>
    </view>

    <scroll-view class="library-list" scroll-y>
      <view v-for="document in visibleDocuments" :key="document.id" class="document-card">
        <view class="preview"><view class="preview-line wide" /><view class="preview-line" /><view class="preview-line" /><view class="preview-line short" /><text>PDF</text></view>
        <view class="document-main">
          <text class="document-title">{{ document.title }}</text>
          <text class="document-badge">{{ document.category }} @ {{ document.source }}</text>
          <view class="document-stats"><text>◉ {{ document.views }}</text><text>▣ {{ document.prints }}</text><text>共 {{ document.pages }} 页</text></view>
        </view>
      </view>
      <view v-if="visibleDocuments.length === 0" class="empty-state">没有匹配的文档</view>
      <view class="list-spacer" />
    </scroll-view>

    <BottomNav active="library" />
  </view>
</template>

<style scoped lang="scss">
.page-shell { min-height: 100vh; padding-bottom: 120rpx; background: #f6f6f6; }
.topbar { color: #fff; background: #ff9f3d; }.status-space { height: var(--status-bar-height); min-height: 36rpx; }.bar-title { height: 74rpx; line-height: 74rpx; text-align: center; font-size: 30rpx; }
.search-row { display: flex; align-items: center; gap: 14rpx; padding: 20rpx 26rpx; background: #fff; }.search-input { flex: 1; height: 62rpx; padding: 0 22rpx; color: #555; background: #f1f1f1; border-radius: 34rpx; font-size: 25rpx; }.search-button { width: 112rpx; height: 62rpx; margin: 0; padding: 0; color: #222; background: #fff; border-radius: 32rpx; box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06); font-size: 27rpx; line-height: 62rpx; }
.filters { display: grid; grid-template-columns: repeat(4, 1fr); height: 84rpx; background: #fff; border-top: 1rpx solid #f3f3f3; border-bottom: 1rpx solid #eee; }.filter { display: flex; align-items: center; justify-content: center; gap: 8rpx; color: #3e3e3e; border-right: 1rpx solid #eeeeee; font-size: 25rpx; }.filter:last-child { border-right: 0; }.filter text { color: #999; font-size: 20rpx; }
.library-list { height: calc(100vh - var(--status-bar-height) - 240rpx); padding: 18rpx 18rpx 0; box-sizing: border-box; }.document-card { display: flex; min-height: 166rpx; margin-bottom: 14rpx; padding: 14rpx; background: #fff; border-radius: 12rpx; box-shadow: 0 2rpx 10rpx rgba(0, 0, 0, 0.03); }
.preview { position: relative; display: flex; flex: 0 0 104rpx; align-items: center; flex-direction: column; gap: 8rpx; min-height: 138rpx; padding: 24rpx 12rpx 8rpx; background: linear-gradient(#fff, #f5f5f5); border: 1rpx solid #e6e6e6; border-radius: 4rpx; box-shadow: 0 2rpx 7rpx rgba(0, 0, 0, 0.08); }.preview-line { width: 68rpx; height: 3rpx; background: #cfcfcf; }.preview-line.wide { width: 78rpx; background: #999; }.preview-line.short { width: 42rpx; }.preview text { position: absolute; right: 8rpx; bottom: 7rpx; color: #df6b57; font-size: 14rpx; }
.document-main { display: flex; min-width: 0; flex: 1; padding: 8rpx 0 2rpx 20rpx; flex-direction: column; }.document-title { overflow: hidden; color: #454545; font-size: 26rpx; line-height: 36rpx; text-overflow: ellipsis; white-space: nowrap; }.document-badge { align-self: flex-start; margin-top: 13rpx; padding: 4rpx 10rpx; color: #e8a03d; border: 1rpx solid #f0bd70; border-radius: 18rpx; font-size: 20rpx; }.document-stats { display: flex; gap: 24rpx; margin-top: auto; color: #9a9a9a; font-size: 20rpx; }.empty-state { padding: 100rpx 0; color: #999; text-align: center; font-size: 26rpx; }.list-spacer { height: 110rpx; }
</style>
