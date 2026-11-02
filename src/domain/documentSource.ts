export type DocumentSource = 'local-document' | 'wechat-chat' | 'invoice-file'

export function resolveDocumentSource(value: unknown): DocumentSource {
  return value === 'wechat-chat' || value === 'invoice-file' ? value : 'local-document'
}

export function documentUploadUrl(value: unknown): string {
  return `/pages/document-upload/index?source=${resolveDocumentSource(value)}`
}

export const documentSourceCopy: Record<DocumentSource, { title: string; button: string }> = {
  'local-document': { title: '上传本地文件', button: '选择 PDF 文件打印' },
  'wechat-chat': { title: '微信聊天文档', button: '选择聊天中的 PDF 文件' },
  'invoice-file': { title: '发票文件打印', button: '选择聊天中的 PDF 发票' }
}

export interface SelectedDocument { name?: string; size?: number; path?: string }

export function validateSelectedPdf(file: SelectedDocument, maxFileBytes: number): string {
  if (!file.name?.toLowerCase().endsWith('.pdf')) return '当前仅支持 PDF；其它文档格式待接入。'
  if (!Number.isFinite(file.size) || Number(file.size) <= 0) return '无法读取文件大小，请重新选择 PDF。'
  if (Number(file.size) > maxFileBytes) return '文件超过 2MB 演示限制。'
  if (!file.path) return '当前设备未返回可上传的临时文件路径。'
  return ''
}

export function isFileSelectionCancelled(error: unknown): boolean {
  const message = (error as { errMsg?: unknown } | null)?.errMsg
  return typeof message === 'string' && /cancel/i.test(message)
}
