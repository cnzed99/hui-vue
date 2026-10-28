import { printPrinterRoutes, printPrinterRouteNames } from './printer/routes'
import { printDirectoryRoutes, printDirectoryRouteNames } from './directory/routes'
import { printAdvertisingRoutes, printAdvertisingRouteNames } from './advertising/routes'
import { printStatisticsRoutes, printStatisticsRouteNames } from './statistics/routes'
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
      { path: 'admin-print-list', name: 'PrintAdminPrintList', component: () => import('./orders/AdminPrintList.vue'), meta: { hidden: true, noTagsView: true, title: '管理员打印列表（当前账号演示）' } },
      ...printPrinterRoutes,
      ...printDirectoryRoutes,
      ...printAdvertisingRoutes,
      ...printStatisticsRoutes,
      {
        path: 'workbench',
        name: 'PrintWorkbench',
        component: () => import('./PrintWorkbench.vue'),
        meta: { hidden: true, noTagsView: true, title: '工作台' }
      },
      {
        path: 'store/profile',
        name: 'PrintStoreProfile',
        component: () => import('./store/StoreProfile.vue'),
        meta: { hidden: true, noTagsView: true, title: '门店详情' }
      },
      {
        path: 'store/password',
        name: 'PrintPasswordManagement',
        component: () => import('./store/PasswordManagement.vue'),
        meta: { hidden: true, noTagsView: true, title: '密码管理' }
      },
      {
        path: 'library/categories',
        name: 'PrintLibraryCategories',
        component: () => import('./library/LibraryCategories.vue'),
        meta: { hidden: true, noTagsView: true, title: '自定义分类' }
      },
      {
        path: 'library/settings',
        name: 'PrintLibrarySettings',
        component: () => import('./library/LibrarySettings.vue'),
        meta: { hidden: true, noTagsView: true, title: '文库设置' }
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

export const printAdminRouteNames = ['PrintAdminPrintList',...printPrinterRouteNames,...printDirectoryRouteNames,...printAdvertisingRouteNames, 'PrintLibraryCategories', ...printStatisticsRouteNames, 'PrintLibrarySettings', 'PrintStoreProfile', 'PrintAdmin', 'PrintWorkbench', 'PrintPasswordManagement', 'PrintDemoWorkflow', 'PrintDemoOrders', 'PrintDemoOrderDetail']
