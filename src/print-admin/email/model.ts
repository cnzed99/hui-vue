export interface EmailSettings {
  version: string
  configured: boolean
  username: string
  smtpHost: string
  smtpPort: number
  passwordConfigured: boolean
  sendingStatus: 'SIMULATED_NOT_SENT'
  tlsStatus: 'UNCONFIRMED_NOT_CONFIGURED'
}
export interface EmailSettingsCommand {
  expectedVersion: string
  username: string
  password: string
  smtpHost: string
  smtpPort: number
}
export const validateEmailDraft = (draft: { username: string; password: string; smtpHost: string; smtpPort: string }, passwordRequired: boolean) => {
  if (!draft.username || draft.username !== draft.username.trim() || draft.username.length > 320) return '请输入有效的用户名。'
  if (!/^[A-Za-z0-9][A-Za-z0-9.:-]{0,254}$/.test(draft.smtpHost)) return '请输入有效的SMTP地址。'
  if (!/^[1-9][0-9]{0,4}$/.test(draft.smtpPort) || Number(draft.smtpPort) > 65535) return '端口必须是1至65535之间的整数。'
  if (passwordRequired && !draft.password) return '首次保存必须填写密码。'
  if (new TextEncoder().encode(draft.password).length > 1024) return '密码长度超出限制。'
  return ''
}
export const parseEmailSettings = (raw: unknown): EmailSettings => {
  const d = raw as EmailSettings
  if (!d || typeof d !== 'object' || typeof d.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(d.version) ||
      Object.prototype.hasOwnProperty.call(d, 'password') || Object.prototype.hasOwnProperty.call(d, 'passwordCiphertext') ||
      Object.prototype.hasOwnProperty.call(d, 'passwordNonce') ||
      BigInt(d.version) > 9223372036854775807n || typeof d.configured !== 'boolean' || typeof d.username !== 'string' ||
      typeof d.smtpHost !== 'string' || !Number.isInteger(d.smtpPort) || typeof d.passwordConfigured !== 'boolean' ||
      d.sendingStatus !== 'SIMULATED_NOT_SENT' || d.tlsStatus !== 'UNCONFIRMED_NOT_CONFIGURED' ||
      (d.configured ? d.version === '0' || !d.username || !/^[A-Za-z0-9][A-Za-z0-9.:-]{0,254}$/.test(d.smtpHost) ||
        d.smtpPort < 1 || d.smtpPort > 65535 || !d.passwordConfigured
        : d.version !== '0' || d.username !== '' || d.smtpHost !== '' || d.smtpPort !== 0 || d.passwordConfigured)) {
    throw new Error('invalid_email_settings_response')
  }
  return d
}
