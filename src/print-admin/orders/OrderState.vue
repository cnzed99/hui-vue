<script setup lang="ts">
import type { OrderFailure } from './api'
defineProps<{ failure?: OrderFailure | null; loading?: boolean }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="failure" class="orders-state" role="alert" :data-error-kind="failure.kind">
    <h2>{{ failure.title }}</h2><p>{{ failure.message }}</p>
    <button v-if="!['forbidden', 'not-found', 'invalid-input', 'session'].includes(failure.kind)" type="button" @click="$emit('retry')">重新加载</button>
  </div>
  <div v-else-if="loading" class="orders-state" role="status">正在加载…</div>
</template>
