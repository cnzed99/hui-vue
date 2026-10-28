<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FormItemRule } from 'element-plus'

defineOptions({ name: 'PrintStoreProfileFields' })

export interface StoreProfileValue {
  storeName: string
  notice: string
  province: string
  city: string
  district: string
  street: string
  address: string
  contact: string
  telephone: string
  qq: string
  openHour: string
  openMinute: string
  closeHour: string
  closeMinute: string
  businessOpen: boolean | null
}
export type StoreRegionLevel = 'province' | 'city' | 'district' | 'street'
export interface StoreRegionOption { value: string; label: string; disabled?: boolean }
type TextField = Exclude<keyof StoreProfileValue, 'businessOpen'>

// Use inside the parent's el-form with :model bound to the same v-model object.
// Options, existing values, field errors and authorization decisions belong to the parent.
const props = withDefaults(defineProps<{
  modelValue: StoreProfileValue
  regionOptions?: Partial<Record<StoreRegionLevel, StoreRegionOption[]>>
  errors?: Partial<Record<keyof StoreProfileValue, string>>
  readonly?: boolean
  disabled?: boolean
  officialAccountQrUrl?: string
  miniProgramQrUrl?: string
}>(), {
  regionOptions: () => ({}),
  errors: () => ({}),
  readonly: false,
  disabled: false,
  officialAccountQrUrl: '',
  miniProgramQrUrl: ''
})
const emit = defineEmits<{
  'update:modelValue': [value: StoreProfileValue]
  'region-change': [change: { level: StoreRegionLevel; value: string; modelValue: StoreProfileValue }]
}>()
const locked = computed(() => props.readonly || props.disabled)
const hours = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'))
const minutes = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'))
const regions: { key: StoreRegionLevel; label: string }[] = [
  { key: 'province', label: '省' }, { key: 'city', label: '市' },
  { key: 'district', label: '区县' }, { key: 'street', label: '街道' }
]
// Technical storage capacities only; optional values may remain unconfigured.
const rules: Partial<Record<keyof StoreProfileValue, FormItemRule[]>> = {
  storeName: [{ required: true, whitespace: true, message: '请输入门店名称', trigger: 'blur' }],
  notice: [{ max: 2000, message: '公告最多2000个字符', trigger: 'blur' }],
  address: [{ max: 500, message: '地址最多500个字符', trigger: 'blur' }],
  contact: [{ max: 128, message: '联系人最多128个字符', trigger: 'blur' }],
  telephone: [{ max: 64, message: '联系电话最多64个字符', trigger: 'blur' }]
}
const updateText = (key: TextField, value: string | number) => {
  if (!locked.value) emit('update:modelValue', { ...props.modelValue, [key]: String(value) })
}
const changeRegion = (level: StoreRegionLevel, value: string) => {
  if (locked.value) return
  const next = { ...props.modelValue, [level]: value }
  // Changing an ancestor invalidates only its child selections. The parent supplies the next options.
  const index = regions.findIndex((region) => region.key === level)
  regions.slice(index + 1).forEach((region) => { next[region.key] = '' })
  emit('update:modelValue', next)
  emit('region-change', { level, value, modelValue: next })
}
const changeBusinessOpen = (value: string | number | boolean) => {
  if (!locked.value && typeof value === 'boolean') {
    emit('update:modelValue', { ...props.modelValue, businessOpen: value })
  }
}
const officialQrFailed = ref(false)
const miniQrFailed = ref(false)
watch(() => props.officialAccountQrUrl, () => { officialQrFailed.value = false })
watch(() => props.miniProgramQrUrl, () => { miniQrFailed.value = false })
</script>

