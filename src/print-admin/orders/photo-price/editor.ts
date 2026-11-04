import { onBeforeUnmount, ref, watch } from 'vue'
import type { Ref } from 'vue'
import {
  classifyPhotoPriceError,
  getPhotoPrice,
  normalizePhotoPrice,
  updatePhotoPrice
} from './api'
import type { PhotoPrice, PhotoPriceFailure } from './api'

export const usePhotoPriceEditor = (access: {
  key: Ref<string>
  ready: Ref<boolean>
  canUpdate: Ref<boolean>
}) => {
  const price = ref<PhotoPrice | null>(null)
  const draft = ref('1.00')
  const loading = ref(false)
  const saving = ref(false)
  const failure = ref<PhotoPriceFailure | null>(null)
  const notice = ref('')
  const inputError = ref('')
  let generation = 0
  const load = async () => {
    const ticket = ++generation
    price.value = null
    failure.value = null
    notice.value = ''
    inputError.value = ''
    loading.value = false
    saving.value = false
    draft.value = '1.00'
    if (!access.ready.value) return
    loading.value = true
    try {
      const result = await getPhotoPrice()
      if (ticket !== generation) return
      price.value = result
      draft.value = result.pricePerSheet
    } catch (error) {
      if (ticket === generation) failure.value = classifyPhotoPriceError(error)
    } finally {
      if (ticket === generation) loading.value = false
    }
  }
  const save = async () => {
    if (
      !access.ready.value ||
      !access.canUpdate.value ||
      !price.value ||
      loading.value ||
      saving.value
    )
      return
    const normalized = normalizePhotoPrice(draft.value)
    inputError.value = ''
    notice.value = ''
    if (normalized === null) {
      inputError.value = '请输入 0.01 至 999.00 元，最多两位小数，不含空格。'
      return
    }
    if (normalized === price.value.pricePerSheet) {
      inputError.value = '价格未变化，无需创建新版本。'
      return
    }
    const ticket = ++generation
    const expectedRevision = price.value.revision
    saving.value = true
    failure.value = null
    try {
      const result = await updatePhotoPrice({ expectedRevision, pricePerSheet: normalized })
      if (ticket !== generation) return
      price.value = result
      draft.value = result.pricePerSheet
      notice.value = '新价格版本已生效，仅影响之后新建的报价；既有报价和订单金额保持原快照。'
    } catch (error) {
      if (ticket !== generation) return
      failure.value = classifyPhotoPriceError(error)
      // No blind PUT retry after conflict or uncertain response. A GET must restore the baseline.
      price.value = null
      notice.value = '本次提交未取得可确认结果。请先刷新当前价格，核对服务端版本后再操作。'
    } finally {
      if (ticket === generation) saving.value = false
    }
  }
  watch(
    () => access.key.value,
    () => {
      void load()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
  })
  return { price, draft, loading, saving, failure, notice, inputError, load, save }
}
