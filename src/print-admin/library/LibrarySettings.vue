<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { getLibrarySettings, putLibrarySettings, librarySettingsFailure } from './settingsApi'
import { validateSettings } from './settingsModel'
import type { LibrarySettings } from './settingsModel'
defineOptions({ name: 'PrintLibrarySettings' })
const user = useUserStore()
const context = usePrintContextStore()
const permitted = (p: string) => user.permissions.has('*:*:*') || user.permissions.has(p)
const canQuery = computed(() => permitted('print:library-settings:query'))
const canUpdate = computed(() => permitted('print:library-settings:update'))
const loaded = ref<LibrarySettings | null>(null)
const name = ref('')
const description = ref('')
const busy = ref(false)
const needsReload = ref(false)
const message = ref('')
let generation = 0
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
const load = async () => {
  if (!canQuery.value || context.status !== 'ready') return
  const ticket = ++generation
  busy.value = true; loaded.value = null; name.value = ''; description.value = ''; message.value = ''
  try {
    const data = await getLibrarySettings()
    if (ticket !== generation) return
    loaded.value = data; name.value = data.name; description.value = data.description; needsReload.value = false
  } catch (error) {
    if (ticket === generation) message.value = librarySettingsFailure(error) === 'forbidden' ? '没有查看文库设置的权限。' : '文库设置加载失败，请重新加载。'
  } finally { if (ticket === generation) busy.value = false }
}
watch([identity, canQuery, canUpdate], () => {
  generation++; loaded.value = null; name.value = ''; description.value = ''; busy.value = false; needsReload.value = false; message.value = ''
  if (canQuery.value && context.status === 'ready') void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
const save = async () => {
  if (!loaded.value || !canUpdate.value || busy.value || needsReload.value || context.status !== 'ready') return
  const invalid = validateSettings(name.value, description.value)
  if (invalid) { message.value = invalid; return }
  const ticket = generation
  busy.value = true; message.value = ''
  try {
    const saved = await putLibrarySettings({ expectedVersion: loaded.value.version, name: name.value, description: description.value })
    if (ticket !== generation) return
    loaded.value = saved; name.value = saved.name; description.value = saved.description; message.value = '文库设置已保存。'
  } catch (error) {
    if (ticket !== generation) return
    const kind = librarySettingsFailure(error)
    if (kind === 'invalid') message.value = '请检查文库店名和描述的必填及长度要求。'
    else {
      needsReload.value = true
      message.value = kind === 'conflict' ? '文库设置已更新，请重新加载并核对后再保存。' :
        kind === 'forbidden' ? '没有修改文库设置的权限。' : '保存结果暂时无法确认，请重新加载核对后再编辑。'
    }
  } finally { if (ticket === generation) busy.value = false }
}
</script>

<template>
  <section class="library-settings" data-testid="print-library-settings">
    <h2>文库设置</h2>
    <p v-if="!canQuery" role="alert">当前账号没有查看文库设置的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="busy" role="status">正在处理文库设置…</p>
      <el-button :disabled="busy" @click="load">重新加载</el-button>
      <el-form v-if="loaded" label-width="130px" class="library-settings__form" @submit.prevent="save">
        <el-form-item label="文库店名：" required>
          <el-input v-model="name" :readonly="!canUpdate" :disabled="busy || needsReload" placeholder="3–20字符" />
        </el-form-item>
        <el-form-item label="小程序路径："><el-input model-value="未配置" readonly /></el-form-item>
        <el-form-item label="描述：" required>
          <el-input v-model="description" type="textarea" :rows="3" :readonly="!canUpdate" :disabled="busy || needsReload" placeholder="30字符以内" />
        </el-form-item>
        <el-form-item><el-button v-if="canUpdate" type="primary" :disabled="busy || needsReload" @click="save">保存</el-button><span v-else>当前账号仅可查看。</span></el-form-item>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.library-settings { padding: 20px 24px; background: #fff; min-height: 100%; }
.library-settings h2 { margin: 0 0 18px; font-size: 18px; }
.library-settings__form { margin-top: 24px; max-width: 740px; }
.library-settings p { color: #64748b; line-height: 1.7; }
</style>
