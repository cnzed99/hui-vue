import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { usePrintContextStore } from '../contextStore'
import { failureText, getDirectory } from './api'
export function useDirectory<T>(endpoint: string, permission: string, params: () => Record<string, string | number>) {
  const user = useUserStore(); const context = usePrintContextStore()
  const permitted = computed(() => user.permissions.has('*:*:*') || user.permissions.has(permission))
  const ready = computed(() => context.status === 'ready')
  const rows = shallowRef<T[]>([]); const total = ref(0); const categories = ref<string[]>([])
  const page = ref(1); const pageSize = ref(10); const busy = ref(false); const loaded = ref(false); const message = ref('')
  let generation = 0
  const load = async () => {
    if (busy.value || !permitted.value || !ready.value) return
    const ticket = ++generation; busy.value = true; loaded.value = false; rows.value = []; total.value = 0; message.value = ''
    try {
      const result = await getDirectory<T>(endpoint, { ...params(), page: page.value, pageSize: pageSize.value })
      if (ticket !== generation) return
      rows.value = result.list; total.value = result.total; categories.value = result.categories; loaded.value = true
    } catch (error) { if (ticket === generation) message.value = failureText(error) }
    finally { if (ticket === generation) busy.value = false }
  }
  const search = () => { if (!busy.value) { page.value = 1; void load() } }
  const changePage = () => { void load() }
  watch(() => `${user.getUser.id}/${context.status}/${context.context?.tenantId}/${context.context?.storeId}/${permitted.value}`, () => {
    generation++; rows.value = []; total.value = 0; categories.value = []; loaded.value = false; busy.value = false; message.value = ''; page.value = 1
    if (ready.value && permitted.value) void load()
  }, { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => { generation++ })
  return { permitted, ready, rows, total, categories, page, pageSize, busy, loaded, message, load, search, changePage }
}