<template>
  <div class="store-fields" role="group" aria-label="编辑门店" data-testid="print-store-profile-fields">
    <el-form-item label="门店名称：" prop="storeName" :rules="rules.storeName" :error="errors.storeName">
      <el-input :model-value="modelValue.storeName" maxlength="128" :readonly="readonly" :disabled="disabled"
        aria-label="门店名称" @update:model-value="updateText('storeName', $event)" />
      <p class="store-fields__hint">名称最多128个字符（存储容量）</p>
    </el-form-item>
    <el-form-item label="门店公告：" prop="notice" :rules="rules.notice" :error="errors.notice">
      <el-input :model-value="modelValue.notice" type="textarea" :rows="3" maxlength="2000"
        :readonly="readonly" :disabled="disabled" aria-label="门店公告"
        @update:model-value="updateText('notice', $event)" />
      <p class="store-fields__hint">公告可留空，最多2000个字符</p>
    </el-form-item>
    <el-form-item label="所在区域：">
      <div class="store-fields__region">
        <el-form-item v-for="region in regions" :key="region.key" :prop="region.key" :error="errors[region.key]">
          <el-select :model-value="modelValue[region.key]" :placeholder="region.label" :aria-label="region.label"
            :disabled="locked || !(regionOptions[region.key]?.length)" @change="changeRegion(region.key, $event)">
            <el-option v-for="option in regionOptions[region.key] || []" :key="option.value"
              :label="option.label" :value="option.value" :disabled="option.disabled" />
          </el-select>
        </el-form-item>
      </div>
      <p class="store-fields__hint">地区字典未配置，暂不可编辑</p>
    </el-form-item>
    <el-form-item label="详细地址：" prop="address" :rules="rules.address" :error="errors.address">
      <el-input :model-value="modelValue.address" maxlength="500" :readonly="readonly"
        :disabled="disabled" aria-label="详细地址" @update:model-value="updateText('address', $event)" />
      <p class="store-fields__hint">详细地址可留空，最多500个字符</p>
    </el-form-item>
    <el-form-item label="联系人：" prop="contact" :rules="rules.contact" :error="errors.contact">
      <el-input :model-value="modelValue.contact" :readonly="readonly" :disabled="disabled"
        aria-label="联系人" @update:model-value="updateText('contact', $event)" />
    </el-form-item>
    <el-form-item label="联系电话：" prop="telephone" :rules="rules.telephone" :error="errors.telephone">
      <el-input :model-value="modelValue.telephone" type="tel" :readonly="readonly" :disabled="disabled"
        aria-label="联系电话" @update:model-value="updateText('telephone', $event)" />
    </el-form-item>
    <el-form-item label="客服QQ：" prop="qq" :error="errors.qq">
      <el-input :model-value="modelValue.qq" :readonly="readonly" :disabled="disabled"
        aria-label="客服QQ" @update:model-value="updateText('qq', $event)" />
    </el-form-item>
    <el-form-item label="营业时间：">
      <div class="store-fields__time" role="group" aria-label="营业开始及结束时间">
        <el-form-item prop="openHour" :error="errors.openHour">
          <el-select :model-value="modelValue.openHour" placeholder="时" :disabled="locked" aria-label="营业开始小时"
            @change="updateText('openHour', $event)">
            <el-option v-for="hour in hours" :key="hour" :label="hour" :value="hour" />
          </el-select>
        </el-form-item>
        <span>:</span>
        <el-form-item prop="openMinute" :error="errors.openMinute">
          <el-select :model-value="modelValue.openMinute" placeholder="分" :disabled="locked" aria-label="营业开始分钟"
            @change="updateText('openMinute', $event)">
            <el-option v-for="minute in minutes" :key="minute" :label="minute" :value="minute" />
          </el-select>
        </el-form-item>
        <span class="store-fields__time-separator">至</span>
        <el-form-item prop="closeHour" :error="errors.closeHour">
          <el-select :model-value="modelValue.closeHour" placeholder="时" :disabled="locked" aria-label="营业结束小时"
            @change="updateText('closeHour', $event)">
            <el-option v-for="hour in hours" :key="hour" :label="hour" :value="hour" />
          </el-select>
        </el-form-item>
        <span>:</span>
        <el-form-item prop="closeMinute" :error="errors.closeMinute">
          <el-select :model-value="modelValue.closeMinute" placeholder="分" :disabled="locked" aria-label="营业结束分钟"
            @change="updateText('closeMinute', $event)">
            <el-option v-for="minute in minutes" :key="minute" :label="minute" :value="minute" />
          </el-select>
        </el-form-item>
      </div>
    </el-form-item>
    <el-form-item label="门店开关：" prop="businessOpen" :error="errors.businessOpen">
      <el-switch :model-value="modelValue.businessOpen === true" :disabled="locked" inline-prompt active-text="ON"
        inactive-text="OFF" aria-label="门店开关" @update:model-value="changeBusinessOpen" />
      <p class="store-fields__hint">{{ modelValue.businessOpen === null ? '营业状态未配置；点击开关可设为营业' : '营业开关独立于管理账号及门店准入状态' }}</p>
    </el-form-item>
    <el-form-item label="微信公众号门店二维码：">
      <div class="store-fields__qr-content">
        <img v-if="officialAccountQrUrl && !officialQrFailed" :src="officialAccountQrUrl" class="store-fields__qr"
          alt="微信公众号门店二维码" referrerpolicy="no-referrer" @error="officialQrFailed = true" />
        <div v-else class="store-fields__qr-placeholder" role="status">暂无二维码</div>
        <p class="store-fields__hint">用户微信扫码关注公众号绑定您的门店，自动成为会员，会员充值、开通会员、接收订单信息等</p>
      </div>
    </el-form-item>
    <el-form-item label="小程序门店二维码：">
      <div class="store-fields__qr-content">
        <img v-if="miniProgramQrUrl && !miniQrFailed" :src="miniProgramQrUrl" class="store-fields__qr"
          alt="小程序门店二维码" referrerpolicy="no-referrer" @error="miniQrFailed = true" />
        <div v-else class="store-fields__qr-placeholder" role="status">暂无二维码</div>
        <p class="store-fields__hint">支持微信和QQ扫码直接打开门店小程序</p>
      </div>
    </el-form-item>
  </div>
