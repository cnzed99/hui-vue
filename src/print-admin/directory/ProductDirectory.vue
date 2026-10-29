<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { categoryOptions, display, priceText, productCategories } from './api'
import type { Product } from './api'
import { useDirectory } from './useDirectory'
const props = defineProps<{ platform?: boolean }>()
const name = ref(''); const category = ref(''); const selected = ref<Product[]>([]); const table = ref()
const { permitted, ready, rows, total, categories, page, pageSize, busy, loaded, message, search, load, changePage } = useDirectory<Product>(props.platform ? 'platform-products' : 'my-products', props.platform ? 'print:platform-product:query' : 'print:product:query', () => ({ name: name.value, category: category.value }))
const options = computed(() => categoryOptions([...productCategories, ...categories.value]))
watch(rows, () => { selected.value = [] })
const all = () => { rows.value.forEach(row => table.value?.toggleRowSelection(row, true)) }
const inverse = () => { rows.value.forEach(row => table.value?.toggleRowSelection(row, !selected.value.includes(row))) }
</script>
<template>
  <section class="directory-page"><h2>{{ platform ? '平台商品库' : '我的商品' }}</h2>
    <p>当前账号的{{ platform ? '平台目录可见快照' : '门店商品目录' }}；价格仅供查看，调价及上下架尚未开放。</p>
    <p v-if="!permitted" role="alert">当前账号没有查看商品的权限。</p><p v-else-if="!ready" role="status">请先确认当前门店身份。</p>
    <template v-else>
      <el-form class="directory-filter" inline @submit.prevent="search"><el-form-item label="商品名称："><el-input v-model="name" maxlength="100" clearable :disabled="busy" /></el-form-item><el-form-item label="商品分类："><el-select v-model="category" clearable placeholder="请选择商品分类" :disabled="busy" style="width:240px"><el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-button native-type="submit" type="primary" :disabled="busy">查询</el-button></el-form>
      <p v-if="message" role="alert">{{ message }} <el-button @click="load" :disabled="busy">重新加载</el-button></p><p v-if="busy" role="status">正在加载商品…</p>
      <div class="directory-tools"><div class="directory-tools__actions"><el-button type="primary" :disabled="!loaded || busy" @click="all"><span class="tool-icon" aria-hidden="true">✓</span>全选</el-button><el-button type="primary" :disabled="!loaded || busy" @click="inverse"><span class="tool-icon" aria-hidden="true">⇄</span>反选</el-button><el-button :type="platform ? 'primary' : 'danger'" disabled title="尚未开放"><span class="tool-icon" aria-hidden="true">×</span>{{ platform ? '批量上架' : '批量下架' }}</el-button></div><div class="directory-tools__page-size"><span>每页显示数量：</span><el-select v-model="pageSize" style="width:90px" :disabled="busy" @change="search"><el-option v-for="size in [10,20,40,100]" :key="size" :value="size" :label="String(size)" /></el-select></div></div>
      <el-table v-if="loaded" class="product-table" ref="table" :data="rows" border row-key="id" empty-text="当前筛选下暂无商品" @selection-change="selected = $event"><el-table-column type="selection" width="55" /><el-table-column label="商品" min-width="220"><template #default="{row}"><strong>{{ row.name }}</strong><div>商品编码：{{ row.code }}</div><small>{{ display(row.categoryPath) }}</small></template></el-table-column><el-table-column label="商品价格" min-width="200"><template #default="{row}">{{ row.priceDescription || priceText(row.unitPrice, row.currency) }}</template></el-table-column><el-table-column label="操作" min-width="300"><template #default><template v-if="!platform"><el-button type="primary" plain disabled>调整售价</el-button><el-button type="primary" plain disabled>其它纸质售价</el-button></template><el-button type="primary" plain disabled>{{ platform ? '上架' : '下架' }}</el-button></template></el-table-column></el-table>
      <el-pagination v-if="loaded" v-model:current-page="page" :page-size="pageSize" :total="total" layout="total, prev, pager, next, jumper" :disabled="busy" @current-change="changePage" />
    </template>
  </section>
</template>
<style scoped>
.directory-page { padding: 20px; background: #fff; }
.directory-filter { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 12px; border: 1px solid #edf0f3; border-radius: 8px; background: #f7f9fb; }
.directory-filter :deep(.el-form-item) { margin: 0; }
.directory-tools { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin: 12px 0; padding: 12px; border: 1px solid #edf0f3; border-radius: 8px; }
.directory-tools__actions, .directory-tools__page-size { display: flex; align-items: center; gap: 6px; }
.directory-tools__actions :deep(.el-button + .el-button) { margin-left: 0; }
.directory-tools__page-size { margin-left: auto; color: #64748b; font-size: 12px; }
.tool-icon { margin-right: 4px; font-size: 16px; font-weight: 700; }
.product-table :deep(.el-table__cell) { padding: 7px 0; font-size: 13px; }
.product-table :deep(.el-table__header-wrapper th) { background: #f7f9fb; color: #475569; }
.el-pagination { margin-top: 20px; }
</style>
