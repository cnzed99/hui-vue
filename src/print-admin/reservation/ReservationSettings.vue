<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { getReservationSettings, putReservationSettings, reservationSettingsFailure } from './api'
import { validatePrefix } from './model'
import type { ReservationSettings } from './model'
defineOptions({ name: 'PrintReservationSettings' })
const user = useUserStore()
const context = usePrintContextStore()
const permitted = (p: string) => user.permissions.has('*:*:*') || user.permissions.has(p)
const canQuery = computed(() => permitted('print:store:query'))
const canUpdate = computed(() => permitted('print:store:update'))
const loaded = ref<ReservationSettings | null>(null)
const draft = reactive({ pickupEnabled: false, timedDeliveryEnabled: false, vipDeliveryEnabled: false, pickupCodePrefix: '', automaticPrintEnabled: false })
const busy = ref(false)
const needsReload = ref(false)
const message = ref('')
let generation = 0
const reset = () => Object.assign(draft, { pickupEnabled: false, timedDeliveryEnabled: false, vipDeliveryEnabled: false, pickupCodePrefix: '', automaticPrintEnabled: false })
const fill = (data: ReservationSettings) => Object.assign(draft, {
  pickupEnabled: data.pickupEnabled, timedDeliveryEnabled: data.timedDeliveryEnabled, vipDeliveryEnabled: data.vipDeliveryEnabled,
  pickupCodePrefix: data.pickupCodePrefix, automaticPrintEnabled: data.automaticPrintEnabled
})
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
const load = async () => {
  if (!canQuery.value || context.status !== 'ready' || busy.value) return
  const ticket = ++generation
  busy.value = true; loaded.value = null; reset(); message.value = ''
  try {
    const data = await getReservationSettings()
    if (ticket !== generation) return
    loaded.value = data; fill(data); needsReload.value = false
  } catch (error) {
    if (ticket === generation) message.value = reservationSettingsFailure(error) === 'forbidden' ? '没有查看远程预约设置的权限。' : '远程预约设置加载失败，请重新加载。'
  } finally { if (ticket === generation) busy.value = false }
}
watch([identity, canQuery, canUpdate], () => {
  generation++; loaded.value = null; reset(); busy.value = false; needsReload.value = false; message.value = ''
  if (canQuery.value && context.status === 'ready') void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
const save = async () => {
  if (!loaded.value || !canUpdate.value || busy.value || needsReload.value || context.status !== 'ready') return
  const invalid = validatePrefix(draft.pickupCodePrefix)
  if (invalid) { message.value = invalid; return }
  const ticket = generation
  busy.value = true; message.value = ''
  try {
    const saved = await putReservationSettings({ expectedVersion: loaded.value.version, ...draft })
    if (ticket !== generation) return
    loaded.value = saved; fill(saved); message.value = '远程预约设置已保存。自动打印仅保存模拟配置。'
  } catch (error) {
    if (ticket !== generation) return
    const kind = reservationSettingsFailure(error)
    if (kind === 'invalid') message.value = '请检查取件码前缀，仅支持2个字符以内的字母或数字。'
    else if (kind === 'disabled') message.value = '远程预约设置保存尚未开放，请等待配置启用。'
    else {
      needsReload.value = true
      message.value = kind === 'conflict' ? '设置已更新，请重新加载并核对后再保存。' : kind === 'forbidden' ? '没有修改远程预约设置的权限。' : '保存结果暂时无法确认，请重新加载核对后再编辑。'
    }
  } finally { if (ticket === generation) busy.value = false }
}
</script>
<template>
  <section class="reservation-settings" data-testid="print-reservation-settings">
    <h2>远程预约设置</h2>
    <p v-if="!canQuery" role="alert">当前账号没有查看远程预约设置的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="busy" role="status">正在处理远程预约设置…</p>
      <el-button :disabled="busy" @click="load">重新加载</el-button>
      <el-form v-if="loaded" label-width="235px" class="reservation-settings__form" @submit.prevent="save">
        <p v-if="!loaded.configured" class="reservation-settings__notice">当前门店尚未配置预约模式；未选中的模式和自动打印均处于关闭状态。</p>
        <el-form-item label="设置预约模式：">
          <div>
            <el-checkbox v-model="draft.pickupEnabled" :disabled="!canUpdate || busy || needsReload">远程打印上门自取</el-checkbox>
            <el-checkbox v-model="draft.timedDeliveryEnabled" :disabled="!canUpdate || busy || needsReload">远程打印定时配送</el-checkbox>
            <el-checkbox v-model="draft.vipDeliveryEnabled" :disabled="!canUpdate || busy || needsReload">远程打印VIP配送</el-checkbox>
            <p class="reservation-settings__hint">用户通过公众号、小程序、PC网页端远程上传文件下订单，可选择自取或配送</p>
          </div>
        </el-form-item>
        <el-form-item label="取件码前缀：">
          <div>
            <el-input v-model="draft.pickupCodePrefix" class="reservation-settings__prefix" :readonly="!canUpdate" :disabled="busy || needsReload" maxlength="2" />
            <p class="reservation-settings__hint">仅支持字母或者数字，限制两个字符以内，方便区分订单来源</p>
          </div>
        </el-form-item>
        <el-form-item label="是否开启预约订单自动打印：">
          <div>
            <el-switch v-model="draft.automaticPrintEnabled" :disabled="!canUpdate || busy || needsReload" inline-prompt active-text="ON" inactive-text="OFF" />
            <p class="reservation-settings__hint">默认手工打印，请谨慎开启。远程预约订单用户不在现场，会涉及分拣及安全等问题。</p>
            <p class="reservation-settings__simulation">演示模式：此开关仅保存自动打印配置；实际设备打印尚未接入。与“门店设置”中同名开关的联动待确认。</p>
          </div>
        </el-form-item>
        <el-form-item><el-button v-if="canUpdate" type="primary" :disabled="busy || needsReload" @click="save">保 存</el-button><span v-else>当前账号仅可查看。</span></el-form-item>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.reservation-settings { padding: 20px 24px; background: #fff; min-height: 100%; }
.reservation-settings h2 { margin: 0 0 18px; font-size: 18px; }
.reservation-settings__form { margin-top: 24px; max-width: 1040px; }
.reservation-settings__prefix { width: 180px; }
.reservation-settings__hint { margin: 5px 0 0; color: #888; line-height: 1.7; font-size: 13px; }
.reservation-settings__notice { color: #64748b; margin-bottom: 20px; }
.reservation-settings__simulation { margin: 5px 0 0; color: #b45309; line-height: 1.7; font-size: 13px; }
</style>
