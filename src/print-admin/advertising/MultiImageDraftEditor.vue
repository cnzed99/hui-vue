<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import type { AdDraft } from './api'
import { findPosition, slotsFor, slotLabel } from './positions'
import type { AdPosition } from './positions'

const props = defineProps<{
  position: AdPosition
  urls: Record<string, string>
  successText: string
  draft: AdDraft | null
  busy: boolean
  locked: boolean
  message: string
  canQuery: boolean
  canSave: boolean
  contextReady: boolean
}>()
const emit = defineEmits<{
  'update-url': [slot: string, value: string]
  'update:successText': [value: string]
  'preview-change': [pending: boolean]
  reload: []
  save: []
}>()
const info = computed(() => findPosition(props.position)!)
const slots = computed(() => slotsFor(props.position))
const orderPage = computed(() => props.position === 'ORDER_PAGE')
const editable = computed(() => props.canQuery && props.contextReady && props.canSave && !props.busy && !props.locked && !!props.draft)
interface LocalPreview { url: string; name: string; dimensions: string; loading: boolean }
const previews = reactive<Record<string, LocalPreview>>({})
const fileMessages = reactive<Record<string, string>>({})
const failedUrls = reactive<Record<string, string>>({})
const previewGenerations: Record<string, number> = {}
const hasLocalPreview = computed(() => Object.keys(previews).length > 0)
watch(hasLocalPreview, (value) => emit('preview-change', value), { flush: 'sync' })
const clearLocal = (slot: string) => {
  previewGenerations[slot] = (previewGenerations[slot] || 0) + 1
  if (previews[slot]) URL.revokeObjectURL(previews[slot].url)
  delete previews[slot]; delete fileMessages[slot]
}
const clearAll = () => {
  Object.keys(previews).forEach(clearLocal)
  Object.keys(fileMessages).forEach((slot) => delete fileMessages[slot])
  Object.keys(failedUrls).forEach((slot) => delete failedUrls[slot])
}
watch(() => [props.draft, props.position], clearAll, { flush: 'sync' })
onBeforeUnmount(clearAll)
const persistedUrl = (slot: string) => {
  try {
    const url = new URL(props.urls[slot]?.trim() || '')
    return url.protocol === 'https:' && !url.username && !url.password && !url.hash ? url.href : ''
  } catch { return '' }
}
const previewUrl = (slot: string) => previews[slot]?.url || persistedUrl(slot)
const selectFile = (slot: string, event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !editable.value || !slots.value.includes(slot)) return
  if (!file.type.startsWith('image/')) { fileMessages[slot] = '请选择图片文件（image/*）。'; return }
  if (orderPage.value && file.size > 1024 * 1024) { fileMessages[slot] = '订单页广告图片不得超过1MB。'; return }
  clearLocal(slot)
  const ticket = previewGenerations[slot]
  const url = URL.createObjectURL(file)
  delete failedUrls[slot]
  previews[slot] = { url, name: file.name, dimensions: '', loading: true }
  const image = new Image()
  image.onload = () => {
    if (ticket !== previewGenerations[slot]) return
    previews[slot].dimensions = `${image.naturalWidth} × ${image.naturalHeight} 像素`
    previews[slot].loading = false
  }
  image.onerror = () => {
    if (ticket !== previewGenerations[slot]) return
    clearLocal(slot)
    fileMessages[slot] = '无法预览此文件，请重新选择可由浏览器读取的图片。'
  }
  image.src = url
}
const onPreviewError = (slot: string, event: Event) => {
  const url = (event.target as HTMLImageElement).getAttribute('src')
  if (url && url === previewUrl(slot)) failedUrls[slot] = url
}
const save = () => { if (editable.value && !hasLocalPreview.value) emit('save') }
</script>
<template>
  <section class="multi-ad-page">
    <header class="page-heading">
      <div><h2>{{ info.title }}</h2><p>管理当前门店的广告素材草稿</p></div>
      <span class="draft-badge">{{ draft?.configured ? '已有后台草稿' : '后台草稿' }}</span>
    </header>
    <p v-if="!canQuery" class="state-panel" role="alert">当前账号没有查看广告的权限。</p>
    <p v-else-if="!contextReady" class="state-panel" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <div class="specifications" aria-label="图片规格提示">
        <div><span>{{ orderPage ? '图片要求' : '图片尺寸' }}</span><strong>{{ orderPage ? '宽高待确认；不超过1MB' : info.size }}</strong></div>
        <div><span>图片数量</span><strong>{{ orderPage ? '正面、反面各1张' : `最多 ${info.max} 张` }}</strong></div>
        <div><span>分辨率</span><strong>96</strong></div>
      </div>
      <p class="boundary">选择文件仅生成本地预览，尚未上传；存在本地预览时不能保存。保存仅更新后台素材草稿，终端展示及小程序消费尚未接入。</p>
      <p v-if="!canSave" class="feedback" role="status">当前账号为只读状态，可查看草稿，不能编辑、选择文件或保存。</p>
      <p v-if="message" class="feedback" role="status">{{ message }}</p>
      <div class="editor-toolbar"><span>{{ orderPage ? '订单页正反面广告图' : `广告图片 · 最多 ${info.max} 张` }}</span><el-button :disabled="busy" @click="emit('reload')">重新加载</el-button></div>
      <p v-if="busy && !draft" class="state-panel" role="status">正在加载广告草稿…</p>
      <p v-else-if="!draft" class="state-panel" role="status">尚未取得广告草稿，请重新加载后查看。</p>
      <el-form v-else class="ad-form" :aria-busy="busy" @submit.prevent="save">
        <div v-if="position === 'COMPUTER_TERMINAL' || orderPage" class="enable-row"><span>{{ orderPage ? '是否开启订单页广告：' : '是否开启自定义广告图：' }}</span><el-switch :model-value="false" disabled /><small>启用与展示规则待确认</small></div>
        <div class="image-grid">
          <article v-for="slot in slots" :key="slot" class="image-card">
            <h3>{{ slotLabel(slot) }}</h3>
            <div class="preview-frame">
              <img v-if="previewUrl(slot) && failedUrls[slot] !== previewUrl(slot)" :key="previewUrl(slot)" :src="previewUrl(slot)" :alt="`${slotLabel(slot)}${previews[slot] ? '，本地预览尚未上传' : '，素材地址预览'}`" referrerpolicy="no-referrer" @error="onPreviewError(slot, $event)" />
              <div v-else class="preview-empty"><span aria-hidden="true">＋</span><strong>{{ failedUrls[slot] ? '图片暂时无法预览' : '暂无广告图片' }}</strong><small>{{ orderPage ? '图片宽高待确认' : info.size }}</small></div>
            </div>
            <p class="preview-caption">{{ previews[slot] ? '本地预览 · 尚未上传' : previewUrl(slot) ? '素材地址预览 · 非投放效果' : '草稿图片预览' }}</p>
            <div class="file-actions"><label class="file-picker" :class="{ disabled: !editable }">选择文件<input type="file" accept="image/*" :disabled="!editable" :aria-label="`选择${slotLabel(slot)}，仅本地预览`" @change="selectFile(slot, $event)" /></label><el-button v-if="previews[slot]" :disabled="busy" @click="clearLocal(slot)">撤销本地预览</el-button></div>
            <p v-if="previews[slot]" class="local-file" role="status">{{ previews[slot].name }}<br />{{ previews[slot].loading ? '正在读取图片…' : previews[slot].dimensions }}<br />本地预览尚未上传，不能保存为素材。</p>
            <p v-if="fileMessages[slot]" class="feedback" role="alert">{{ fileMessages[slot] }}</p>
            <label class="url-label" :for="`ad-url-${slot}`">已持久化素材地址</label>
            <el-input :id="`ad-url-${slot}`" :model-value="urls[slot]" :disabled="!editable" maxlength="2048" placeholder="当前账号已持久化图片的HTTPS地址" @update:model-value="emit('update-url', slot, $event)" />
            <p class="field-description">已有素材仅可替换，删除规则待确认。</p>
          </article>
        </div>
        <div v-if="position === 'COMPUTER_TERMINAL'" class="success-editor">
          <label for="ad-success-source">订单支付成功后广告语（HTML源码）</label>
          <el-input id="ad-success-source" :model-value="successText" type="textarea" :rows="5" maxlength="10000" :disabled="!editable" placeholder="富文本HTML草稿" @update:model-value="emit('update:successText', $event)" />
          <p class="field-description">仅编辑和保存HTML源稿；展示效果待确认。</p>
        </div>
        <footer class="save-row">
          <el-button type="primary" :disabled="!editable || hasLocalPreview" :loading="busy && !!draft" @click="save">保存草稿</el-button>
          <el-button v-if="hasLocalPreview" :disabled="busy" @click="clearAll">清除全部本地预览</el-button>
          <span v-if="locked" role="status">请先重新加载并核对草稿，再继续编辑。</span>
          <span v-else-if="hasLocalPreview" role="status">请先撤销本地预览，再保存已持久化素材地址。</span>
          <span v-else-if="busy" role="status">正在保存广告草稿…</span>
          <span v-else>仅保存后台草稿；启用、删除及投放尚未开放。</span>
        </footer>
        <p class="field-description">尺寸与分辨率为规格提示，不代表已完成图片校验；上传格式白名单、裁剪及轮播间隔待确认。</p>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.multi-ad-page { min-height: 100%; padding: 24px; background: #fff; color: #334155; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
h2 { margin: 0; color: #1e293b; font-size: 19px; font-weight: 600; }
.page-heading p { margin: 8px 0 0; color: #8490a2; font-size: 13px; }
.draft-badge { padding: 5px 11px; border-radius: 4px; background: #eff6ff; color: #3b82f6; font-size: 12px; white-space: nowrap; }
.specifications { display: flex; flex-wrap: wrap; gap: 36px; margin-top: 24px; padding: 18px 22px; background: #f8fafc; border: 1px solid #edf0f3; border-radius: 4px; }
.specifications div { display: flex; flex-direction: column; gap: 8px; min-width: 100px; }
.specifications span { color: #8490a2; font-size: 12px; }
.specifications strong { font-size: 14px; font-weight: 500; }
.boundary, .feedback { padding: 10px 13px; font-size: 13px; line-height: 1.7; border-radius: 4px; overflow-wrap: anywhere; }
.boundary { margin: 16px 0; background: #f0f7ff; color: #526d89; }
.feedback { background: #fffbeb; color: #8a651d; }
.editor-toolbar { display: flex; align-items: center; justify-content: space-between; margin: 24px 0 14px; font-size: 14px; }
.ad-form { max-width: 1200px; }
.enable-row { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 20px; font-size: 13px; }
.enable-row small { color: #8490a2; }
.image-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.image-card { min-width: 0; padding: 20px; border: 1px solid #e6eaf0; border-radius: 5px; }
h3 { margin: 0 0 16px; font-size: 14px; font-weight: 500; }
.preview-frame { display: grid; height: 210px; place-items: center; overflow: hidden; background: #f6f8fb; border: 1px dashed #d8dfe8; border-radius: 4px; }
.preview-frame img { display: block; width: 100%; height: 100%; min-height: 0; object-fit: contain; }
.preview-empty { display: flex; flex-direction: column; align-items: center; gap: 9px; padding: 20px; text-align: center; color: #8c99ab; }
.preview-empty > span { font-size: 34px; font-weight: 300; line-height: 1; }
.preview-empty strong { font-size: 13px; font-weight: 400; }
.preview-empty small, .preview-caption { font-size: 12px; }
.preview-caption { margin: 10px 0 16px; color: #8490a2; text-align: center; }
.file-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.file-picker { position: relative; display: inline-flex; align-items: center; height: 32px; padding: 0 14px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 14px; color: #606266; background: #fff; overflow: hidden; }
.file-picker:focus-within { outline: 2px solid #409eff; outline-offset: 2px; }
.file-picker input { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
.file-picker.disabled { color: #a8abb2; background: #f5f7fa; }
.file-picker.disabled input { cursor: not-allowed; }
.file-actions .el-button + .el-button { margin-left: 0; }
.local-file { font-size: 12px; line-height: 1.8; color: #997329; overflow-wrap: anywhere; }
.url-label { display: block; margin: 22px 0 9px; font-size: 13px; }
.field-description { margin: 8px 0 0; color: #8490a2; font-size: 12px; line-height: 1.8; }
.success-editor { margin-top: 24px; padding: 20px; border: 1px solid #e6eaf0; border-radius: 5px; }
.success-editor label { display: block; margin-bottom: 12px; font-size: 13px; }
.save-row { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; margin-top: 24px; padding-top: 22px; border-top: 1px solid #edf0f3; }
.save-row span { color: #8490a2; font-size: 12px; }
.state-panel { padding: 40px 20px; border: 1px solid #edf0f3; color: #8490a2; font-size: 13px; text-align: center; }
@media (max-width: 800px) { .multi-ad-page { padding: 16px; } .image-grid { grid-template-columns: minmax(0, 1fr); } .specifications { gap: 20px; } .image-card { padding: 16px; } }
</style>
