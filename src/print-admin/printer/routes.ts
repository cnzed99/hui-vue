import type { RouteRecordRaw } from 'vue-router'
export const printPrinterRoutes: RouteRecordRaw[] = [
  { path: 'printers', name: 'PrintPrinterDirectory', component: () => import('./PrinterDirectory.vue'), meta: { hidden: true, noTagsView: true, title: '打印机' } }
]
export const printPrinterRouteNames = ['PrintPrinterDirectory']
