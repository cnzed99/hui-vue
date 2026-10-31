<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { loadMiniappCatalog, localCatalog } from '@/api/catalog'
import type { CatalogSource, MiniappCatalog, MiniappPrintMethod } from '@/api/catalog'

const catalog = ref<MiniappCatalog>(localCatalog)
const source = ref<CatalogSource>('local')
const loading = ref(true)
const commonMethods = computed(() => catalog.value.methods.filter(item => item.group === 'COMMON'))
const otherMethods = computed(() => catalog.value.methods.filter(item => item.group === 'OTHER'))

onMounted(async () => {
  const result = await loadMiniappCatalog()
  catalog.value = result.catalog
  source.value = result.source
  loading.value = false
})

function goBack() {
  uni.navigateBack()
}

function openMethod(method: MiniappPrintMethod) {
  if (method.id === 'local-document') {
    uni.navigateTo({ url: '/pages/document-upload/index' })
    return
  }
  uni.showToast({ title: `${method.label}暂未接入`, icon: 'none' })
}
</script>

<template>
  <view class="method-page">
    <view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>文档打印</text><text class="spacer" /></view></view>
    <view class="catalog-state" :class="source"><text>{{ loading ? '正在读取打印目录…' : source === 'server' ? '服务端目录已连接' : '本地目录 · 服务暂不可用' }}</text><text>上传功能未开放</text></view>
    <view class="section"><view class="section-title"><text class="mark" />常用打印</view><view class="method-list featured"><view v-for="item in commonMethods" :key="item.id" class="method-row" @click="openMethod(item)"><view class="method-icon orange">▤</view><view class="copy"><text class="name">{{ item.label }}</text><text class="description">{{ item.description }}</text></view><text class="arrow">›</text></view></view></view>
    <view class="section"><view class="section-title"><text class="mark" />其它打印</view><view class="method-list"><view v-for="(item,index) in otherMethods" :key="item.id" class="method-row"><view class="method-icon" :class="`tone-${index % 4}`">{{ ['▥','▦','腾','金','百','W','A3','锁','纸'][index] || '▤' }}</view><view class="copy" @click="openMethod(item)"><text class="name">{{ item.label }}</text><text class="description">{{ item.description }}</text></view><text class="arrow" @click="openMethod(item)">›</text></view></view></view>
    <view class="limit-note">演示约束：单文件不超过 {{ Math.round(catalog.demoLimits.maxFileBytes / 1024 / 1024) }}MB、{{ catalog.demoLimits.maxPages }} 页，暂存 {{ catalog.demoLimits.retentionMinutes }} 分钟；当前不上传。</view>
  </view>
</template>

<style scoped lang="scss">
.method-page{min-height:100vh;background:#f6f6f6;padding-bottom:44rpx}.header{color:#fff;background:linear-gradient(135deg,#ff9634,#ffad48)}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.catalog-state{display:flex;justify-content:space-between;gap:16rpx;padding:18rpx 28rpx;color:#9b6d2b;background:#fff7e8;font-size:22rpx}.catalog-state.server{color:#26735b;background:#eafaf4}.section{padding:30rpx 20rpx 0}.section-title{display:flex;align-items:center;margin:0 6rpx 18rpx;font-size:31rpx;font-weight:700}.mark{width:7rpx;height:31rpx;margin-right:12rpx;background:#20c2e5;border-radius:6rpx}.method-list{overflow:hidden;background:#fff;border-radius:14rpx;box-shadow:0 5rpx 18rpx rgba(0,0,0,.05)}.featured{border:2rpx solid #fff0df}.method-row{display:flex;align-items:center;min-height:128rpx;margin-left:22rpx;padding:20rpx 22rpx 20rpx 0;border-bottom:1rpx solid #eee}.method-row:last-child{border-bottom:0}.method-icon{display:flex;flex:0 0 auto;align-items:center;justify-content:center;width:72rpx;height:72rpx;margin-right:20rpx;color:#20a9db;background:#ebf8ff;border-radius:50%;font-size:26rpx;font-weight:700}.method-icon.orange,.tone-1{color:#ff8b2f;background:#fff1e4}.tone-2{color:#735bd1;background:#f0edff}.tone-3{color:#31b786;background:#e9faf4}.copy{display:flex;min-width:0;flex:1;flex-direction:column}.name{font-size:29rpx;font-weight:600}.description{margin-top:9rpx;color:#999;font-size:23rpx;line-height:34rpx}.arrow{padding-left:12rpx;color:#bbb;font-size:42rpx}.limit-note{margin:28rpx 30rpx;color:#999;font-size:22rpx;line-height:36rpx}
</style>
