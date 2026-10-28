import type { RouteRecordRaw } from 'vue-router'
export const printDirectoryRoutes: RouteRecordRaw[] = [
  { path: 'products/mine', name: 'PrintMyProducts', component: () => import('./MyProducts.vue'), meta: { hidden: true, noTagsView: true, title: '我的商品' } },
  { path: 'products/platform', name: 'PrintPlatformProducts', component: () => import('./PlatformProducts.vue'), meta: { hidden: true, noTagsView: true, title: '平台商品库' } },
  { path: 'library/entries', name: 'PrintLibraryEntries', component: () => import('./LibraryEntries.vue'), meta: { hidden: true, noTagsView: true, title: '文库列表' } },
  { path: 'terminals', name: 'PrintTerminalDirectory', component: () => import('./Terminals.vue'), meta: { hidden: true, noTagsView: true, title: '打印终端' } }
]
export const printDirectoryRouteNames = ['PrintMyProducts','PrintPlatformProducts','PrintLibraryEntries','PrintTerminalDirectory']
