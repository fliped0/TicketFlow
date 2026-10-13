import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { apiReply, ApiError } from '../api/client'
import type { TradeResult } from '../api/types'
import { accessToken } from './session'
import { useAuthStore } from './auth'
import { definiteRejections, dismissRecord, readRecord, storageKey, validReceipt, writeRecord, type TradeCommand, type TradeRecord } from '../trade/journal'
import { agentRevision, agentStorageKey, agentUnresolved, readAgentRecord } from '../trade/agent-journal'

export const useTradeStore = defineStore('trade', () => {
  const auth = useAuthStore()
  const record = ref<TradeRecord | null>(null); const storageError = ref(''); const busy = ref(false)
  const unresolved = computed(() => record.value?.state === 'UNKNOWN')
  const agentBlocked = computed(() => {
    void agentRevision.value
    if (!auth.user) return false
    try { return agentUnresolved(readAgentRecord(auth.user.userId)) } catch { return true }
  })
  const blocked = computed(() => busy.value || unresolved.value || !!storageError.value || agentBlocked.value)
  function refresh() {
    record.value = null; storageError.value = ''
    if (!auth.authenticated || !auth.user) return
    try { record.value = readRecord(auth.user.userId) }
    catch (error) { storageError.value = (error as Error).message }
  }
  watch(() => auth.user?.userId, refresh, { immediate: true, flush: 'sync' })
  const storageChanged = (event: StorageEvent) => {
    if (!event.key || event.key === agentStorageKey(auth.user?.userId ?? '')) agentRevision.value++
    if (!event.key || event.key === storageKey(auth.user?.userId ?? '')) refresh()
  }
  window.addEventListener('storage', storageChanged)
  onScopeDispose(() => window.removeEventListener('storage', storageChanged))

  async function execute(command?: TradeCommand): Promise<TradeResult> {
    if (busy.value) throw new ApiError('TRADE_BUSY', '操作正在处理，请稍候。')
    if (!auth.authenticated || !auth.user) throw new ApiError('UNAUTHENTICATED', '请先登录。')
    if (!navigator.locks) throw new ApiError('BROWSER_UNSUPPORTED', '当前浏览器不支持安全交易协调，请使用新版 Chrome 或 Edge。')
    const owner = auth.user.userId; const token = accessToken.value
    busy.value = true
    try {
      return await navigator.locks.request(storageKey(owner), { ifAvailable: true }, async lock => {
        if (!lock) throw new ApiError('TRADE_BUSY', '其他标签页正在处理你的操作，请稍后查询结果。')
        if (auth.user?.userId !== owner || accessToken.value !== token) throw new ApiError('UNAUTHENTICATED', '账号已切换，请重新确认。')
        const previous = readRecord(owner)
        let current: TradeRecord
        if (command) {
          if (agentUnresolved(readAgentRecord(owner))) throw new ApiError('TRADE_UNRESOLVED', '请先处理智能助手中已确认的原操作。')
          if (previous?.state === 'UNKNOWN') throw new ApiError('TRADE_UNRESOLVED', '已有结果待确认，请先恢复原操作。')
          current = { ...command, schemaVersion: 1, owner, origin: location.origin, key: crypto.randomUUID(), createdAt: Date.now(), retryAt: 0, state: 'UNKNOWN', result: null, errorCode: '' }
          writeRecord(current) // Explicit approval has happened; persist BEFORE HTTP.
        } else {
          if (!previous || previous.state !== 'UNKNOWN') throw new ApiError('NO_RECOVERY', '没有需要恢复的操作。')
          current = previous
          if (Date.now() < current.retryAt) throw new ApiError('RETRY_WAIT', '请等待限流窗口结束后再恢复。')
        }
        refresh()
        const actions = { PAY: 'payments', CANCEL: 'cancel', REFUND: 'refunds' } as const
        const path = current.operation === 'CREATE' ? '/orders' : `/orders/${current.targetId}/${actions[current.operation]}`
        try {
          const reply = await apiReply<unknown>(path, { method: 'POST', authenticated: true, idempotencyKey: current.key,
            body: current.operation === 'CREATE' ? { tierId: current.targetId, quantity: 1 } : {} })
          if (!validReceipt(reply.data, current)) throw new ApiError('INVALID_RECEIPT', '交易回执无法核对，请保留原操作并恢复结果。')
          writeRecord({ ...current, state: 'SUCCEEDED', result: reply.data, errorCode: '', retryAt: 0 })
          return reply.data
        } catch (error) {
          const failed = error instanceof ApiError ? error : new ApiError('UNKNOWN_RESULT', '结果尚未确认，请恢复原操作。')
          const rejected = definiteRejections[failed.code] === failed.status && failed.status > 0
          // 401/429/5xx, conflicts and malformed success remain UNKNOWN. Never replace the key.
          try { writeRecord({ ...current, state: rejected ? 'REJECTED' : 'UNKNOWN', result: null, errorCode: failed.code,
            retryAt: failed.status === 429 ? Date.now() + Math.ceil(Math.max(1, failed.retryAfter)) * 1000 : 0 }) } catch { /* Original durable record is retained. */ }
          throw failed
        } finally { refresh() }
      })
    } finally { busy.value = false; refresh() }
  }
  async function dismiss() {
    if (busy.value || !auth.user || !record.value) return
    const owner = auth.user.userId; const key = record.value.key
    if (!navigator.locks) return
    await navigator.locks.request(storageKey(owner), { ifAvailable: true }, async lock => {
      if (!lock) throw new ApiError('TRADE_BUSY', '其他标签页正在处理操作。')
      if (auth.user?.userId !== owner) return
      dismissRecord(owner, key); refresh()
    })
  }
  return { record, storageError, busy, unresolved, blocked, refresh, execute, dismiss }
})
