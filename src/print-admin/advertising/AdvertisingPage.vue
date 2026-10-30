<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { adFailure, getAdDraft, saveAdDraft } from './api'
import type { AdDraft } from './api'
import { slotsFor } from './positions'
import type { AdPosition } from './positions'
import SingleImageDraftEditor from './SingleImageDraftEditor.vue'
import MultiImageDraftEditor from './MultiImageDraftEditor.vue'
const props = defineProps<{ position: AdPosition }>()
const user = useUserStore()
const context = usePrintContextStore()
const singleImage = computed(() => props.position === 'COMPUTER_DESKTOP' || props.position === 'MINI_SHARE')
const permitted = (code: string) => user.permissions.has('*:*:*') || user.permissions.has(code)
const canQuery = computed(() => permitted('print:advertising:query'))
const canSave = computed(() => permitted('print:advertising:update'))
const draft = ref<AdDraft | null>(null)
const urls = reactive<Record<string, string>>({})
const successText = ref('')
const hasLocalPreview = ref(false)
const busy = ref(false)
const locked = ref(false)
const message = ref('')
const slots = computed(() => slotsFor(props.position))
const identity = computed(() => `${props.position}/${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
let generation = 0
const reset = () => { draft.value = null; hasLocalPreview.value = false; successText.value = ''; Object.keys(urls).forEach((k) => delete urls[k]); locked.value = false }
const apply = (data: AdDraft) => { draft.value = data; successText.value = data.successMessageHtml; Object.keys(urls).forEach((k) => delete urls[k]); data.assets.forEach((a) => { urls[a.slot] = a.url }) }
const load = async () => {
  if (busy.value || !canQuery.value || context.status !== 'ready') return
  const ticket = ++generation
  busy.value = true; message.value = ''; reset()
  try { const data = await getAdDraft(props.position); if (ticket === generation) apply(data) }
  catch (e) { if (ticket === generation) message.value = adFailure(e) === 'forbidden' ? '当前账号没有查看广告的权限。' : '广告素材暂时无法加载，请重新加载。' }
  finally { if (ticket === generation) busy.value = false }
}
watch([identity, canQuery, canSave], () => { generation++; reset(); busy.value = false; message.value = ''; if (canQuery.value && context.status === 'ready') void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
const save = async () => {
  if (busy.value || locked.value || hasLocalPreview.value || !draft.value || !canQuery.value || !canSave.value || context.status !== 'ready') return
  const assets = slots.value.filter((slot) => !!urls[slot]?.trim()).map((slot) => ({ slot, url: urls[slot].trim() }))
  if (draft.value.assets.some((old) => !assets.some((a) => a.slot === old.slot))) { message.value = '删除及关联规则待确认，已有素材不能移除。'; return }
  if (assets.some((a) => { try { const u = new URL(a.url); return u.protocol !== 'https:' || !!u.username || !!u.password || !!u.hash } catch { return true } })) { message.value = '请输入已持久化图片的HTTPS地址。'; return }
  const ticket = generation
  busy.value = true; message.value = ''
  try {
    const data = await saveAdDraft(props.position, { expectedVersion: draft.value.version, customEnabled: false, successMessageHtml: successText.value, assets })
    if (ticket !== generation) return
    apply(data); message.value = '后台素材草稿已保存。'
  } catch (e) {
    if (ticket !== generation) return
    const kind = adFailure(e)
    message.value = kind === 'asset' ? '图片须为当前账号已上传并持久化的素材；订单页图片不得超过1M。' : kind === 'invalid' ? '请检查素材数量、位置和字段。' : kind === 'pending' ? '启用、删除及关联规则待确认。' : kind === 'forbidden' ? '当前账号没有保存广告的权限。' : kind === 'conflict' ? '草稿已被修改，请重新加载核对。' : '保存结果暂时无法确认，请重新加载核对。'
    if (!['invalid', 'asset', 'pending'].includes(kind)) locked.value = true
  } finally { if (ticket === generation) busy.value = false }
}
</script>
<template>
  <SingleImageDraftEditor
    v-if="singleImage" :key="identity" v-model="urls[slots[0]]"
    :position="props.position" :draft="draft" :busy="busy" :locked="locked"
    :message="message" :can-query="canQuery" :can-save="canSave"
    :context-ready="context.status === 'ready'" @reload="load" @save="save"
  />
  <MultiImageDraftEditor
    v-else :key="identity" :urls="urls" v-model:success-text="successText"
    @update-url="(slot, value) => urls[slot] = value"
    :position="props.position" :draft="draft" :busy="busy" :locked="locked"
    :message="message" :can-query="canQuery" :can-save="canSave"
    :context-ready="context.status === 'ready'"
    @preview-change="hasLocalPreview = $event" @reload="load" @save="save"
  />
</template>
