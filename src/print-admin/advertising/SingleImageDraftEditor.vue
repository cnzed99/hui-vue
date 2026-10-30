<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { AdDraft } from './api'
import { findPosition } from './positions'
import type { AdPosition } from './positions'

const props = defineProps<{
  position: AdPosition
  modelValue?: string
  draft: AdDraft | null
  busy: boolean
  locked: boolean
  message: string
  canQuery: boolean
  canSave: boolean
  contextReady: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; reload: []; save: [] }>()
const info = computed(() => findPosition(props.position)!)
const editable = computed(() => props.canQuery && props.contextReady && props.canSave && !props.busy && !props.locked && !!props.draft)
const fileInput = ref<HTMLInputElement>()
const localUrl = ref('')
const localName = ref('')
const localDimensions = ref('')
const localLoading = ref(false)
const fileMessage = ref('')
const previewFailed = ref(false)
let previewGeneration = 0
const persistedUrl = computed(() => {
  try {
    const url = new URL(props.modelValue?.trim() || '')
    return url.protocol === 'https:' && !url.username && !url.password && !url.hash ? url.href : ''
  } catch { return '' }
})
const previewUrl = computed(() => localUrl.value || persistedUrl.value)
watch(previewUrl, () => { previewFailed.value = false }, { flush: 'sync' })
const clearLocal = () => {
  previewGeneration++
  if (localUrl.value) URL.revokeObjectURL(localUrl.value)
  localUrl.value = ''; localName.value = ''; localDimensions.value = ''
  localLoading.value = false; fileMessage.value = ''
}
watch(() => props.draft, clearLocal)
onBeforeUnmount(clearLocal)
const selectFile = (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !editable.value) return
  clearLocal()
  const ticket = previewGeneration
  localUrl.value = URL.createObjectURL(file)
  localName.value = file.name
  localLoading.value = true
  const image = new Image()
  image.onload = () => {
    if (ticket !== previewGeneration) return
    localDimensions.value = `${image.naturalWidth} × ${image.naturalHeight} 像素`
    localLoading.value = false
  }
  image.onerror = () => {
    if (ticket !== previewGeneration) return
    clearLocal()
    fileMessage.value = '无法预览此文件，请重新选择可由浏览器读取的图片。'
  }
  image.src = localUrl.value
}
const onPreviewError = (event: Event) => {
  if ((event.target as HTMLImageElement).getAttribute('src') === previewUrl.value) previewFailed.value = true
}
const save = () => {
  if (editable.value && !localUrl.value) emit('save')
}
</script>
<template>
  <section class="single-ad-page">
    <header class="page-heading">
      <div><h2>{{ info.title }}</h2><p>管理当前门店的广告图片草稿</p></div>
      <span class="draft-badge">{{ draft?.configured ? '已有后台草稿' : '后台草稿' }}</span>
    </header>
    <p v-if="!canQuery" class="state-panel" role="alert">当前账号没有查看广告的权限。</p>
    <p v-else-if="!contextReady" class="state-panel" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <div class="specifications" aria-label="图片规格提示">
        <div><span>图片尺寸</span><strong>{{ info.size }}</strong></div>
        <div><span>图片数量</span><strong>最多 {{ info.max }} 张</strong></div>
        <div><span>分辨率</span><strong>96</strong></div>
      </div>
      <p class="boundary">选择文件仅生成本地预览；图片上传尚未开放。保存仅更新后台素材草稿，终端展示及小程序消费尚未接入。</p>
      <p v-if="!canSave" class="readonly-note" role="status">当前账号为只读状态，可查看草稿，不能选择文件或保存。</p>
      <div v-if="message" class="feedback" role="status">{{ message }}</div>
      <div class="editor-toolbar">
        <span>{{ draft ? (draft.assets.length ? '广告图片 · 1 / 1' : '广告图片 · 0 / 1') : '广告图片' }}</span>
        <el-button :disabled="busy" @click="emit('reload')">重新加载</el-button>
      </div>
      <div v-if="busy && !draft" class="state-panel" role="status">正在加载广告草稿…</div>
      <div v-else-if="!draft" class="state-panel" role="status">尚未取得广告草稿，请重新加载后查看。</div>
      <div v-else class="editor-card" :aria-busy="busy">
        <div class="preview-column">
          <div class="preview-frame" :class="{ 'share-frame': position === 'MINI_SHARE' }">
            <img v-if="previewUrl && !previewFailed" :key="previewUrl" :src="previewUrl" :alt="localUrl ? '本地图片预览，尚未上传' : '素材地址预览'" referrerpolicy="no-referrer" @error="onPreviewError" />
            <div v-else class="preview-empty"><span aria-hidden="true">＋</span><strong>{{ previewFailed ? '图片暂时无法预览' : '暂无广告图片' }}</strong><small>{{ previewFailed ? '请核对素材地址或重新选择本地图片' : '选择一张图片查看本地预览' }}</small></div>
          </div>
          <p class="preview-caption">{{ localUrl ? '本地预览 · 尚未上传' : previewUrl ? '素材地址预览 · 非投放效果' : '草稿图片预览' }}</p>
        </div>
        <div class="editor-fields">
          <h3>广告图片</h3>
          <p class="field-description">最多1张，图片尺寸{{ info.size }}，分辨率96。</p>
          <input ref="fileInput" class="file-input" type="file" accept="image/*" :disabled="!editable" aria-label="选择一张图片，仅本地预览" @change="selectFile" />
          <div class="file-actions"><el-button :disabled="!editable" @click="fileInput?.click()">选择文件</el-button><el-button v-if="localUrl" :disabled="busy" @click="clearLocal">撤销本地预览</el-button></div>
          <p v-if="localUrl" class="local-file" role="status">{{ localName }}<br />{{ localLoading ? '正在读取图片…' : localDimensions }}<br />本地预览尚未上传，不能保存为素材。</p>
          <p v-if="fileMessage" class="feedback" role="alert">{{ fileMessage }}</p>
          <label class="url-label" for="single-ad-url">已持久化素材地址</label>
          <el-input id="single-ad-url" :model-value="modelValue" :disabled="!editable" maxlength="2048" placeholder="当前账号已持久化图片的HTTPS地址" @update:model-value="emit('update:modelValue', $event)" />
          <p class="field-description">已有草稿仅可替换素材，删除规则待确认。尺寸提示不代表已完成图片校验。</p>
        </div>
        <footer class="save-row">
          <el-button type="primary" :disabled="!editable || !!localUrl" :loading="busy && !!draft" @click="save">保存草稿</el-button>
          <span v-if="locked" role="status">请先重新加载并核对草稿，再继续编辑。</span>
          <span v-else-if="busy" role="status">正在保存广告草稿…</span>
          <span v-else>仅保存后台草稿；启用、删除及投放尚未开放。</span>
        </footer>
      </div>
    </template>
  </section>
