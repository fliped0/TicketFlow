import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, apiReply, errorText } from '../api/client'
import { useAuthStore } from './auth'
import { accessToken } from './session'
import { readRecord, storageKey } from '../trade/journal'
import { agentUnresolved, readAgentRecord } from '../trade/agent-journal'
import { dismissPurchase, pendingPurchase, purchaseKey, purchaseRevision, readPurchase, savePurchase, validPurchaseResult, type PurchaseCommand, type PurchaseRecord } from '../trade/purchase-journal'

const rejectedBeforeAdmission: Record<string, number> = { SYNC_REQUIRED: 409, NOT_FOUND: 404 }
export const usePurchaseStore = defineStore('purchase', () => {
  const auth = useAuthStore(); const record = ref<PurchaseRecord | null>(null); const busy = ref(false); const storageError = ref('')
  const unresolved = computed(() => pendingPurchase(record.value))
  function refresh() {
    record.value = null; storageError.value = ''
    if (!auth.user) return
    try { record.value = readPurchase(auth.user.userId) } catch (error) { storageError.value = errorText(error) }
  }
  watch(() => [auth.user?.userId, purchaseRevision.value], refresh, { immediate: true, flush: 'sync' })
  const changed = (e: StorageEvent) => { if (!e.key || e.key === purchaseKey(auth.user?.userId ?? '')) purchaseRevision.value++ }
  window.addEventListener('storage', changed); onScopeDispose(() => window.removeEventListener('storage', changed))
  async function locked<T>(work: (owner: string) => Promise<T>) {
    if (busy.value) throw new ApiError('TRADE_BUSY', '抢票请求正在处理。')
    if (!auth.user || !accessToken.value) throw new ApiError('UNAUTHENTICATED', '请先登录。')
    if (!navigator.locks) throw new ApiError('BROWSER_UNSUPPORTED', '请使用新版 Chrome 或 Edge 进行抢票。')
    const owner = auth.user.userId; const token = accessToken.value; busy.value = true
    try { return await navigator.locks.request(storageKey(owner), { ifAvailable: true }, async lock => {
      if (!lock) throw new ApiError('TRADE_BUSY', '其他标签页正在处理操作，请稍候。')
      if (auth.user?.userId !== owner || accessToken.value !== token) throw new ApiError('UNAUTHENTICATED', '账号已切换。')
      return await work(owner)
    }) } finally { busy.value = false; refresh() }
  }
  function accept(current: PurchaseRecord, value: unknown, retryAfter = 0) {
    if (!validPurchaseResult(value, current) || (current.result && current.result.requestId !== value.requestId)) throw new ApiError('INVALID_RECEIPT', '抢票回执无法核对，请保留原请求并查询结果。')
    const next: PurchaseRecord = { ...current, result: value, failures: 0, errorCode: value.failureCode ?? '',
      state: value.state === 'SUCCEEDED' || value.state === 'REJECTED' ? value.state : 'PENDING', retryAt: Date.now() + Math.max(2, retryAfter) * 1000 }
    savePurchase(next); return next
  }
  async function run(command?: PurchaseCommand) {
    return locked(async owner => {
      let current = readPurchase(owner)
      if (command) {
        if (pendingPurchase(current) || readRecord(owner)?.state === 'UNKNOWN' || agentUnresolved(readAgentRecord(owner))) throw new ApiError('TRADE_UNRESOLVED', '请先处理原交易或抢票请求。')
        current = { ...command, schemaVersion: 1, owner, origin: location.origin, key: crypto.randomUUID(), createdAt: Date.now(), retryAt: 0, failures: 0, state: 'UNKNOWN', result: null, errorCode: '' }
        savePurchase(current) // Durable original key and parameters before the first POST.
      } else if (!current || !pendingPurchase(current)) throw new ApiError('NO_RECOVERY', '没有待处理的抢票请求。')
      if (Date.now() < current.retryAt) throw new ApiError('RETRY_WAIT', '请等待查询间隔结束。')
      const original = current; const isQuery = !!original.result
      try {
        const reply = await apiReply<unknown>(isQuery ? '/purchase-requests/' + original.result!.requestId : '/purchase-requests', {
          authenticated: true, method: isQuery ? 'GET' : 'POST',
          ...(isQuery ? {} : { idempotencyKey: original.key, body: { tierId: original.tierId, quantity: 1 } }),
        })
        return accept(original, reply.data, reply.retryAfter)
      } catch (failure) {
        const error = failure instanceof ApiError ? failure : new ApiError('UNKNOWN_RESULT', '抢票结果尚未确认。')
        if (!isQuery && error.status === 409 && validPurchaseResult(error.data, original) && error.data.state === 'REJECTED' && error.data.failureCode === error.code) return accept(original, error.data)
        const rejected = !isQuery && rejectedBeforeAdmission[error.code] === error.status
        try { savePurchase({ ...original, state: rejected ? 'REJECTED' : original.state, errorCode: error.code, failures: original.failures + 1,
          retryAt: Date.now() + Math.max(2, error.retryAfter, Math.min(30, 2 ** Math.min(5, original.failures + 1))) * 1000 }) } catch { /* Keep the original durable request. */ }
        throw error
      }
    })
  }
  async function dismiss() { return locked(async owner => dismissPurchase(owner)) }
  return { record, busy, storageError, unresolved, refresh, run, dismiss }
})
