import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { agentApi, terminalConfirmation, validConfirmation, type Confirmation } from '../api/agent'
import { ApiError, errorText } from '../api/client'
import { useAuthStore } from './auth'
import { accessToken } from './session'
import { readRecord, storageKey } from '../trade/journal'
import { pendingPurchase, readPurchase } from '../trade/purchase-journal'
import { agentRevision, agentStorageKey, agentUnresolved, clearAgentRecord, readAgentRecord, saveAgentRecord, type AgentRecord } from '../trade/agent-journal'

export const useAgentStore = defineStore('agent', () => {
  const auth = useAuthStore(); const record = ref<AgentRecord | null>(null); const storageError = ref(''); const busy = ref(false)
  const unresolved = computed(() => agentUnresolved(record.value))
  function refresh() {
    record.value = null; storageError.value = ''
    if (!auth.user) return
    try { record.value = readAgentRecord(auth.user.userId) } catch (error) { storageError.value = errorText(error) }
  }
  watch(() => [auth.user?.userId, agentRevision.value], refresh, { immediate: true, flush: 'sync' })
  const storageChanged = (e: StorageEvent) => { if (!e.key || e.key === agentStorageKey(auth.user?.userId ?? '')) refresh() }
  window.addEventListener('storage', storageChanged); onScopeDispose(() => window.removeEventListener('storage', storageChanged))
  async function locked<T>(action: (owner: string, current: AgentRecord | null) => Promise<T>): Promise<T> {
    if (busy.value) throw new ApiError('TRADE_BUSY', '操作正在处理，请稍候。')
    if (!auth.user || !accessToken.value) throw new ApiError('UNAUTHENTICATED', '请先登录。')
    if (!navigator.locks) throw new ApiError('BROWSER_UNSUPPORTED', '请使用支持安全交易协调的新版 Chrome 或 Edge。')
    const owner = auth.user.userId; const token = accessToken.value; busy.value = true
    try {
      return await navigator.locks.request(storageKey(owner), { ifAvailable: true }, async lock => {
        if (!lock) throw new ApiError('TRADE_BUSY', '其他标签页正在处理操作，请稍后查询。')
        if (auth.user?.userId !== owner || accessToken.value !== token) throw new ApiError('UNAUTHENTICATED', '账号已切换，请重新确认。')
        return await action(owner, readAgentRecord(owner))
      })
    } finally { busy.value = false; refresh() }
  }
  function accept(owner: string, value: unknown, previous: AgentRecord | null) {
    if (!validConfirmation(value) || (previous && (value.confirmationId !== previous.card.confirmationId || value.orderId !== previous.card.orderId || value.operation !== previous.card.operation || value.snapshot.amountFen !== previous.card.snapshot.amountFen))) throw new ApiError('INVALID_RECEIPT', '确认回执无法核对，请保留原记录并查询结果。')
    saveAgentRecord({ schemaVersion: 1, owner, origin: location.origin, card: value, attempted: !terminalConfirmation(value.state) && (previous?.attempted === true || ['UNKNOWN', 'EXECUTING'].includes(value.state)) })
    return value
  }
  async function select(card: Confirmation) {
    return locked(async (owner, current) => {
      if (agentUnresolved(current) && current?.card.confirmationId !== card.confirmationId) throw new ApiError('TRADE_UNRESOLVED', '请先处理原助手确认记录。')
      if (!validConfirmation(card)) throw new ApiError('INVALID_RECEIPT', '确认卡无法核对。')
      // Persist the identifier before reading it again, so a refresh retains the recovery entry.
      if (current?.card.confirmationId !== card.confirmationId) accept(owner, card, null)
      return accept(owner, await agentApi<unknown>('/confirmations/' + card.confirmationId), readAgentRecord(owner))
    })
  }
  async function action(kind: 'query' | 'execute' | 'recover' | 'revoke', expectedId?: string) {
    return locked(async (owner, current) => {
      if (!current) throw new ApiError('NO_RECOVERY', '没有可处理的助手确认记录。')
      const id = current.card.confirmationId; const path = '/confirmations/' + id
      if (expectedId && id !== expectedId) throw new ApiError('CONFIRMATION_CHANGED', '确认记录已变化，请重新核对。')
      if (kind === 'execute' || kind === 'recover') {
        if (kind === 'execute' && pendingPurchase(readPurchase(owner))) throw new ApiError('TRADE_UNRESOLVED', '请先处理页面顶部的原抢票请求。')
        if (readRecord(owner)?.state === 'UNKNOWN') throw new ApiError('TRADE_UNRESOLVED', '请先恢复页面顶部的原购票交易。')
        if (kind === 'execute' && (current.card.state !== 'PENDING' || Date.now() >= Date.parse(current.card.expiresAt))) throw new ApiError('CONFIRMATION_EXPIRED', '确认已到期或状态变化，请先查询结果。')
        if (kind === 'recover' && current.card.state !== 'UNKNOWN') throw new ApiError('NO_RECOVERY', '请先查询原记录，仅未知结果可以恢复。')
        saveAgentRecord({ ...current, attempted: true }) // Explicit approval, durable BEFORE sending.
      }
      const result = await agentApi<unknown>(kind === 'query' || kind === 'revoke' ? path : path + '/' + kind,
        kind === 'query' ? 'GET' : kind === 'revoke' ? 'DELETE' : 'POST', kind === 'execute' ? { approved: true } : kind === 'recover' ? {} : undefined)
      return accept(owner, result, readAgentRecord(owner))
    })
  }
  async function dismiss() { return locked(async owner => { clearAgentRecord(owner) }) }
  return { record, storageError, busy, unresolved, refresh, select, action, dismiss }
})