</template>
<style scoped>
.single-ad-page { min-height: 100%; padding: 24px; background: #fff; color: #334155; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
h2 { margin: 0; color: #1e293b; font-size: 19px; font-weight: 600; }
.page-heading p { margin: 8px 0 0; color: #8490a2; font-size: 13px; }
.draft-badge { padding: 5px 11px; border-radius: 4px; background: #eff6ff; color: #3b82f6; font-size: 12px; white-space: nowrap; }
.specifications { display: flex; gap: 40px; margin-top: 24px; padding: 18px 22px; background: #f8fafc; border: 1px solid #edf0f3; border-radius: 4px; }
.specifications div { display: flex; flex-direction: column; gap: 8px; min-width: 100px; }
.specifications span { color: #8490a2; font-size: 12px; }
.specifications strong { font-size: 14px; font-weight: 500; }
.boundary, .readonly-note, .feedback { padding: 10px 13px; font-size: 13px; line-height: 1.7; border-radius: 4px; overflow-wrap: anywhere; }
.boundary { margin: 16px 0; background: #f0f7ff; color: #526d89; }
.readonly-note, .feedback { background: #fffbeb; color: #8a651d; }
.editor-toolbar { display: flex; align-items: center; justify-content: space-between; margin: 24px 0 14px; font-size: 14px; }
.editor-card { display: grid; grid-template-columns: minmax(220px, 440px) minmax(240px, 1fr); gap: 28px; max-width: 1040px; padding: 24px; border: 1px solid #e6eaf0; border-radius: 5px; }
.preview-frame { display: grid; aspect-ratio: 1920 / 1080; place-items: center; overflow: hidden; background: #f6f8fb; border: 1px dashed #d8dfe8; border-radius: 4px; }
.share-frame { aspect-ratio: 627 / 486; }
.preview-frame img { display: block; width: 100%; height: 100%; min-height: 0; object-fit: contain; }
.preview-empty { display: flex; flex-direction: column; align-items: center; gap: 9px; padding: 20px; text-align: center; color: #8c99ab; }
.preview-empty > span { font-size: 34px; font-weight: 300; line-height: 1; }
.preview-empty strong { font-size: 13px; font-weight: 400; }
.preview-empty small { font-size: 12px; }
.preview-caption { margin: 10px 0 0; font-size: 12px; color: #8490a2; text-align: center; }
h3 { margin: 0 0 10px; font-size: 14px; font-weight: 500; }
.field-description { margin: 8px 0 16px; font-size: 12px; line-height: 1.8; color: #8490a2; }
.file-input { display: none; }
.file-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.file-actions .el-button + .el-button { margin-left: 0; }
.local-file { font-size: 12px; line-height: 1.8; color: #997329; overflow-wrap: anywhere; }
.url-label { display: block; margin: 24px 0 9px; font-size: 13px; }
.save-row { grid-column: 1 / -1; display: flex; align-items: center; flex-wrap: wrap; gap: 14px; padding-top: 22px; border-top: 1px solid #edf0f3; }
.save-row span { color: #8490a2; font-size: 12px; }
.state-panel { padding: 40px 20px; border: 1px solid #edf0f3; color: #8490a2; font-size: 13px; text-align: center; }
@media (max-width: 800px) { .single-ad-page { padding: 16px; } .specifications { flex-wrap: wrap; gap: 20px; } .editor-card { grid-template-columns: minmax(0, 1fr); padding: 18px; gap: 22px; } }
</style>
