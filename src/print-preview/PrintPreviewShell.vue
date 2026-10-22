<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { printMenuEntries, printMenuGroups, printMenuModules } from './menu'

defineOptions({ name: 'PrintPreviewShell' })
const router = useRouter()
const activeModule = ref('广告')
const sampleTabOpen = ref(true)
const visibleGroups = computed(() => printMenuGroups
  .filter((group) => group.module === activeModule.value)
  .map((group) => ({
    ...group,
    entries: printMenuEntries.filter((entry) =>
      entry.module === group.module && entry.group === group.title)
  })))
const openSample = () => {
  activeModule.value = '广告'
  sampleTabOpen.value = true
  void router.push('/print-preview/touch-ad')
}
</script>

<template>
  <div class="print-preview" data-testid="print-preview-shell">
    <header class="print-preview__header">
      <div class="print-preview__brand" aria-label="文本Logo占位，待原素材">打印后台</div>
      <nav class="print-preview__modules" aria-label="一级模块">
        <button
          v-for="module in printMenuModules"
          :key="module"
          type="button"
          :class="{ 'is-current': activeModule === module }"
          :aria-pressed="activeModule === module"
          @click="activeModule = module"
        >{{ module }}</button>
      </nav>
      <div class="print-preview__identity" role="status">开发预览·接口未接入</div>
    </header>
    <div class="print-preview__body">
      <aside class="print-preview__sidebar" aria-label="源页面菜单">
        <section v-for="group in visibleGroups" :key="group.title">
          <p class="print-preview__sidebar-label">{{ group.title }}</p>
          <nav>
            <button
              v-for="entry in group.entries"
              :key="entry.sourceId"
              type="button"
              :disabled="entry.status === 'not-implemented'"
              :class="{ 'is-current': entry.status === 'visual-sample' && sampleTabOpen }"
              :title="`${entry.sourceId} / ${entry.taskId} · ${entry.status === 'visual-sample' ? '仅视觉样板，接口未接入' : '未实现'}`"
              :data-source-id="entry.sourceId"
              :data-implementation-status="entry.status"
              @click="openSample"
            >
              <span>{{ entry.title }}</span>
              <small v-if="entry.status === 'not-implemented'">未实现</small>
            </button>
          </nav>
        </section>
      </aside>
      <div class="print-preview__gutter" aria-hidden="true"></div>
      <main class="print-preview__workspace">
        <div class="print-preview__tabs" aria-label="工作标签">
          <div v-if="sampleTabOpen" class="print-preview__tab">
            <button type="button" class="print-preview__tab-label" @click="openSample">触屏终端广告</button>
            <button
              type="button"
              class="print-preview__tab-close"
              aria-label="关闭触屏终端广告标签"
              @click="sampleTabOpen = false"
            >×</button>
          </div>
        </div>
        <div class="print-preview__content">
          <RouterView v-if="sampleTabOpen" />
          <div v-else class="print-preview__closed">
            <p>当前标签已关闭</p>
            <button type="button" @click="openSample">重新打开触屏终端广告</button>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.print-preview {
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
.print-preview *, .print-preview *::before, .print-preview *::after { box-sizing: border-box; }
.print-preview button { font: inherit; cursor: pointer; }
.print-preview button:focus-visible { outline: 2px solid #38bdf8; outline-offset: -2px; }
.print-preview__header { display: flex; height: 60px; background: #0284c7; color: #fff; }
.print-preview__brand { display: flex; flex: 0 0 168px; align-items: center; padding-left: 20px; font-size: 21px; font-weight: 700; }
.print-preview__modules { display: flex; flex: 1; min-width: 0; }
.print-preview__modules button { flex-shrink: 0; min-width: 74px; height: 60px; padding: 0 16px; border: 0; color: #fff; background: transparent; font-size: 15px; line-height: 60px; white-space: nowrap; }
.print-preview__modules button.is-current { background: #0369a1; }
.print-preview__identity { display: flex; align-items: center; padding: 0 20px; font-size: 12px; white-space: nowrap; }
.print-preview__body { display: grid; grid-template-columns: 168px 5px minmax(0, 1fr); height: calc(100% - 60px); min-height: 660px; }
.print-preview__sidebar { grid-column: 1; overflow-y: auto; color: #d5dfec; background: #182235; }
.print-preview__sidebar-label { margin: 20px 16px 10px; color: #9caec7; font-size: 12px; }
.print-preview__sidebar nav { padding: 0 8px; }
.print-preview__sidebar nav button { display: flex; align-items: center; justify-content: space-between; width: 100%; height: 32px; padding: 0 8px; border: 0; border-radius: 6px; color: #d5dfec; background: transparent; font-size: 13px; line-height: 32px; text-align: left; }
.print-preview__sidebar nav button span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.print-preview__sidebar nav button small { flex-shrink: 0; margin-left: 2px; color: #a4b3c8; font-size: 9px; }
.print-preview__sidebar nav button.is-current { color: #fff; background: #0284c7; }
.print-preview__sidebar nav button:disabled { cursor: not-allowed; }
.print-preview__gutter { grid-column: 2; background: #e3eaf1; }
.print-preview__workspace { grid-column: 3; display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #f4f7fb; }
.print-preview__tabs { display: flex; flex-shrink: 0; align-items: flex-start; height: 43px; border-bottom: 1px solid #e2e8f0; background: #fff; }
.print-preview__tab { display: flex; align-items: center; height: 42px; border-bottom: 3px solid #0284c7; color: #0284c7; background: #f0f9ff; font-weight: 700; }
.print-preview__tab button { height: 100%; border: 0; color: inherit; background: transparent; }
.print-preview__tab-label { padding: 0 16px; font-weight: 700 !important; }
.print-preview__tab-close { width: 24px; margin-right: 7px; padding: 0; font-size: 17px !important; }
.print-preview__content { flex: 1; min-height: 0; padding: 23px 16px 24px; overflow: auto; }
.print-preview__closed { padding: 24px; color: #64748b; background: #fff; }
.print-preview__closed button { padding: 7px 12px; border: 1px solid #dce3eb; border-radius: 3px; color: #0284c7; background: #fff; }
</style>
