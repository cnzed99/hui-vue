<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from './contextStore'

defineOptions({ name: 'PrintWorkbench' })
const userStore = useUserStore()
const contextStore = usePrintContextStore()
const accountName = computed(() => userStore.getUser.nickname || `员工 ${contextStore.context?.userId}`)
const reload = () => { void contextStore.load(userStore.getUser.id) }
</script>

<template>
  <section class="workbench" data-testid="print-workbench" :aria-busy="contextStore.status === 'loading'">
    <header class="workbench__title-card"><h1>工作台</h1></header>
    <div v-if="contextStore.status === 'idle' || contextStore.status === 'loading'"
      class="workbench__card workbench__state" role="status" data-testid="print-context-loading">
      <p>正在加载门店信息…</p>
    </div>
    <div v-else-if="contextStore.failure" class="workbench__card workbench__state" role="alert"
      :data-error-kind="contextStore.failure.kind" data-testid="print-context-error">
      <h2>{{ contextStore.failure.title }}</h2>
      <p>{{ contextStore.failure.message }}</p>
      <button type="button" @click="reload">重新加载</button>
    </div>
    <div v-else-if="contextStore.context" class="workbench__card" data-testid="print-context-ready">
      <div class="workbench__card-heading">
        <h2>当前门店</h2>
        <button type="button" class="workbench__refresh" @click="reload">刷新</button>
      </div>
      <dl class="workbench__details">
        <div><dt>门店名称</dt><dd data-testid="print-store-name">{{ contextStore.context.storeName }}</dd></div>
        <div><dt>当前账号</dt><dd data-testid="print-account-name">{{ accountName }}</dd></div>
        <div><dt>登录身份</dt><dd>后台员工</dd></div>
      </dl>
      <p class="workbench__hint">请选择上方业务模块查看对应菜单。</p>
    </div>
  </section>
</template>

<style scoped>
.workbench { color: #263445; }
.workbench__title-card { display: flex; align-items: center; height: 71px; padding: 0 19px; background: #fff; }
.workbench__title-card h1 { position: relative; margin: 0; font-size: 16px; line-height: 24px; font-weight: 600; }
.workbench__title-card h1::after { position: absolute; right: 0; bottom: -13px; left: 0; height: 3px; content: ''; background: #0284c7; }
.workbench__card { margin-top: 12px; padding: 24px 28px; background: #fff; }
.workbench__card-heading { display: flex; align-items: center; justify-content: space-between; }
.workbench h2 { margin: 0; font-size: 15px; font-weight: 600; }
.workbench__details { margin: 24px 0; }
.workbench__details > div { display: flex; min-height: 36px; align-items: baseline; }
.workbench__details dt { flex: 0 0 90px; color: #64748b; }
.workbench__details dd { margin: 0; overflow-wrap: anywhere; }
.workbench__hint { margin: 24px 0 0; color: #64748b; font-size: 12px; }
.workbench__state p { margin: 12px 0; color: #64748b; }
.workbench button { padding: 7px 12px; border: 1px solid #dce3eb; border-radius: 3px; font: inherit; color: #0284c7; background: #fff; cursor: pointer; }
.workbench button:focus-visible { outline: 2px solid #38bdf8; outline-offset: 2px; }
.workbench__refresh { padding: 4px 8px !important; }
</style>
