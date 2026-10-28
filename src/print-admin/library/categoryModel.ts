export interface LibraryCategory { id: string; version: string; name: string; fileCount: null; sort: null }
export interface CategoryPage { list: LibraryCategory[]; total: number; page: number; pageSize: number }
export interface CategoryCreate { name: string; requestId: string }
export interface CategoryUpdate { name: string; expectedVersion: string }
export const categoryNameError = (name: string) => !name.trim() || [...name].length > 100 || name.includes('\0') || /[\uD800-\uDFFF]/u.test(name) ? '分类名称必填，最多100个字符。' : ''
export const parseCategory = (raw: unknown): LibraryCategory => {
  const v = raw as LibraryCategory
  if (!v || typeof v !== 'object' || typeof v.id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v.id) ||
      typeof v.version !== 'string' || !/^[1-9][0-9]{0,18}$/.test(v.version) || BigInt(v.version) > 9223372036854775807n ||
      typeof v.name !== 'string' || categoryNameError(v.name) || v.fileCount !== null || v.sort !== null) throw new Error('invalid_category_response')
  return v
}
export const parseCategoryPage = (raw: unknown): CategoryPage => {
  const v = raw as CategoryPage
  if (!v || !Array.isArray(v.list) || !Number.isSafeInteger(v.total) || v.total < 0 || !Number.isInteger(v.page) || v.page < 1 ||
      !Number.isInteger(v.pageSize) || v.pageSize < 1 || v.pageSize > 100 || v.list.length > v.pageSize) throw new Error('invalid_category_page')
  return { ...v, list: v.list.map(parseCategory) }
}
