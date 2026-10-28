<script setup lang="ts">
import { ref, watch } from 'vue'
import { categoryNameError } from './categoryModel'
const props = defineProps<{ initialName: string; editing: boolean; busy: boolean; locked: boolean }>()
const emit = defineEmits<{ save: [name: string, continueAdding: boolean]; cancel: [] }>()
const name = ref('')
const error = ref('')
watch(() => props.initialName, value => { name.value = value; error.value = '' }, { immediate: true })
const save = (continueAdding: boolean) => {
  if (props.busy || props.locked) return
  error.value = categoryNameError(name.value)
  if (!error.value) emit('save', name.value, continueAdding)
}
</script>
<template>
  <el-form label-width="130px" class="category-form" @submit.prevent="save(false)">
    <el-form-item label="分类名称：" required :error="error">
      <el-input v-model="name" :disabled="busy || locked" placeholder="请输入分类名称" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :disabled="busy || locked" @click="save(false)">确 定</el-button>
      <el-button v-if="!editing" :disabled="busy || locked" @click="save(true)">保存并继续添加</el-button>
      <el-button :disabled="busy || locked" @click="emit('cancel')">取消</el-button>
    </el-form-item>
  </el-form>
</template>
<style scoped>.category-form { margin-top: 24px; max-width: 650px; }</style>
