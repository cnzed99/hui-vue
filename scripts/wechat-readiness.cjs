const { isIP } = require('node:net')

const keys = [
  'VITE_APP_API_BASE', 'VITE_WECHAT_APP_ID',
  'WECHAT_REQUEST_DOMAINS', 'WECHAT_UPLOAD_DOMAINS', 'WECHAT_DOWNLOAD_DOMAINS',
  'WECHAT_ICP_HTTPS_CONFIRMED', 'WECHAT_DOMAIN_CONSOLE_CONFIRMED',
  'WECHAT_PRIVACY_CONFIRMED', 'WECHAT_CATEGORY_QUALIFICATIONS_CONFIRMED',
  'WECHAT_SUBSCRIBE_CONFIRMED', 'WECHAT_PAYMENT_CONFIRMED',
  'WECHAT_LOGIN_CONFIRMED', 'WECHAT_DEVICE_VALIDATION_CONFIRMED'
]

function validHost(host) {
  if (!host || host.length > 253 || isIP(host) || !/^[a-z0-9.-]+$/.test(host)) return false
  const labels = host.split('.')
  if (labels.length < 2 || !/^[a-z]{2,63}$/.test(labels.at(-1))) return false
  if (labels.some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) return false
  if (labels.some(label => ['localhost', 'local', 'internal', 'lan', 'home', 'test', 'invalid', 'example'].includes(label))) return false
  return !/^(?:.*\.)?(?:example\.(?:com|org|net)|iyint\.com)$/.test(host)
}

// Validate original text as well as URL: WHATWG normalization can hide /../,
// percent escapes, empty queries/fragments, default ports, or encoded IPs.
function httpsUrl(value, apiBase = false) {
  if (typeof value !== 'string' || value !== value.trim() || !/^[\x21-\x7e]+$/.test(value)) return null
  if (!/^https:\/\/[a-zA-Z0-9.-]+(?::443)?(?:\/app-api\/?|\/?)$/.test(value)) return null
  if (/[?#%@\\]/.test(value)) return null
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || !validHost(url.hostname) || url.username || url.password || url.port) return null
    if (apiBase ? !['/app-api', '/app-api/'].includes(url.pathname) : url.pathname !== '/') return null
    return url
  } catch { return null }
}

function domainList(value) {
  if (!value) return { valid: false, origins: [] }
  const entries = value.split(',').map(item => item.trim())
  const parsed = entries.map(item => httpsUrl(item))
  if (!entries.length || parsed.some(item => !item)) return { valid: false, origins: [] }
  return { valid: true, origins: [...new Set(parsed.map(item => item.origin))] }
}

function readConfig(env) {
  return Object.fromEntries(keys.map(key => [key, env[key] || '']))
}

function evaluate(env) {
  const input = readConfig(env)
  const appId = input.VITE_WECHAT_APP_ID
  const validAppId = /^wx[0-9a-f]{16}$/.test(appId)
    && !/^wx([0-9a-f])\1{15}$/.test(appId)
    && !['wx1234567890abcdef', 'wx0123456789abcdef', 'wx1234567890123456'].includes(appId)
  const base = httpsUrl(input.VITE_APP_API_BASE, true)
  const checks = []
  const add = (id, ok, value, missingReason, declared = false) => checks.push({
    id, status: ok ? (declared ? 'DECLARED_NOT_VERIFIED' : 'AVAILABLE_FORMAT_ONLY') : 'MISSING_OR_INVALID',
    value: ok ? value : null, reason: ok ? null : missingReason
  })
  add('appId', validAppId, appId, 'Supply a non-placeholder wx + 16 lowercase hexadecimal AppID; ownership is not verified.')
  add('apiBase', !!base, base?.href.replace(/\/$/, ''), 'Absolute HTTPS FQDN with exact /app-api path required; local/HTTP/IP/credentials/query/fragment/other paths are rejected.')
  for (const [id, key] of [
    ['requestDomains', 'WECHAT_REQUEST_DOMAINS'],
    ['uploadFileDomains', 'WECHAT_UPLOAD_DOMAINS'],
    ['downloadFileDomains', 'WECHAT_DOWNLOAD_DOMAINS']
  ]) {
    const domains = domainList(input[key])
    const coversApi = id === 'downloadFileDomains' || (base && domains.origins.includes(base.origin))
    add(id, domains.valid && !!coversApi, domains.origins,
      'Supply comma-separated legal HTTPS origins; request/uploadFile must include API origin. Console registration is not verified.')
  }
  const configStatus = checks.every(check => check.status !== 'MISSING_OR_INVALID') ? 'READY_FOR_COMPILE' : 'NOT_READY'
  for (const [id, key] of [
    ['filedHttpsDomain', 'WECHAT_ICP_HTTPS_CONFIRMED'],
    ['legalDomainConsole', 'WECHAT_DOMAIN_CONSOLE_CONFIRMED'],
    ['privacyAgreement', 'WECHAT_PRIVACY_CONFIRMED'],
    ['categoryQualifications', 'WECHAT_CATEGORY_QUALIFICATIONS_CONFIRMED'],
    ['subscriptionMessages', 'WECHAT_SUBSCRIBE_CONFIRMED'],
    ['paymentConfiguration', 'WECHAT_PAYMENT_CONFIRMED'],
    ['wechatLogin', 'WECHAT_LOGIN_CONFIRMED'],
    ['deviceValidation', 'WECHAT_DEVICE_VALIDATION_CONFIRMED']
  ]) {
    add(id, input[key] === 'true', true, 'External prerequisite has not been supplied/confirmed.', true)
  }
  return {
    schemaVersion: 1, generatedAt: new Date().toISOString(),
    status: 'NOT_READY', configStatus,
    evidenceLevel: 'LOCAL_CONFIGURATION_ONLY', deviceValidation: 'NOT_RUN',
    releaseReason: 'Syntax and self-declarations cannot verify AppID ownership, filing/TLS, WeChat console, credentials, device behavior or release acceptance.',
    checks, missing: checks.filter(check => check.status === 'MISSING_OR_INVALID').map(check => check.id),
    secretsIncluded: false
  }
}

function assertIntegration(env, manifest) {
  const report = evaluate(env)
  if (report.configStatus !== 'READY_FOR_COMPILE') {
    throw new Error('NOT_READY: ' + report.missing.filter(id => report.checks.find(check => check.id === id)?.status === 'MISSING_OR_INVALID').join(', '))
  }
  if (manifest && (manifest['mp-weixin']?.appid !== env.VITE_WECHAT_APP_ID || manifest['mp-weixin']?.setting?.urlCheck !== true)) {
    throw new Error('NOT_READY: integration manifest must match configured AppID and enable urlCheck; use npm run build:mp-weixin:integration')
  }
  return report
}

module.exports = { validHost, httpsUrl, domainList, readConfig, evaluate, assertIntegration }
