<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ensureDemoSession, getActiveDemoSession, watchDemoSession } from '@/api/demoSession'
import { continuePhotoUpload, loadPhotoUpload, newPhotoUpload, clearPhotoUpload, photoOwnerMatches } from '@/api/demoPhotoFiles'
import type { PhotoUploadRecord, PhotoUploadView } from '@/api/demoPhotoFiles'
import { photoSpecifications, takePhotoSpecification } from '@/domain/photoSelection'
import type { PhotoSpecification } from '@/domain/photoSelection'
import { createPhotoDraft, saveDraft } from '@/domain/printDraft'
import { waitForDemoFile } from '@/api/demoFiles'

const variant = ref<PhotoSpecification>('PHOTO_3IN')
const photos = ref<string[]>([])
const copies = ref(1)
const pending = ref<PhotoUploadRecord | null>(null)
const view = ref<PhotoUploadView | null>(null)
const busy = ref(false)
const issue = ref('')
const rejected = ref(false)
const maxPhotos = computed(() => variant.value === 'PHOTO_6IN_COLLAGE' ? 9 : 30)
const selectedSheets = computed(() => variant.value === 'PHOTO_6IN_COLLAGE' ? (photos.value.length ? 1 : 0) : photos.value.length)
const label = computed(() => photoSpecifications[variant.value].label)
let shown = false
let generation = 0
function restore(record: PhotoUploadRecord) {
  pending.value = record; variant.value = record.variant; photos.value = [...record.paths]; copies.value = record.copies
  issue.value = '发现上次上传，请恢复原会话；结果未知时不会另建文件。'
}
onLoad(() => {
  const selection = takePhotoSpecification()
  const record = loadPhotoUpload()
  if (record) { restore(record); return }
  if (!selection) { uni.redirectTo({ url: '/pages/photo-method/index' }); return }
  variant.value = selection
})
onShow(() => { shown = true })
onHide(() => { shown = false; generation++; busy.value = false })
onUnload(() => { shown = false; generation++ })
watchDemoSession(() => {
  if (pending.value && !photoOwnerMatches(pending.value, getActiveDemoSession())) {
    generation++; busy.value = false; pending.value = null; view.value = null; photos.value = []
    issue.value = '演示会话已变化，请重新选择照片。旧文件不会带入新主体。'
  }
})
function goBack() { if (getCurrentPages().length > 1) uni.navigateBack(); else uni.reLaunch({ url: '/pages/photo-method/index' }) }
function changeCopies(delta: number) { if (!pending.value && !busy.value) copies.value = Math.min(99, Math.max(1, copies.value + delta)) }
function removePhoto(index: number) { if (!pending.value && !busy.value) photos.value.splice(index, 1) }
function addPhoto() {
  if (pending.value || busy.value) return
  const remaining = maxPhotos.value - photos.value.length
  if (remaining <= 0) return
  const request = generation
  uni.chooseImage({ count: Math.min(9, remaining), sizeType: ['original'],
    success(result) {
      if (!shown || request !== generation || pending.value) return
      const paths = Array.isArray(result.tempFilePaths) ? result.tempFilePaths : [result.tempFilePaths]
      photos.value = [...photos.value, ...paths.filter(path => typeof path === 'string' && path.length > 0)].slice(0, maxPhotos.value)
    }, fail(error) { if (!error.errMsg.includes('cancel')) uni.showToast({ title: '无法打开照片选择器', icon: 'none' }) } })
}
function discardRejected() {
  if (!rejected.value || !pending.value || busy.value) return
  clearPhotoUpload(pending.value); pending.value = null; photos.value = []; view.value = null; rejected.value = false
  uni.redirectTo({ url: '/pages/photo-method/index' })
}
async function submit() {
  if (busy.value || !photos.value.length || !shown) return
  const request = ++generation
  busy.value = true; issue.value = ''; rejected.value = false
  const active = () => shown && request === generation
  try {
    const session = await ensureDemoSession()
    if (!active()) return
    if (!session) { issue.value = '无法建立演示会话'; return }
    let record = pending.value
    if (record && !photoOwnerMatches(record, session)) { issue.value = '原会话已变化，不能恢复旧文件'; return }
    if (!record) { record = newPhotoUpload(session, variant.value, photos.value, copies.value); pending.value = record }
    const target = pending.value
    if (!target) return
    const current = () => active() && pending.value === target && photoOwnerMatches(target, getActiveDemoSession())
    const reply = await continuePhotoUpload(session, target, current, value => { view.value = value })
    if (!current()) return
    if (reply.kind !== 'ok') {
      rejected.value = reply.kind === 'rejected'
      issue.value = reply.kind === 'rejected' ? '服务端拒绝请求或会话已过期；保留原记录，请核实后处理。' : '上传结果暂不可确认，请恢复原会话。'
      return
    }
    if (reply.view.state !== 'COMPLETE' || !reply.view.result) { issue.value = '服务端正在生成，请恢复原会话查询结果。'; return }
    const file = await waitForDemoFile(session, reply.view.result, current)
    if (!current()) return
    const draft = file && createPhotoDraft(file, session, target.copies)
    if (!draft) { issue.value = '文件尚未就绪或已失效，请恢复查询；不会重新生成文件。'; return }
    saveDraft(draft); clearPhotoUpload(target)
    uni.navigateTo({ url: '/pages/order-confirm/index' })
  } finally { if (active()) busy.value = false }
}
</script>

