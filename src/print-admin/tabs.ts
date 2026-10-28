import { printAdminMenu } from './menu'
import { PRINT_HOME } from './navigation'

export interface PrintAdminTab {
  path: string
  fullPath: string
  title: string
  module: string
}

export const printPage = (path: string, fullPath = path, title?: unknown): PrintAdminTab => {
  const entry = printAdminMenu.find((item) => item.route === path)
  return {
    path, fullPath,
    title: entry?.title || (typeof title === 'string' && title ? title : '打印后台'),
    module: entry?.module || (path === '/print/demo-orders' || path.startsWith('/print/demo-orders/') ? '订单' : '工作台')
  }
}

/** One tab per page; keep its most recent query and hash for the next switch. */
export const rememberPrintTab = (tabs: readonly PrintAdminTab[], page: PrintAdminTab): PrintAdminTab[] =>
  tabs.some((tab) => tab.path === page.path)
    ? tabs.map((tab) => tab.path === page.path ? page : tab)
    : [...tabs, page]

// Persist page locations only. Drafts, passwords, query strings and order IDs stay out of storage.
const restorablePaths = new Set(printAdminMenu.flatMap((entry) => entry.route ? [entry.route] : []))
export const serializePrintTabs = (tabs: readonly PrintAdminTab[]): string =>
  JSON.stringify(tabs.filter((tab) => restorablePaths.has(tab.path)).map((tab) => tab.path))

export const restorePrintTabs = (saved: string | null): PrintAdminTab[] => {
  let tabs = [printPage(PRINT_HOME)]
  try {
    const paths: unknown = JSON.parse(saved || '[]')
    if (Array.isArray(paths)) for (const path of paths.slice(0, 20)) {
      if (typeof path === 'string' && restorablePaths.has(path)) tabs = rememberPrintTab(tabs, printPage(path))
    }
  } catch { /* Browser storage is optional; malformed state starts at the workbench. */ }
  return tabs
}

/** Workbench stays available; closing an inactive page never moves the current page. */
export const closePrintTab = (tabs: readonly PrintAdminTab[], path: string, currentPath: string) => {
  const index = tabs.findIndex((tab) => tab.path === path)
  const next = tabs.filter((tab) => tab.path !== path || path === PRINT_HOME)
  const nextFullPath = path !== PRINT_HOME && path === currentPath && index >= 0
    ? (next[Math.max(0, index - 1)]?.fullPath || PRINT_HOME) : null
  return { tabs: next, nextFullPath }
}
