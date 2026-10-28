import type { RouteRecordRaw } from 'vue-router'
export const printStatisticsRoutes: RouteRecordRaw[] = [
  { path: 'statistics/terminal', name: 'PrintTerminalStatistics', component: () => import('./TerminalStatistics.vue'), meta: { hidden: true, noTagsView: true, title: '按终端统计' } },
  { path: 'statistics/paper-total', name: 'PrintPaperTotalStatistics', component: () => import('./PaperTotalStatistics.vue'), meta: { hidden: true, noTagsView: true, title: '按总量统计' } },
  { path: 'statistics/paper-daily', name: 'PrintPaperDailyStatistics', component: () => import('./PaperDailyStatistics.vue'), meta: { hidden: true, noTagsView: true, title: '按日统计' } }
]
export const printStatisticsRouteNames = ['PrintTerminalStatistics', 'PrintPaperTotalStatistics', 'PrintPaperDailyStatistics']