<template>
  <view class="page"><view class="header"><view class="status-space" /><view class="title-row"><text class="back" @click="goBack">‹</text><text>{{ label }}上传</text><text class="spacer" /></view></view>
    <view v-if="photos.length === 0" class="empty"><view class="empty-icon">⌒</view><text>暂无照片哦~</text></view>
    <view v-else class="photo-grid"><view v-for="(photo, index) in photos" :key="`${photo}-${index}`"><image class="photo" :src="photo" mode="aspectFit" /><text v-if="!pending" @click="removePhoto(index)">移除</text></view></view>
    <view class="details"><text>{{ photoSpecifications[variant].box }}</text><text>六寸相纸 · 彩色 · 单面 · 完整显示不裁剪，可留白</text><view class="copies"><text>份数</text><button :disabled="!!pending" @click="changeCopies(-1)">−</button><text>{{ copies }}</text><button :disabled="!!pending" @click="changeCopies(1)">＋</button></view><text>预计 {{ selectedSheets }} 张/份 × {{ copies }} 份 = {{ selectedSheets * copies }} 张相纸</text><text>最终张数和金额以服务端报价为准。</text><text v-if="view">已上传 {{ view.uploadedIndexes.length }}/{{ view.fileCount }} · {{ view.state === 'COMPLETE' ? '生成完成' : view.state === 'FINALIZING' ? '正在生成' : '上传中' }}</text></view>
    <view class="boundary">JPEG/PNG，单图最大2MiB；普通照片总计最多30MiB，拼版最多12MiB。支付与打印为模拟链路，不扣真实资金、不代表真实出纸。</view>
    <view v-if="issue" class="issue">{{ issue }}<button v-if="rejected" @click="discardRejected">重新选择照片</button></view>
    <view class="footer"><button class="upload" :disabled="!!pending || busy" @click="addPhoto">添加照片({{ photos.length }}/{{ maxPhotos }})</button><button class="submit" :class="{ ready: photos.length > 0 }" :disabled="busy || !photos.length" @click="submit">{{ busy ? '处理中…' : pending ? '恢复原上传' : '生成并确认' }}</button></view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;background:#fff}.header{color:#fff;background:#ff9f3d}.status-space{height:var(--status-bar-height);min-height:36rpx}.title-row{display:flex;align-items:center;justify-content:space-between;height:86rpx;padding:0 26rpx;font-size:34rpx;font-weight:600}.back,.spacer{width:56rpx}.back{font-size:56rpx;line-height:56rpx}.empty{display:flex;align-items:center;flex-direction:column;justify-content:center;min-height:720rpx;color:#333;font-size:26rpx}.empty-icon{display:flex;align-items:center;justify-content:center;width:150rpx;height:110rpx;margin-bottom:24rpx;color:#cfd7df;background:#f3f6f8;border-radius:44rpx;font-size:70rpx}.photo-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx;padding:28rpx 24rpx}.photo{width:100%;height:220rpx;background:#f3f3f3;border-radius:10rpx}.boundary{margin:24rpx 38rpx 20rpx;color:#aaa;font-size:22rpx;line-height:35rpx;text-align:center}.footer{position:fixed;right:0;bottom:0;left:0;display:grid;grid-template-columns:1fr 1fr;gap:20rpx;padding:14rpx 24rpx calc(14rpx + env(safe-area-inset-bottom));background:#fff;border-top:1rpx solid #eee}.upload,.submit{height:86rpx;border-radius:10rpx;text-align:center;font-size:29rpx;line-height:86rpx}.upload{color:#ff9a3d;border:2rpx solid #ff9a3d}.submit{color:#fff;background:#d8d8d8}.submit.ready{background:#ff9a3d}
</style>

<style scoped>.details{display:flex;flex-direction:column;gap:12rpx;padding:24rpx;font-size:25rpx;color:#666}.copies{display:flex;align-items:center;gap:24rpx}.copies button{margin:0;width:68rpx;padding:0}.issue{padding:20rpx 28rpx 160rpx;color:#a66b26;font-size:24rpx}.photo-grid text{font-size:22rpx;color:#b66e31}.footer button{padding:0;font-size:25rpx}</style>
