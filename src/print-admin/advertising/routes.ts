import type { RouteRecordRaw } from 'vue-router'
import { adPositions } from './positions'
export const printAdvertisingRouteNames = adPositions.map((p) => `PrintAdvertising${p.sourceId}`)
export const printAdvertisingRoutes: RouteRecordRaw[] = adPositions.map((p) => ({
  path: `advertising/${p.path}`, name: `PrintAdvertising${p.sourceId}`,
  component: () => import('./AdvertisingPage.vue'), props: { position: p.key },
  meta: { hidden: true, noTagsView: true, title: p.title }
}))
