<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { useTagsViewStore } from '@/store/modules/tagsView'
import { usePrintContextStore } from './contextStore'
import { printAdminGroups, printAdminMenu, printAdminModules } from './menu'
import type { PrintAdminMenuEntry } from './menu'
import { PRINT_HOME } from './navigation'

defineOptions({ name: 'PrintAdminShell' })
const router = useRouter()
const userStore = useUserStore()
const tagsViewStore = useTagsViewStore()
const contextStore = usePrintContextStore()
const activeModule = ref<string>('工作台')
const workbenchOpen = ref(true)
const logoutLoading = ref(false)
const logoutError = ref('')
const visibleGroups = computed(() => printAdminGroups
  .filter((group) => group.module === activeModule.value)
  .map((group) => ({ ...group, entries: printAdminMenu.filter((entry) =>
    entry.module === group.module && entry.group === group.title) })))
const verifiedName = computed(() => contextStore.status === 'ready'
  ? (userStore.getUser.nickname || `员工 ${contextStore.context?.userId}`) : '')

watch(() => userStore.getUser.id, (userId) => {
  if (!Number.isSafeInteger(userId) || userId <= 0) {
    contextStore.clear()
    return
  }
  void contextStore.load(userId)
}, { immediate: true })
onBeforeUnmount(() => contextStore.clear())

const openEntry = (entry: PrintAdminMenuEntry) => {
  if (!entry.route) return
  workbenchOpen.value = true
  activeModule.value = entry.module
  void router.push(entry.route)
}
const openWorkbench = () => {
  workbenchOpen.value = true
  activeModule.value = '工作台'
  void router.push(PRINT_HOME)
}
const selectModule = (module: string) => {
  activeModule.value = module
  if (module === '工作台') openWorkbench()
}
const logout = async () => {
  if (logoutLoading.value) return
  logoutLoading.value = true
  logoutError.value = ''
  try {
    await userStore.loginOut()
    contextStore.clear()
    tagsViewStore.delAllViews()
    await router.replace({ path: '/login', query: { redirect: PRINT_HOME } })
  } catch {
    logoutError.value = '退出失败，请重试。'
  } finally {
    logoutLoading.value = false
  }
}
</script>

