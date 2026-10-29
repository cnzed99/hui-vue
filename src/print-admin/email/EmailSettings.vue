<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { emailSettingsFailure, getEmailSettings, putEmailSettings } from './api'
import { validateEmailDraft } from './model'
import type { EmailSettings } from './model'
defineOptions({ name: 'PrintEmailSettings' })
const user = useUserStore()
const context = usePrintContextStore()
const permitted = (p: string) => user.permissions.has('*:*:*') || user.permissions.has(p)
const canQuery = computed(() => permitted('print:store:query'))
const canUpdate = computed(() => permitted('print:store:update'))
const loaded = ref<EmailSettings | null>(null)
const draft = reactive({ username: '', password: '', smtpHost: '', smtpPort: '' })
const busy = ref(false)
const needsReload = ref(false)
const message = ref('')
let generation = 0
const reset = () => Object.assign(draft, { username: '', password: '', smtpHost: '', smtpPort: '' })
const fill = (data: EmailSettings) => Object.assign(draft, { username: data.username, password: '', smtpHost: data.smtpHost, smtpPort: data.smtpPort ? String(data.smtpPort) : '' })
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
const load = async () => {
  if (!canQuery.value || context.status !== 'ready' || busy.value) return
  const ticket = ++generation
  busy.value = true; loaded.value = null; reset(); message.value = ''
  try {
    const data = await getEmailSettings()
    if (ticket !== generation) return
    loaded.value = data; fill(data); needsReload.value = false
  } catch (error) {
    if (ticket === generation) message.value = emailSettingsFailure(error) === 'forbidden' ? '没有查看邮箱发送设置的权限。' : '邮箱发送设置加载失败，请重新加载。'
  } finally { if (ticket === generation) busy.value = false }
}
watch([identity, canQuery, canUpdate], () => {
  generation++; loaded.value = null; reset(); busy.value = false; needsReload.value = false; message.value = ''
  if (canQuery.value && context.status === 'ready') void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
const save = async () => {
  if (!loaded.value || !canUpdate.value || busy.value || needsReload.value || context.status !== 'ready') return
  const invalid = validateEmailDraft(draft, !loaded.value.passwordConfigured)
  if (invalid) { message.value = invalid; return }
  const ticket = generation
  busy.value = true; message.value = ''
  try {
    const saved = await putEmailSettings({ expectedVersion: loaded.value.version, username: draft.username,
      password: draft.password, smtpHost: draft.smtpHost, smtpPort: Number(draft.smtpPort) })
    if (ticket !== generation) return
    loaded.value = saved; fill(saved); message.value = '邮箱发送设置已保存。当前不会发送真实邮件。'
  } catch (error) {
    if (ticket !== generation) return
    const kind = emailSettingsFailure(error)
    if (kind === 'invalid') message.value = '请检查用户名、密码、SMTP地址和端口。'
    else if (kind === 'disabled') message.value = '邮箱发送设置保存尚未开放，请等待配置启用。'
    else if (kind === 'secret') message.value = '服务器尚未配置邮箱密码保护密钥，无法保存。'
    else {
      needsReload.value = true
      message.value = kind === 'conflict' ? '设置已更新，请重新加载并核对后再保存。' :
        kind === 'forbidden' ? '没有修改邮箱发送设置的权限。' : '保存结果暂时无法确认，请重新加载核对后再编辑。'
    }
  } finally { if (ticket === generation) busy.value = false }
}
</script>
<template>
  <section class="email-settings" data-testid="print-email-settings">
    <h2>邮箱发送设置</h2>
    <p v-if="!canQuery" role="alert">当前账号没有查看邮箱发送设置的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="busy" role="status">正在处理邮箱发送设置…</p>
      <el-button :disabled="busy" @click="load">重新加载</el-button>
      <el-form v-if="loaded" label-width="150px" class="email-settings__form" @submit.prevent="save">
        <p v-if="!loaded.configured" class="email-settings__notice">当前门店尚未配置发送邮件所用账户。</p>
        <el-form-item label="* 用户名："><el-input v-model="draft.username" :readonly="!canUpdate" :disabled="busy || needsReload" maxlength="320" /></el-form-item>
        <el-form-item label="* 密码：">
          <div class="email-settings__wide">
            <el-input v-model="draft.password" type="password" autocomplete="new-password" :readonly="!canUpdate" :disabled="busy || needsReload" maxlength="1024" />
            <p class="email-settings__hint">{{ loaded.passwordConfigured ? '密码已安全配置；留空表示保留原密码。' : '首次保存必须填写密码。' }}</p>
          </div>
        </el-form-item>
        <el-form-item label="* SMTP地址："><el-input v-model="draft.smtpHost" :readonly="!canUpdate" :disabled="busy || needsReload" maxlength="255" /></el-form-item>
        <el-form-item label="* 端口："><el-input v-model="draft.smtpPort" :readonly="!canUpdate" :disabled="busy || needsReload" maxlength="5" inputmode="numeric" /></el-form-item>
        <el-form-item>
          <el-button v-if="canUpdate" type="primary" :disabled="busy || needsReload" @click="save">保 存</el-button>
          <span v-else>当前账号仅可查看。</span>
        </el-form-item>
        <p class="email-settings__simulation">演示模式：真实邮件发送及TLS/SSL选项尚未接入；保存仅持久化加密配置，不会发送测试邮件。</p>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.email-settings { padding: 20px 24px; background: #fff; min-height: 100%; }
.email-settings h2 { margin: 0 0 18px; font-size: 18px; }
.email-settings__form { margin-top: 24px; max-width: 760px; }
.email-settings__wide { width: 100%; }
.email-settings__hint { margin: 5px 0 0; color: #888; line-height: 1.7; font-size: 13px; }
.email-settings__notice { color: #64748b; margin-bottom: 20px; }
.email-settings__simulation { margin: 10px 0 0 150px; color: #b45309; line-height: 1.7; font-size: 13px; }
</style>
