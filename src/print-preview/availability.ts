/** Exact, opt-in static UI route. This grants no API or business authorization. */
export const isPrintPreviewEnabled = (mode: string, flag: string | undefined): boolean =>
  mode === 'cloud' && flag === 'true'

export const matchesPrintPreviewRoute = (route: { path: string; name?: unknown }): boolean =>
  route.path === '/print-preview/touch-ad' && route.name === 'PrintPreviewTouchAd'
