import request from '@/config/axios'
import { isAxiosError } from 'axios'
import { parseCategory, parseCategoryPage } from './categoryModel'
import type { CategoryCreate, CategoryUpdate } from './categoryModel'
export class CategoryApiError extends Error {
  constructor(readonly kind: 'invalid' | 'conflict' | 'forbidden' | 'missing' | 'pending' | 'service') { super(kind) }
}
const decode = (raw: unknown): unknown => {
  let v: { code?: number }
  try { v = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { throw new CategoryApiError('service') }
  if (!v || typeof v.code !== 'number') throw new CategoryApiError('service')
  if (v.code !== 0 && v.code !== 200 && v.code !== 401) throw new CategoryApiError(
    v.code === 1510080001 ? 'invalid' : v.code === 1510080002 ? 'conflict' : v.code === 1510080003 ? 'missing' :
    v.code === 1510080005 ? 'pending' : v.code === 403 || (v.code >= 1510000001 && v.code <= 1510000007) ? 'forbidden' : 'service')
  return v
}
const url = '/print/library/categories'
export const getCategories = async (page = 1, pageSize = 20) => parseCategoryPage(await request.get<unknown>({ url, params: { page, pageSize }, transformResponse: [decode] }))
export const getCategory = async (id: string) => parseCategory(await request.get<unknown>({ url: `${url}/${encodeURIComponent(id)}`, transformResponse: [decode] }))
export const createCategory = async (data: CategoryCreate) => parseCategory(await request.post<unknown>({ url, data, transformResponse: [decode] }))
export const updateCategory = async (id: string, data: CategoryUpdate) => parseCategory(await request.put<unknown>({ url: `${url}/${encodeURIComponent(id)}`, data, transformResponse: [decode] }))
export const categoryFailure = (error: unknown) => {
  if (error instanceof CategoryApiError) return error.kind
  if (isAxiosError(error) && (error.response?.status === 403 || error.response?.status === 401)) return 'forbidden'
  if (isAxiosError(error) && error.response?.status === 409) return 'conflict'
  return 'service'
}
