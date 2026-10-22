import type { RouteLocationNormalized, RouteRecordRaw } from 'vue-router'
import { isPrintPreviewEnabled, matchesPrintPreviewRoute } from './availability'

const enabled = isPrintPreviewEnabled(
  import.meta.env.MODE,
  import.meta.env.VITE_PRINT_VISUAL_PREVIEW
)

// Default routes/layout are unchanged. No wildcard, source-page stubs or API mocks.
export const printPreviewRoutes: RouteRecordRaw[] = enabled
  ? [
      {
        path: '/print-preview',
        name: 'PrintPreview',
        component: () => import('./PrintPreviewShell.vue'),
        redirect: '/print-preview/touch-ad',
        meta: { hidden: true, noTagsView: true, title: '打印后台 · 视觉样板' },
        children: [
          {
            path: 'touch-ad',
            name: 'PrintPreviewTouchAd',
            component: () => import('./TouchAdSample.vue'),
            meta: { hidden: true, noTagsView: true, title: '触屏终端广告 · 未接接口' }
          }
        ]
      }
    ]
  : []

export const printPreviewRouteNames = enabled ? ['PrintPreview', 'PrintPreviewTouchAd'] : []
export const isPrintVisualPreviewRoute = (to: RouteLocationNormalized): boolean =>
  enabled && matchesPrintPreviewRoute(to)
