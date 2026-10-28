<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import CategoryForm from './CategoryForm.vue'
import { categoryFailure, createCategory, getCategories, updateCategory } from './categoryApi'
import type { LibraryCategory } from './categoryModel'
defineOptions({ name: 'PrintLibraryCategories' })
const user = useUserStore()
const context = usePrintContextStore()
const permitted = (code: string) => user.permissions.has('*:*:*') || user.permissions.has(code)
const canQuery = computed(() => permitted('print:library-category:query'))
const canCreate = computed(() => permitted('print:library-category:create'))
const canUpdate = computed(() => permitted('print:library-category:update'))
const rows = ref<LibraryCategory[]>([])
const total = ref(0)
const page = ref(1)
const loaded = ref(false)
const busy = ref(false)
const message = ref('')
const formOpen = ref(false)
const editing = ref<LibraryCategory | null>(null)
const formKey = ref(0)
const requestId = ref('')
const needsReload = ref(false)
let generation = 0
const identity = computed(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}`)
const clearForm = () => { formOpen.value = false; editing.value = null; requestId.value = ''; formKey.value++; needsReload.value = false }
const load = async () => {
  if (busy.value || !canQuery.value || context.status !== 'ready') return
  const ticket = ++generation
  busy.value = true; loaded.value = false; rows.value = []; message.value = ''; clearForm()
  try {
    const data = await getCategories(page.value)
    if (ticket !== generation) return
    rows.value = data.list; total.value = data.total; loaded.value = true
  } catch (error) { if (ticket === generation) message.value = categoryFailure(error) === 'forbidden' ? '没有查看分类的权限。' : '分类加载失败，请重新加载。' }
  finally { if (ticket === generation) busy.value = false }
}
watch([identity, canQuery, canCreate, canUpdate], () => {
  generation++; rows.value = []; total.value = 0; loaded.value = false; busy.value = false; message.value = ''; page.value = 1; clearForm()
  if (canQuery.value && context.status === 'ready') void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
const open = (row: LibraryCategory | null) => {
  if (busy.value || needsReload.value || !loaded.value || !(row ? canUpdate.value : canCreate.value)) return
  if (!row && !globalThis.crypto?.randomUUID) { message.value = '当前浏览器无法安全提交，请使用受支持的安全连接。'; return }
  editing.value = row; requestId.value = row ? '' : globalThis.crypto.randomUUID(); formKey.value++; formOpen.value = true; message.value = ''
}
const save = async (name: string, continueAdding: boolean) => {
  if (busy.value || needsReload.value || !formOpen.value || context.status !== 'ready' || !(editing.value ? canUpdate.value : canCreate.value)) return
  const ticket = generation
  busy.value = true; message.value = ''
  try {
    const saved = editing.value ? await updateCategory(editing.value.id, { name, expectedVersion: editing.value.version }) : await createCategory({ name, requestId: requestId.value })
    if (ticket !== generation) return
    const data = await getCategories(page.value)
    if (ticket !== generation) return
    rows.value = data.list; total.value = data.total; loaded.value = true
    if (continueAdding && !editing.value) { requestId.value = globalThis.crypto.randomUUID(); formKey.value++ }
    else clearForm()
    message.value = `分类“${saved.name}”已保存。`
  } catch (error) {
    if (ticket !== generation) return
    const kind = categoryFailure(error)
    if (kind === 'invalid') message.value = '请检查分类名称必填及长度要求。'
    else { needsReload.value = true; message.value = kind === 'conflict' ? '分类已被修改，请重新加载并核对。' : kind === 'forbidden' ? '当前账号没有保存权限。' : '保存结果暂时无法确认，请重新加载核对后再操作。' }
  } finally { if (ticket === generation) busy.value = false }
}
const changePage = (value: number) => { if (!busy.value) { page.value = value; void load() } }
</script>
<template>
  <section class="library-categories" data-testid="print-library-categories">
    <h2>自定义分类</h2>
    <p v-if="!canQuery" role="alert">当前账号没有查看分类的权限。</p>
    <p v-else-if="context.status !== 'ready'" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <nav class="category-tabs" aria-label="分类管理">
        <el-button :disabled="busy || needsReload" @click="clearForm">管理</el-button>
        <el-button v-if="canCreate" :disabled="busy || !loaded || needsReload" @click="open(null)">添加</el-button>
      </nav>
      <p v-if="message" role="status">{{ message }}</p>
      <p v-if="busy" role="status">正在处理分类…</p>
      <el-button :disabled="busy" @click="load">重新加载</el-button>
      <CategoryForm v-if="formOpen" :key="formKey" :initial-name="editing?.name || ''" :editing="!!editing" :busy="busy" :locked="needsReload" @save="save" @cancel="clearForm" />
      <template v-else-if="loaded">
        <div class="category-tools">
          <el-button disabled title="分类层级规则待确认">全部展开</el-button>
          <el-button disabled title="分类层级规则待确认">全部收缩</el-button>
          <el-button disabled title="文件关联及数量更新规则待确认">更新文件数量</el-button>
        </div>
        <el-table :data="rows" border empty-text="暂无自定义分类">
          <el-table-column prop="name" label="分类名称" />
          <el-table-column label="文件数量" width="130"><template #default>待接入</template></el-table-column>
          <el-table-column label="排序" width="110"><template #default>待确认</template></el-table-column>
          <el-table-column label="操作" width="170"><template #default="{ row }">
            <el-button v-if="canUpdate" link type="primary" :disabled="busy || needsReload" @click="open(row)">编辑</el-button>
            <el-button link disabled title="删除及关联规则待确认">删除</el-button>
          </template></el-table-column>
        </el-table>
        <el-pagination :current-page="page" :page-size="20" :total="total" :disabled="busy" layout="prev, pager, next, total" @current-change="changePage" />
      </template>
    </template>
  </section>
</template>
<style scoped>
.library-categories { padding: 20px 24px; min-height: 100%; background: #fff; }
.library-categories h2 { margin: 0 0 18px; font-size: 18px; }
.library-categories p { color: #64748b; line-height: 1.7; }
.category-tabs { padding-bottom: 14px; border-bottom: 1px solid #e2e8f0; margin-bottom: 16px; }
.category-tools { display: flex; gap: 8px; margin: 18px 0; }
.el-pagination { margin-top: 18px; }
</style>
