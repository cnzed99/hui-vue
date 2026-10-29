<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { adFailure, getAdDraft, saveAdDraft } from './api'
import type { AdDraft } from './api'
import { findPosition, slotsFor, slotLabel } from './positions'
import type { AdPosition } from './positions'
const props = defineProps<{ position: AdPosition }>()
const user = useUserStore()
const context = usePrintContextStore()
const info = computed(() => findPosition(props.position)!)
const permitted = (code: string) => user.permissions.has('*:*:*') || user.permissions.has(code)
const canQuery = computed(() => permitted('print:advertising:query'))
const canSave = computed(() => permitted('print:advertising:update'))
const draft = ref<AdDraft | null>(null)
const urls = reactive<Record<string, string>>({})
const successText = ref('')
const busy = ref(false)
const locked = ref(false)
const message = ref('')
const slots = computed(() => slotsFor(props.position))
const identity = computed(() => `${props.position}/${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
let generation = 0
const reset = () => { draft.value = null; successText.value = ''; Object.keys(urls).forEach((k) => delete urls[k]); locked.value = false }
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
  if (busy.value || locked.value || !draft.value || !canSave.value || context.status !== 'ready') return
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
  <section class="advertising-page">
    <h2>{{ info.title }}</h2>
    <p class="observed">{{ props.position === 'ORDER_PAGE' ? info.size : `最多可上传${info.max}张图片，图片尺寸${info.size}` }}，分辨率96。</p>
    <p v-if="!canQuery" role="alert">当前账号没有查看广告的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <p class="boundary">当前管理后台素材草稿；尺寸、格式、裁剪、轮播和展示优先级待确认。终端展示及小程序消费尚未接入。</p>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="busy" role="status">正在处理广告素材…</p>
      <el-button :disabled="busy" @click="load">重新加载</el-button>
      <el-form v-if="draft" label-width="190px" class="ad-form" @submit.prevent="save">
        <el-form-item v-if="props.position === 'COMPUTER_TERMINAL' || props.position === 'ORDER_PAGE'" :label="props.position === 'ORDER_PAGE' ? '是否开启订单页广告：' : '是否开启自定义广告图：'">
          <el-switch :model-value="false" disabled /><span class="hint">启用与展示规则待确认</span>
        </el-form-item>
        <el-form-item v-for="slot in slots" :key="slot" :label="`${slotLabel(slot)}：`">
          <div class="material-slot">
            <div class="material-placeholder">
              <button class="material-placeholder__add" type="button" disabled :aria-label="`${slotLabel(slot)}，素材上传尚未开放`" title="素材上传尚未开放"><span aria-hidden="true">＋</span></button>
              <span class="material-placeholder__size">{{ info.size }}</span>
            </div>
            <el-input v-model="urls[slot]" :disabled="busy || locked || !canSave" maxlength="2048" placeholder="当前账号已持久化图片的HTTPS地址" />
            <div class="slot-actions"><el-button disabled title="格式和安全图片上传接口待确认">选择文件</el-button><el-button disabled title="删除及文件引用规则待确认">删除</el-button></div>
            <span v-if="draft.assets.some((a) => a.slot === slot)" class="hint">已有素材仅可替换，删除规则待确认</span>
          </div>
        </el-form-item>
        <el-form-item v-if="props.position === 'COMPUTER_TERMINAL'" label="订单支付成功后广告语：">
          <div class="material-slot"><el-input v-model="successText" type="textarea" :rows="5" maxlength="10000" :disabled="busy || locked || !canSave" placeholder="富文本HTML草稿" /><span class="hint">富文本源稿保存；展示效果待确认。</span></div>
        </el-form-item>
        <el-form-item class="ad-save"><el-button type="primary" :disabled="busy || locked || !canSave" @click="save">保 存</el-button><span class="hint">保存后台草稿</span></el-form-item>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.advertising-page { background: #fff; padding: 20px 24px; min-height: 100%; }
h2 { margin: 0 0 20px; font-size: 18px; }
.observed, .boundary { font-size: 13px; color: #64748b; line-height: 1.7; }
.boundary { background: #f8fafc; padding: 10px 14px; }
.ad-form { margin-top: 24px; max-width: 960px; }
.material-slot { width: 100%; }
.material-placeholder { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; margin-bottom: 14px; }
.material-placeholder__add { display: grid; width: 50px; height: 50px; place-items: center; padding: 0; border: 0; background: #909090; color: #fff; cursor: not-allowed; }
.material-placeholder__add span { display: grid; width: 29px; height: 29px; place-items: center; border: 1px solid #fff; border-radius: 50%; font-size: 26px; line-height: 1; }
.material-placeholder__size { max-width: 280px; color: #777; font-size: 12px; line-height: 1.5; }
.slot-actions { display: flex; gap: 8px; margin-top: 8px; }
.ad-save { margin-top: 56px; padding-top: 24px; border-top: 1px solid #edf0f3; }
.hint { margin-left: 10px; color: #64748b; font-size: 12px; }
</style>
