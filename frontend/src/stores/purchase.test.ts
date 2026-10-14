import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePurchaseStore } from './purchase'
import { useTradeStore } from './trade'
import { useAuthStore } from './auth'
import { clearToken, saveToken } from './session'
import { purchaseKey, readPurchase, savePurchase, type PurchaseCommand } from '../trade/purchase-journal'
const command: PurchaseCommand = { eventId: '1', sessionId: '2', tierId: '3', preview: { eventName: '音乐会', tierName: '标准票', startsAt: '2099-01-01T00:00:00Z', amountFen: 9900 } }
const accepted = { requestId: '11111111-1111-4111-8111-111111111111', state: 'ACCEPTED', acceptedAt: '2026-10-14T00:00:00Z', createDeadline: '2026-10-14T00:00:30Z', orderId: null, currentOrderStatus: null, snapshot: null, failureCode: null }
const ok = (data: unknown, retry = '1') => new Response(JSON.stringify({ code: 'OK', data, replayed: false }), { headers: { 'Retry-After': retry } })
let purchase: ReturnType<typeof usePurchaseStore>; let trade: ReturnType<typeof useTradeStore>
beforeEach(() => {
  vi.useFakeTimers(); localStorage.clear(); setActivePinia(createPinia()); saveToken('token')
  useAuthStore().user = { userId: '1', username: 'owner', role: 'USER' }
  vi.stubGlobal('navigator', { locks: { request: (_key: string, _options: unknown, callback: (lock: object | null) => unknown) => Promise.resolve(callback({})) } })
  purchase = usePurchaseStore(); trade = useTradeStore()
})
afterEach(() => { purchase.$dispose(); trade.$dispose(); clearToken(); localStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })
test('durable intent survives lost admission and replays the same key and tier; accepted requests only GET', async () => {
  const fetcher = vi.fn((_url: string, options: RequestInit): Promise<Response> => {
    expect(readPurchase('1')?.state).toBe('UNKNOWN'); expect(JSON.parse(options.body as string)).toEqual({ tierId: '3', quantity: 1 })
    return Promise.reject(new TypeError('response lost'))
  }); vi.stubGlobal('fetch', fetcher)
  await expect(purchase.run(command)).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  const key = readPurchase('1')!.key; expect(trade.blocked).toBe(true)
  await expect(purchase.run({ ...command, tierId: '4' })).rejects.toMatchObject({ code: 'TRADE_UNRESOLVED' })
  vi.advanceTimersByTime(2001); fetcher.mockImplementation(() => Promise.resolve(ok(accepted)))
  await purchase.run(); expect((fetcher.mock.calls[1]![1].headers as Record<string, string>)['Idempotency-Key']).toBe(key)
  vi.advanceTimersByTime(2001); await purchase.run()
  expect(fetcher.mock.calls[2]![0]).toBe('/api/v1/purchase-requests/' + accepted.requestId)
  expect(fetcher.mock.calls[2]![1].method).toBe('GET'); expect(fetcher.mock.calls[2]![1].body).toBeUndefined()
})
test('server rejection ends admission but a GET 404 or mismatched identity never discards an accepted request', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok(accepted)); vi.stubGlobal('fetch', fetcher)
  await purchase.run(command); vi.advanceTimersByTime(2001)
  fetcher.mockResolvedValue(new Response(JSON.stringify({ code: 'NOT_FOUND' }), { status: 404 }))
  await expect(purchase.run()).rejects.toMatchObject({ code: 'NOT_FOUND' }); expect(readPurchase('1')!.state).toBe('PENDING')
  vi.advanceTimersByTime(2001); fetcher.mockResolvedValue(ok({ ...accepted, requestId: '22222222-2222-4222-8222-222222222222' }))
  await expect(purchase.run()).rejects.toMatchObject({ code: 'INVALID_RECEIPT' }); expect(readPurchase('1')!.result!.requestId).toBe(accepted.requestId)
  localStorage.clear(); purchase.refresh()
  fetcher.mockResolvedValue(new Response(JSON.stringify({ code: 'SOLD_OUT', data: { ...accepted, state: 'REJECTED', failureCode: 'SOLD_OUT', acceptedAt: null, createDeadline: null } }), { status: 409 }))
  await purchase.run(command); expect(readPurchase('1')!.state).toBe('REJECTED'); expect(trade.blocked).toBe(false)
})
test('Retry-After survives reload and changing account hides but preserves pending results', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok(accepted, '60')); vi.stubGlobal('fetch', fetcher)
  await purchase.run(command)
  await expect(purchase.run()).rejects.toMatchObject({ code: 'RETRY_WAIT' }); expect(fetcher).toHaveBeenCalledTimes(1)
  saveToken('other'); useAuthStore().user = { userId: '2', username: 'other', role: 'USER' }
  expect(purchase.record).toBeNull(); await expect(purchase.run()).rejects.toMatchObject({ code: 'NO_RECOVERY' })
  expect(readPurchase('1')!.result!.requestId).toBe(accepted.requestId)
})
test('storage quota, corrupted storage and a held cross-tab lock prevent any admission POST', async () => {
  const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  await expect(purchase.run(command)).rejects.toMatchObject({ code: 'PURCHASE_STORAGE_ERROR' }); vi.restoreAllMocks()
  localStorage.setItem(purchaseKey('1'), '{broken'); await expect(purchase.run(command)).rejects.toMatchObject({ code: 'PURCHASE_STORAGE_ERROR' })
  localStorage.clear(); vi.stubGlobal('navigator', { locks: { request: (_key: string, _options: unknown, callback: (lock: null) => unknown) => Promise.resolve(callback(null)) } })
  await expect(purchase.run(command)).rejects.toMatchObject({ code: 'TRADE_BUSY' }); expect(fetcher).not.toHaveBeenCalled()
})
test('expired display deadline does not end an accepted request, and mismatched success snapshot cannot unlock trading', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok(accepted)); vi.stubGlobal('fetch', fetcher)
  await purchase.run(command); vi.advanceTimersByTime(120000)
  const original = readPurchase('1')!; expect(original.state).toBe('PENDING')
  const success = { ...accepted, state: 'SUCCEEDED', orderId: '77', currentOrderStatus: 'PENDING', snapshot: { schemaVersion: 1, ...command, quantity: 1, unitPriceFen: 9900, ...command.preview, tierId: '999' } }
  fetcher.mockResolvedValue(ok(success)); await expect(purchase.run()).rejects.toMatchObject({ code: 'INVALID_RECEIPT' })
  expect(trade.blocked).toBe(true); await expect(purchase.dismiss()).rejects.toMatchObject({ code: 'PURCHASE_STORAGE_ERROR' })
  savePurchase({ ...original, retryAt: 0 }); success.snapshot.tierId = '3'; fetcher.mockResolvedValue(ok(success))
  await purchase.run(); expect(trade.blocked).toBe(false); await purchase.dismiss(); expect(readPurchase('1')).toBeNull()
})
