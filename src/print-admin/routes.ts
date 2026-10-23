import type { RouteRecordRaw } from 'vue-router'
import { PRINT_HOME } from './navigation'

/** These routes always use the normal login guard. They have no preview/auth bypass. */
export const printAdminRoutes: RouteRecordRaw[] = [
  {
    path: '/print',
    name: 'PrintAdmin',
    component: () => import('./PrintAdminShell.vue'),
    redirect: PRINT_HOME,
    meta: { hidden: true, noTagsView: true, title: '打印后台' },
    children: [
      {
        path: 'workbench',
        name: 'PrintWorkbench',
        component: () => import('./PrintWorkbench.vue'),
        meta: { hidden: true, noTagsView: true, title: '工作台' }
      }
    ]
  }
]

export const printAdminRouteNames = ['PrintAdmin', 'PrintWorkbench']
