<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import StoreProfileFields from './StoreProfileFields.vue'
import { emptyProfileForm, toProfileForm, toStoreProfileCommand } from './profileModel'
import type { StoreProfileResponse } from './profileModel'
import { getStoreProfile, putStoreProfile, profileFailureKind } from './profileApi'

defineOptions({ name: 'PrintStoreProfile' })
const user = useUserStore()
const context = usePrintContextStore()
const permitted = (permission: string) => user.permissions.has('*:*:*') || user.permissions.has(permission)
const canQuery = computed(() => permitted('print:store:query'))
const canUpdate = computed(() => permitted('print:store:update'))
const loaded = ref<StoreProfileResponse | null>(null)
const form = ref(emptyProfileForm())
const busy = ref(false)
const editing = ref(false)
const message = ref('')
const needsReload = ref(false)
let generation = 0
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
const load = async () => {
  if (!canQuery.value || context.status !== 'ready') return
  const ticket = ++generation
  busy.value = true; message.value = ''; editing.value = false; loaded.value = null
  try {
    const data = await getStoreProfile()
    if (ticket !== generation) return
    loaded.value = data; form.value = toProfileForm(data.profile); needsReload.value = false
    // GET is also the recovery path for uncertain saves. Keep the shell's current name synchronized.
    if (context.context) context.context.storeName = data.profile.storeName
  } catch (error) {
    if (ticket !== generation) return
    message.value = profileFailureKind(error) === 'forbidden' ? '没有查看门店资料的权限，请确认登录身份和门店分配。' : '门店资料加载失败，请重新加载。'
  } finally { if (ticket === generation) busy.value = false }
}
watch([identity, canQuery], () => {
  generation++; loaded.value = null; form.value = emptyProfileForm(); editing.value = false; busy.value = false; message.value = ''; needsReload.value = false
  if (canQuery.value && context.status === 'ready') void load()
}, { immediate: true })
watch(canUpdate, (allowed) => { if (!allowed) editing.value = false })
onBeforeUnmount(() => { generation++ })
const cancel = () => {
  if (!loaded.value || busy.value) return
  form.value = toProfileForm(loaded.value.profile); editing.value = false; message.value = ''
}
const save = async () => {
  if (!loaded.value || !canUpdate.value || !editing.value || busy.value || needsReload.value || context.status !== 'ready') return
  let data: ReturnType<typeof toStoreProfileCommand>
  try { data = toStoreProfileCommand(form.value, loaded.value) } catch (error) {
    message.value = error instanceof Error ? error.message : '请检查输入。'; return
  }
  const ticket = generation
  busy.value = true; message.value = ''
  try {
    const saved = await putStoreProfile(data)
    if (ticket !== generation) return
    loaded.value = saved; form.value = toProfileForm(saved.profile); editing.value = false
    if (context.context) context.context.storeName = saved.profile.storeName
    message.value = '门店资料已保存。'
  } catch (error) {
    if (ticket !== generation) return
    const kind = profileFailureKind(error)
    if (kind === 'invalid') message.value = '资料格式不符合要求，请检查名称、字段长度和营业时间；地区字典尚未配置。'
    else {
      needsReload.value = true; editing.value = false
      message.value = kind === 'conflict' ? '资料已被其他操作更新。请重新加载并核对最新内容，再编辑保存。' :
        kind === 'forbidden' ? '没有修改权限。请重新加载并确认登录身份。' :
        '保存结果暂时无法确认。请重新加载并核对资料，确认后再编辑；本页不会自动重发保存。'
    }
  } finally { if (ticket === generation) busy.value = false }
}
</script>

<template>
  <section class="store-profile" data-testid="print-store-profile">
    <h2>门店详情</h2>
    <p v-if="!canQuery" role="alert">当前账号没有查看门店资料的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <p v-if="busy" role="status">{{ editing ? '正在保存门店资料…' : '正在加载门店资料…' }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <div class="store-profile__actions">
        <el-button :disabled="busy" @click="load">重新加载</el-button>
        <el-button v-if="loaded && canUpdate && !editing" :disabled="busy || needsReload" @click="editing = true">编辑资料</el-button>
        <template v-if="editing && canUpdate">
          <el-button type="primary" :disabled="busy || needsReload" @click="save">保存</el-button>
          <el-button :disabled="busy" @click="cancel">取消</el-button>
        </template>
      </div>
      <template v-if="loaded">
        <p v-if="!loaded.configured" role="status">门店扩展资料尚未配置；名称来自当前门店。</p>
        <p v-if="!canUpdate">当前账号仅可查看全部资料。</p>
        <p>地区字典、微信公众号二维码和小程序二维码尚未配置。</p>
        <el-form :model="form" label-width="192px" :validate-on-rule-change="false">
          <StoreProfileFields v-model="form" :readonly="!editing || !canUpdate" :disabled="busy || needsReload" />
          <el-form-item v-if="editing" label="未配置选项：">
            <el-button :disabled="busy" @click="form.openHour = ''; form.openMinute = ''; form.closeHour = ''; form.closeMinute = ''">清空营业时间</el-button>
            <el-button :disabled="busy" @click="form.businessOpen = null">营业状态设为未配置</el-button>
          </el-form-item>
        </el-form>
      </template>
    </template>
  </section>
</template>

<style scoped>
.store-profile { padding: 20px 24px; background: white; min-height: 100%; }
.store-profile h2 { margin: 0 0 18px; font-size: 18px; }
.store-profile p { color: #64748b; line-height: 1.7; }
.store-profile__actions { display: flex; gap: 10px; margin: 16px 0 24px; }
</style>
