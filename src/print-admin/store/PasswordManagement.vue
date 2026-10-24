<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { InputPassword } from '@/components/InputPassword'
import { getUserProfile, updateUserPassword } from '@/api/system/user/profile'

defineOptions({ name: 'PrintPasswordManagement' })

const formRef = ref<FormInstance>()
const username = ref('')
const profileLoading = ref(false)
const profileReady = ref(false)
const profileFailed = ref(false)
const submitting = ref(false)
const saveFailed = ref(false)
const password = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
let active = true

const rules: FormRules = {
  oldPassword: [
    { required: true, message: '请输入旧密码', trigger: 'blur' },
    { min: 4, max: 16, message: '密码长度为 4–16 位', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 4, max: 16, message: '密码长度为 4–16 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        callback(value === password.newPassword ? undefined : new Error('两次输入的新密码不一致'))
      },
      trigger: 'blur'
    }
  ]
}

const clearPasswords = () => {
  password.oldPassword = ''
  password.newPassword = ''
  password.confirmPassword = ''
  formRef.value?.clearValidate()
}
const loadProfile = async () => {
  if (profileLoading.value) return
  profileLoading.value = true
  profileFailed.value = false
  profileReady.value = false
  username.value = ''
  try {
    const profile = await getUserProfile()
    if (!active) return
    if (typeof profile?.username !== 'string' || !profile.username) throw new Error('PROFILE_UNAVAILABLE')
    username.value = profile.username
    profileReady.value = true
  } catch {
    // The shared request interceptor displays the original API error as text.
    if (active) profileFailed.value = true
  } finally {
    if (active) profileLoading.value = false
  }
}
const submit = async () => {
  if (submitting.value || !profileReady.value || !formRef.value) return
  submitting.value = true
  saveFailed.value = false
  try {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid || !active) return
    await updateUserPassword(password.oldPassword, password.newPassword)
    clearPasswords()
    if (active) ElMessage.success('密码修改成功')
  } catch {
    // Do not log request objects or echo password values; keep shared API error handling.
    if (active) saveFailed.value = true
  } finally {
    if (active) submitting.value = false
  }
}
onMounted(() => { void loadProfile() })
onBeforeUnmount(() => {
  active = false
  clearPasswords()
})
</script>

<template>
  <section class="password-page" data-testid="print-password-management" :aria-busy="profileLoading || submitting">
    <header class="password-page__title"><h1>密码管理</h1></header>
    <div class="password-page__card">
      <h2>修改登录密码</h2>
      <p class="password-page__hint">修改当前登录账号的密码，请填写旧密码及 4–16 位新密码。</p>
      <p v-if="profileLoading" role="status">正在加载当前账号…</p>
      <div v-else-if="profileFailed" class="password-page__error" role="alert">
        <p>当前账号加载失败，请重试。</p>
        <el-button @click="loadProfile">重新加载</el-button>
      </div>
      <el-form v-if="profileReady" ref="formRef" class="password-page__form" :model="password"
        :rules="rules" label-width="100px" :disabled="submitting" @submit.prevent="submit">
        <el-form-item label="登录账号">
          <el-input :model-value="username" readonly autocomplete="username" aria-label="当前登录账号" />
        </el-form-item>
        <el-form-item label="旧密码" prop="oldPassword">
          <InputPassword v-model="password.oldPassword" autocomplete="current-password" aria-label="旧密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <InputPassword v-model="password.newPassword" strength autocomplete="new-password" aria-label="新密码" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <InputPassword v-model="password.confirmPassword" autocomplete="new-password" aria-label="确认新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="submitting" :disabled="submitting">保存</el-button>
          <el-button :disabled="submitting" @click="clearPasswords(); saveFailed = false">清空</el-button>
        </el-form-item>
        <p v-if="saveFailed" class="password-page__error" role="alert">密码未保存，请根据错误提示检查后重试。</p>
      </el-form>
    </div>
  </section>
</template>

<style scoped>
.password-page { color: #263445; }
.password-page__title { display: flex; align-items: center; height: 71px; padding: 0 19px; background: #fff; }
.password-page__title h1 { position: relative; margin: 0; font-size: 16px; line-height: 24px; font-weight: 600; }
.password-page__title h1::after { position: absolute; right: 0; bottom: -13px; left: 0; height: 3px; content: ''; background: #0284c7; }
.password-page__card { margin-top: 12px; padding: 24px 28px; background: #fff; }
.password-page h2 { margin: 0; font-size: 15px; font-weight: 600; }
.password-page__hint { margin: 16px 0 24px; color: #64748b; font-size: 12px; }
.password-page__form { max-width: 500px; }
.password-page__error { color: #b91c1c; }
</style>
