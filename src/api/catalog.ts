export type CatalogSource = 'server' | 'local'

export interface MiniappPrintMethod {
  id: string
  label: string
  description: string
  group: 'COMMON' | 'OTHER'
  availability: 'LOCAL_ONLY' | 'RESERVED'
}

export interface MiniappCatalog {
  catalogVersion: string
  methods: MiniappPrintMethod[]
  supportedExtensions: string[]
  demoLimits: {
    maxFileBytes: number
    maxPages: number
    retentionMinutes: number
  }
  uploadEnabled: boolean
}

export interface CatalogLoadResult {
  catalog: MiniappCatalog
  source: CatalogSource
  serviceAvailable: boolean
}

export const localCatalog: MiniappCatalog = {
  catalogVersion: 'miniapp-m2-local-2026-10-31',
  methods: [
    { id: 'wechat-chat', label: '微信聊天文档打印', description: '支持 Word、Excel、PDF、PPT 打印', group: 'COMMON', availability: 'RESERVED' },
    { id: 'local-document', label: '本地文档打印', description: '从手机本地选择文档一键打印', group: 'OTHER', availability: 'LOCAL_ONLY' },
    { id: 'archive', label: '压缩包打印', description: '从微信聊天记录选择压缩包打印', group: 'OTHER', availability: 'RESERVED' },
    { id: 'tencent-doc', label: '腾讯文档打印', description: '导入腾讯文档后打印', group: 'OTHER', availability: 'RESERVED' },
    { id: 'kingsoft-doc', label: '金山文档打印', description: '导入金山文档后打印', group: 'OTHER', availability: 'RESERVED' },
    { id: 'baidu-drive', label: '百度网盘文档打印', description: '从百度网盘选择文档', group: 'OTHER', availability: 'RESERVED' },
    { id: 'other-app', label: '其它 APP 文档打印', description: '钉钉、WPS、邮箱等应用内文件打印', group: 'OTHER', availability: 'RESERVED' },
    { id: 'a3-to-a4', label: 'A3 文档转 A4', description: '转换版面后打印', group: 'OTHER', availability: 'RESERVED' },
    { id: 'pdf-unlock', label: 'PDF 解密打印', description: '解密能力待正式接入', group: 'OTHER', availability: 'RESERVED' },
    { id: 'buy-paper', label: '购买 A4 白纸', description: '商品与库存接口待接入', group: 'OTHER', availability: 'RESERVED' }
  ],
  supportedExtensions: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'jpg', 'jpeg', 'png'],
  demoLimits: { maxFileBytes: 2 * 1024 * 1024, maxPages: 20, retentionMinutes: 60 },
  uploadEnabled: false
}

function catalogUrl(): string | null {
  const configured = import.meta.env.VITE_APP_API_BASE?.replace(/\/$/, '')
  if (configured?.startsWith('https://')) return `${configured}/print/miniapp/catalog`
  // H5 is deployed behind the same Nginx origin during the current demo phase.
  // #ifdef H5
  return `${configured || '/app-api'}/print/miniapp/catalog`
  // #endif
  // The WeChat build must not call a relative URL. Until an HTTPS domain is approved it uses the explicit local fallback.
  return null
}

function isCatalog(value: unknown): value is MiniappCatalog {
  if (!value || typeof value !== 'object') return false
  const item = value as Partial<MiniappCatalog>
  const limits = item.demoLimits as Partial<MiniappCatalog['demoLimits']> | undefined
  const positiveInteger = (field: unknown) => Number.isSafeInteger(field) && Number(field) > 0
  return typeof item.catalogVersion === 'string'
    && item.catalogVersion.length > 0
    && Array.isArray(item.methods) && item.methods.length > 0 && item.methods.length <= 50
    && item.methods.every(method => !!method && typeof method === 'object'
      && typeof method.id === 'string' && method.id.length > 0
      && typeof method.label === 'string' && method.label.length > 0
      && typeof method.description === 'string'
      && ['COMMON', 'OTHER'].includes(method.group)
      && ['LOCAL_ONLY', 'RESERVED'].includes(method.availability))
    && Array.isArray(item.supportedExtensions) && item.supportedExtensions.length > 0 && item.supportedExtensions.length <= 30
    && item.supportedExtensions.every(extension => typeof extension === 'string' && /^[a-z0-9]+$/.test(extension))
    && typeof item.uploadEnabled === 'boolean'
    && !!limits
    && positiveInteger(limits.maxFileBytes)
    && positiveInteger(limits.maxPages)
    && positiveInteger(limits.retentionMinutes)
}

export function parseMiniappCatalogEnvelope(value: unknown): MiniappCatalog | null {
  const envelope = value as { code?: number; data?: unknown }
  return envelope?.code === 0 && isCatalog(envelope.data) ? envelope.data : null
}

export function loadMiniappCatalog(): Promise<CatalogLoadResult> {
  const url = catalogUrl()
  if (!url) return Promise.resolve({ catalog: localCatalog, source: 'local', serviceAvailable: false })
  return new Promise(resolve => {
    uni.request({
      url,
      method: 'GET',
      timeout: 2500,
      success: response => {
        const parsed = parseMiniappCatalogEnvelope(response.data)
        if (response.statusCode === 200 && parsed) {
          resolve({ catalog: parsed, source: 'server', serviceAvailable: true })
          return
        }
        resolve({ catalog: localCatalog, source: 'local', serviceAvailable: false })
      },
      fail: () => resolve({ catalog: localCatalog, source: 'local', serviceAvailable: false })
    })
  })
}
