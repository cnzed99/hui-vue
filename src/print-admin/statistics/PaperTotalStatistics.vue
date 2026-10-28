<script setup lang="ts">
import PaperStatistics from './PaperStatistics.vue'
defineOptions({ name: 'PrintPaperTotalStatistics' })
</script>
<template><PaperStatistics :daily="false" /></template>