<template>
  <div class="print-admin" data-testid="print-admin-shell">
    <header class="print-admin__header">
      <div class="print-admin__brand">打印后台</div>
      <nav class="print-admin__modules" aria-label="一级模块">
        <button v-for="module in printAdminModules" :key="module" type="button"
          :class="{ 'is-current': activeModule === module }" :aria-pressed="activeModule === module"
          @click="selectModule(module)">{{ module }}</button>
      </nav>
      <div class="print-admin__identity">
        <span v-if="verifiedName" class="print-admin__account" :title="verifiedName">{{ verifiedName }}</span>
        <span v-else class="print-admin__account">{{ contextStore.status === 'loading' ? '正在确认身份' : '身份尚未确认' }}</span>
        <button type="button" :disabled="logoutLoading" @click="logout">{{ logoutLoading ? '正在退出' : '退出' }}</button>
        <span v-if="logoutError" class="print-admin__logout-error" role="alert">{{ logoutError }}</span>
      </div>
    </header>
    <div class="print-admin__body">
      <aside class="print-admin__sidebar" aria-label="业务菜单">
        <section v-for="group in visibleGroups" :key="group.title">
          <p class="print-admin__sidebar-label">{{ group.title }}</p>
          <nav>
            <button v-for="entry in group.entries" :key="entry.sourceId" type="button"
              :disabled="!entry.route" :class="{ 'is-current': entry.sourceId === 'P01' && workbenchOpen }"
              :title="entry.route ? entry.title : `${entry.title}（暂未开放）`"
              :data-source-id="entry.sourceId" :data-available="Boolean(entry.route)"
              @click="openEntry(entry)"><span>{{ entry.title }}</span></button>
          </nav>
        </section>
      </aside>
      <div class="print-admin__gutter" aria-hidden="true"></div>
      <main class="print-admin__workspace">
        <div class="print-admin__tabs" aria-label="工作标签">
          <div v-if="workbenchOpen" class="print-admin__tab">
            <button type="button" class="print-admin__tab-label" @click="openWorkbench">工作台</button>
            <button type="button" class="print-admin__tab-close" aria-label="关闭工作台标签"
              @click="workbenchOpen = false">×</button>
          </div>
        </div>
        <div class="print-admin__content">
          <RouterView v-if="workbenchOpen" />
          <section v-else class="print-admin__closed">
            <p>当前标签已关闭</p>
            <button type="button" @click="openWorkbench">打开工作台</button>
          </section>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.print-admin {
  --el-color-primary: #0284c7;
  --el-bg-color: #fff;
  --el-text-color-primary: #263445;
  --el-text-color-regular: #475569;
  --el-fill-color-light: #f4f7fb;
  --el-border-color: #dce3eb;
  width: 100%;
  height: 100dvh;
  min-width: 1280px;
  overflow: auto;
  color: #263445;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', Arial, sans-serif;
  font-size: 13px;
  background: #f4f7fb;
}
.print-admin *, .print-admin *::before, .print-admin *::after { box-sizing: border-box; }
.print-admin button { font: inherit; cursor: pointer; }
.print-admin button:focus-visible { outline: 2px solid #38bdf8; outline-offset: -2px; }
.print-admin__header { display: flex; height: 60px; background: #0284c7; color: #fff; }
.print-admin__brand { display: flex; flex: 0 0 168px; align-items: center; padding-left: 20px; font-size: 21px; font-weight: 700; }
.print-admin__modules { display: flex; flex: 1; min-width: 0; }
.print-admin__modules button { flex-shrink: 0; min-width: 74px; height: 60px; padding: 0 16px; border: 0; color: #fff; background: transparent; font-size: 15px; line-height: 60px; white-space: nowrap; }
.print-admin__modules button.is-current { background: #0369a1; }
.print-admin__identity { position: relative; display: flex; align-items: center; gap: 14px; padding: 0 20px; font-size: 12px; white-space: nowrap; }
.print-admin__account { max-width: 180px; overflow: hidden; text-overflow: ellipsis; }
.print-admin__identity button { border: 0; padding: 6px 0; color: #fff; background: transparent; }
.print-admin__identity button:disabled { cursor: wait; }
.print-admin__logout-error { position: absolute; top: 48px; right: 16px; z-index: 2; padding: 8px 12px; border: 1px solid #fca5a5; border-radius: 4px; color: #991b1b; background: #fff; }
.print-admin__body { display: grid; grid-template-columns: 168px 5px minmax(0, 1fr); height: calc(100% - 60px); min-height: 660px; }
.print-admin__sidebar { grid-column: 1; overflow-y: auto; color: #d5dfec; background: #182235; }
.print-admin__sidebar-label { margin: 20px 16px 10px; color: #9caec7; font-size: 12px; }
.print-admin__sidebar nav { padding: 0 8px; }
.print-admin__sidebar nav button { display: flex; align-items: center; width: 100%; height: 32px; padding: 0 8px; border: 0; border-radius: 6px; color: #d5dfec; background: transparent; font-size: 13px; line-height: 32px; text-align: left; }
.print-admin__sidebar nav button span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.print-admin__sidebar nav button.is-current { color: #fff; background: #0284c7; box-shadow: 0 2px 6px #0b172433; }
.print-admin__sidebar nav button:disabled { cursor: not-allowed; }
.print-admin__gutter { grid-column: 2; background: #e3eaf1; }
.print-admin__workspace { grid-column: 3; display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #f4f7fb; }
.print-admin__tabs { display: flex; flex-shrink: 0; align-items: flex-start; height: 43px; border-bottom: 1px solid #e2e8f0; background: #fff; }
.print-admin__tab { display: flex; align-items: center; height: 42px; border-bottom: 3px solid #0284c7; color: #0284c7; background: #f0f9ff; font-weight: 700; }
.print-admin__tab button { height: 100%; border: 0; color: inherit; background: transparent; }
.print-admin__tab-label { padding: 0 16px; font-weight: 700 !important; }
.print-admin__tab-close { width: 24px; margin-right: 7px; padding: 0; font-size: 17px !important; }
.print-admin__content { flex: 1; min-height: 0; padding: 23px 16px 24px; overflow: auto; }
.print-admin__closed { padding: 24px; color: #64748b; background: #fff; }
.print-admin__closed button { padding: 7px 12px; border: 1px solid #dce3eb; border-radius: 3px; color: #0284c7; background: #fff; }
</style>
