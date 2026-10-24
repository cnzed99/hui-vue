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
      },
      {
        path: 'demo-workflow',
        name: 'PrintDemoWorkflow',
        component: () => import('./workflow/DemoWorkflow.vue'),
        meta: { hidden: true, noTagsView: true, title: '后台演示流程' }
      },
      {
        path: 'demo-orders',
        name: 'PrintDemoOrders',
        component: () => import('./orders/DemoOrders.vue'),
        meta: { hidden: true, noTagsView: true, title: '演示订单' }
      },
      {
        path: 'demo-orders/:id',
        name: 'PrintDemoOrderDetail',
        component: () => import('./orders/DemoOrderDetail.vue'),
        meta: { hidden: true, noTagsView: true, title: '演示订单详情' }
      }
    ]
  }
]

export const printAdminRouteNames = ['PrintAdmin', 'PrintWorkbench', 'PrintDemoWorkflow', 'PrintDemoOrders', 'PrintDemoOrderDetail']