</template>

<style scoped>
.store-fields { max-width: 880px; color: #263445; }
.store-fields > :deep(.el-form-item) { margin-bottom: 24px; }
.store-fields > :deep(.el-form-item > .el-form-item__label) { width: 192px; padding-right: 18px; }
.store-fields > :deep(.el-form-item > .el-form-item__content) { min-width: 0; }
.store-fields :deep(.el-input), .store-fields :deep(.el-textarea) { max-width: 500px; }
.store-fields__hint { flex-basis: 100%; margin: 6px 0 0; color: #64748b; font-size: 12px; line-height: 20px; }
.store-fields__region { display: grid; width: min(100%, 580px); grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.store-fields__region :deep(.el-form-item), .store-fields__time :deep(.el-form-item) { margin-bottom: 0; }
.store-fields__region :deep(.el-select) { width: 100%; }
.store-fields__time { display: flex; max-width: 100%; align-items: center; gap: 6px; }
.store-fields__time :deep(.el-select) { width: 76px; }
.store-fields__time-separator { padding: 0 6px; }
.store-fields__qr-content { width: 100%; }
.store-fields__qr { display: block; width: 160px; height: 160px; object-fit: contain; }
.store-fields__qr-placeholder { display: flex; width: 160px; height: 160px; align-items: center; justify-content: center; border: 1px solid #e2e8f0; background: #f8fafc; color: #64748b; font-size: 12px; }
@media (max-width: 640px) {
  .store-fields > :deep(.el-form-item) { display: block; }
  .store-fields > :deep(.el-form-item > .el-form-item__label) { float: none; width: auto; height: auto; padding-bottom: 6px; justify-content: flex-start; }
  .store-fields > :deep(.el-form-item > .el-form-item__content) { margin-left: 0 !important; }
  .store-fields__region { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 8px; }
  .store-fields__time { gap: 3px; }
  .store-fields__time :deep(.el-select) { width: 64px; }
  .store-fields__time-separator { padding: 0 2px; }
}
</style>
