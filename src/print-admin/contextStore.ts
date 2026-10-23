import { defineStore } from 'pinia'
import { classifyContextError, contextFailure, getPrintContext } from './context'
import type { ContextFailure, PrintContext } from './context'

interface PrintContextState {
  context: PrintContext | null
  status: 'idle' | 'loading' | 'ready' | 'error'
  failure: ContextFailure | null
  requestVersion: number
}

export const usePrintContextStore = defineStore('print-context', {
  state: (): PrintContextState => ({ context: null, status: 'idle', failure: null, requestVersion: 0 }),
  actions: {
    async load(expectedUserId: number) {
      const version = ++this.requestVersion
      this.context = null
      this.failure = null
      this.status = 'loading'
      try {
        const context = await getPrintContext()
        if (version !== this.requestVersion) return
        if (!Number.isSafeInteger(expectedUserId) || context.userId !== expectedUserId) {
          this.failure = contextFailure('identity')
          this.status = 'error'
          return
        }
        this.context = context
        this.status = 'ready'
      } catch (error) {
        if (version !== this.requestVersion) return
        this.failure = classifyContextError(error)
        this.status = 'error'
      }
    },
    clear() {
      this.requestVersion++
      this.context = null
      this.status = 'idle'
      this.failure = null
    }
  },
  persist: false
})
