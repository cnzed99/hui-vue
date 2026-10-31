export interface MiniappCatalogStatus {
  catalogVersion: string
  methods: unknown[]
  uploadEnabled: boolean
  demoLimits: { maxFileBytes: number; maxPages: number; retentionMinutes: number }
}

function parseCatalog(value: unknown): MiniappCatalogStatus {
  const envelope = value as { code?: number; data?: Partial<MiniappCatalogStatus> }
  const data = envelope?.data
  if (envelope?.code !== 0 || !data || typeof data.catalogVersion !== 'string'
    || !Array.isArray(data.methods) || typeof data.uploadEnabled !== 'boolean'
    || !data.demoLimits || typeof data.demoLimits.maxFileBytes !== 'number'
    || typeof data.demoLimits.maxPages !== 'number' || typeof data.demoLimits.retentionMinutes !== 'number') {
    throw new Error('invalid_miniapp_catalog')
  }
  return data as MiniappCatalogStatus
}

/** Public capability probe: intentionally sends no login, tenant or store credentials. */
export async function getMiniappCatalogStatus(): Promise<MiniappCatalogStatus> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch('/app-api/print/miniapp/catalog', {
      method: 'GET',
      credentials: 'omit',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: controller.signal
    })
    if (!response.ok) throw new Error('miniapp_catalog_unavailable')
    return parseCatalog(await response.json())
  } finally {
    window.clearTimeout(timeout)
  }
}
