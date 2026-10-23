export const PRINT_HOME = '/print/workbench'

/** Keep explicit same-site paths, query and hash; never turn login into an external redirect. */
export const resolvePrintLoginRedirect = (value: unknown): string => {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') ||
      value.includes('\\') || /[\u0000-\u001f\u007f]/.test(value)) {
    return PRINT_HOME
  }
  const path = value.split(/[?#]/, 1)[0]
  if (path === '/' || path === '/index') return PRINT_HOME + value.slice(path.length)
  if (path === '/login') return PRINT_HOME
  return value
}
