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
const saved = ref(false)
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
  saved.value = false
  try {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid || !active) return
    await updateUserPassword(password.oldPassword, password.newPassword)
    clearPasswords()
    if (active) {
      saved.value = true
      ElMessage.success('密码修改成功')
    }
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
    <h2 class="password-page__title">密码管理</h2>
    <div class="password-page__card">
      <h3>修改登录密码</h3>
      <p class="password-page__hint">修改当前登录账号的密码，新密码长度为 4–16 位。</p>
      <ol class="password-page__steps" aria-label="修改密码流程">
        <li><span>1</span>确认登录账号</li>
        <li><span>2</span>填写旧密码与新密码</li>
        <li><span>3</span>保存修改</li>
      </ol>
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
          <InputPassword v-model="password.oldPassword" placeholder="请输入当前登录密码" autocomplete="current-password" aria-label="旧密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <InputPassword v-model="password.newPassword" placeholder="请输入 4–16 位新密码" strength autocomplete="new-password" aria-label="新密码" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <InputPassword v-model="password.confirmPassword" placeholder="请再次输入新密码" autocomplete="new-password" aria-label="确认新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="submitting" :disabled="submitting">{{ submitting ? '正在保存' : '保存修改' }}</el-button>
          <el-button :disabled="submitting" @click="clearPasswords(); saveFailed = false; saved = false">清空</el-button>
        </el-form-item>
        <p v-if="saved" class="password-page__success" role="status">密码修改成功，密码输入已清空。下次登录请使用新密码。</p>
        <p v-if="saveFailed" class="password-page__error" role="alert">密码未保存，请根据错误提示检查后重试。</p>
      </el-form>
      <p class="password-page__note">离开或刷新本页后，未保存的密码输入将清空。</p>
    </div>
  </section>
</template>

<style scoped>
.password-page { min-height: 100%; padding: 20px 24px; color: #263445; background: #fff; }
.password-page__title { margin: 0 0 24px; padding-bottom: 14px; border-bottom: 1px solid #e2e8f0; font-size: 16px; line-height: 24px; font-weight: 600; }
.password-page__card { max-width: 760px; }
.password-page h3 { margin: 0; font-size: 15px; font-weight: 600; }
.password-page__hint { margin: 12px 0 20px; color: #64748b; font-size: 13px; line-height: 1.7; }
.password-page__steps { display: flex; gap: 24px; margin: 0 0 28px; padding: 16px; list-style: none; color: #475569; background: #f4f7fb; }
.password-page__steps li { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.password-page__steps span { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; color: #0284c7; background: #e0f2fe; font-size: 12px; }
.password-page__form { max-width: 520px; }
.password-page__form :deep(.el-form-item) { margin-bottom: 24px; }
.password-page__note { margin: 24px 0 0; padding-top: 16px; border-top: 1px solid #e2e8f0; color: #64748b; font-size: 12px; line-height: 1.7; }
.password-page__error, .password-page__success { padding: 12px 16px; border-radius: 4px; line-height: 1.7; }
.password-page__error { color: #b91c1c; background: #fef2f2; }
.password-page__success { color: #166534; background: #f0fdf4; }
</style>
