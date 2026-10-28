<script setup lang="ts">
import PaperStatistics from './PaperStatistics.vue'
defineOptions({ name: 'PrintPaperDailyStatistics' })
</script>
<template><PaperStatistics :daily="true" /></template>
