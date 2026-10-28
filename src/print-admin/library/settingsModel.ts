export interface LibrarySettings {
  version: string
  configured: boolean
  name: string
  description: string
  miniProgramPathStatus: 'NOT_CONFIGURED'
  miniProgramPath: null
}
export interface LibrarySettingsCommand { expectedVersion: string; name: string; description: string }
const validText = (text: string, min: number, max: number) => {
  const length = [...text].length
  return text.trim().length > 0 && length >= min && length <= max && !text.includes('\0') && !/[\uD800-\uDFFF]/u.test(text)
}
export const validateSettings = (name: string, description: string): string => {
  if (!validText(name, 3, 20)) return '文库店名必填，长度为3–20个字符。'
  if (!validText(description, 1, 30)) return '描述必填，长度为1–30个字符。'
  return ''
}
export const parseLibrarySettings = (value: unknown): LibrarySettings => {
  const d = value as LibrarySettings
  if (!d || typeof d !== 'object' || typeof d.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(d.version) ||
      BigInt(d.version) > 9223372036854775807n || typeof d.configured !== 'boolean' ||
      typeof d.name !== 'string' || typeof d.description !== 'string' ||
      d.miniProgramPathStatus !== 'NOT_CONFIGURED' || d.miniProgramPath !== null ||
      (d.configured ? d.version === '0' || !!validateSettings(d.name, d.description) : d.version !== '0' || d.name !== '' || d.description !== '')) {
    throw new Error('invalid_library_settings_response')
  }
  return d
}
