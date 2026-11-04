<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { hasPermission } from '@/directives/permission/hasPermi'
import { usePrintContextStore } from '../../contextStore'
import { usePhotoPriceEditor } from './editor'

const user = useUserStore()
const context = usePrintContextStore()
const canQuery = computed(() => hasPermission(['print:photo-price:query']))
const canUpdate = computed(() => hasPermission(['print:photo-price:update']))
const ready = computed(
  () => canQuery.value && context.status === 'ready' && context.context?.userId === user.getUser.id
)
const key = computed(() =>
  [
    ready.value,
    canUpdate.value,
    user.getUser.id,
    context.context?.tenantId,
    context.context?.storeId,
    context.context?.userId,
    context.status
  ].join(':')
)
const { price, draft, loading, saving, failure, notice, inputError, load, save } =
  usePhotoPriceEditor({ key, ready, canUpdate })
</script>
<template>
  <article
    v-if="canQuery"
    class="orders-card photo-price"
    data-testid="photo-price-card"
    :aria-busy="loading || saving"
  >
    <div class="orders-toolbar">
      <h2>六寸照片纸演示价格</h2>
      <button type="button" :disabled="!ready || loading || saving" @click="load"
        >刷新当前价格</button
      >
    </div>
    <p>普通照片、六寸拼版和证件照按六寸相纸张数计费。开发初始价为 ¥1.00/张，以当前门店配置为准。</p>
    <p v-if="!ready" role="status">请等待当前账号的门店信息就绪后查看价格。</p>
    <p v-else-if="loading" role="status">正在加载当前门店价格…</p>
    <p v-if="failure" role="alert">{{ failure.message }}</p>
    <template v-if="price && ready">
      <dl class="orders-facts">
        <div
          ><dt>当前单价</dt><dd>¥{{ price.pricePerSheet }} / 六寸张</dd></div
        >
        <div
          ><dt>价格版本</dt><dd>{{ price.profileVersion }}</dd></div
        >
        <div
          ><dt>新报价状态</dt
          ><dd>{{ price.quoteEnabled ? '门店配置允许新报价' : '已禁用照片新报价' }}</dd></div
        >
      </dl>
      <p v-if="!price.quoteEnabled" role="status"
        >当前门店禁止创建照片新报价；保存价格不会开启报价。既有报价和订单仍按冻结快照查询。</p
      >
      <p v-else>服务开放情况以小程序提示为准；支付与打印设备仍为模拟。</p>
      <form v-if="canUpdate" class="photo-price__form" @submit.prevent="save">
        <label
          >新单价（元 / 六寸张）<input
            v-model="draft"
            inputmode="decimal"
            maxlength="6"
            :disabled="saving || loading"
            aria-describedby="photo-price-help"
        /></label>
        <button type="submit" :disabled="saving || loading">{{
          saving ? '正在保存…' : '保存为新价格版本'
        }}</button>
      </form>
      <p v-else>当前账号只有查看权限，修改价格请联系门店管理员。</p>
      <p id="photo-price-help"
        >单价范围
        ¥0.01–¥999.00，最多两位小数。保存后启用新的价格版本，只影响之后新建的报价；已有报价和订单金额不变。</p
      >
    </template>
    <p v-if="inputError" role="alert">{{ inputError }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
  </article>
</template>
<style scoped>
.photo-price p {
  color: #64748b;
  line-height: 1.7;
}
.photo-price h2 {
  margin: 0;
  font-size: 17px;
}
.photo-price__form {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
}
.photo-price__form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.photo-price__form input {
  padding: 8px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  color: #263445;
  background: #fff;
}
.photo-price__form input:focus-visible {
  outline: 2px solid #bae6fd;
}
</style>
