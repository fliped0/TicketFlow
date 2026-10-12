import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'
import { useTradeStore } from '../stores/trade'
import { clearToken, saveToken } from '../stores/session'
import { dismissRecord, readRecord, storageKey, validReceipt, writeRecord, type TradeCommand } from './journal'
const command: TradeCommand = { operation: 'CREATE', targetId: '21', preview: { eventName: '音乐会', tierName: '标准票', startsAt: '2026-11-01T11:00:00Z', amountFen: 9900 } }
const receipt = { orderId: '123', operationStatus: 'PENDING', currentOrderStatus: 'PENDING', amountFen: 9900, expiresAt: '2026-11-01T10:00:00Z', paymentId: null, refundId: null }
const response = (data: unknown = receipt) => new Response(JSON.stringify({ code: 'OK', data, replayed: false }), { status: 201 })
let trade: ReturnType<typeof useTradeStore>
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); saveToken('owner-token')
  useAuthStore().user = { userId: '1', username: 'owner', role: 'USER' }
  vi.stubGlobal('navigator', { locks: { request: (_name: string, _options: unknown, callback: (lock: object | null) => unknown) => Promise.resolve(callback({})) } })
  trade = useTradeStore()
})
afterEach(() => { trade.$dispose(); clearToken(); localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
test('durable approved intent and key exist before the first POST', async () => {
  const fetcher = vi.fn((_path: string, options: RequestInit) => {
    const stored = readRecord('1')!
    expect(stored.state).toBe('UNKNOWN')
    expect((options.headers as Record<string, string>)['Idempotency-Key']).toBe(stored.key)
    expect(JSON.parse(options.body as string)).toEqual({ tierId: '21', quantity: 1 })
    return Promise.resolve(response())
  })
  vi.stubGlobal('fetch', fetcher)
  await trade.execute(command)
  expect(readRecord('1')?.state).toBe('SUCCEEDED')
  expect(fetcher).toHaveBeenCalledTimes(1)
})
test('unknown network result survives a new store and replays the original immutable request', async () => {
  const fetcher = vi.fn().mockRejectedValueOnce(new TypeError('response lost')).mockImplementation(() => Promise.resolve(response()))
  vi.stubGlobal('fetch', fetcher)
  await expect(trade.execute(command)).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  const original = readRecord('1')!
  await expect(trade.execute({ ...command, targetId: '31' })).rejects.toMatchObject({ code: 'TRADE_UNRESOLVED' })
  trade.$dispose(); setActivePinia(createPinia())
  // Re-create identity as a browser restart would, keeping the same origin and persisted journal.
  const auth = useAuthStore()
  auth.user = { userId: '1', username: 'owner', role: 'USER' }
  trade = useTradeStore()
  await trade.execute()
  expect((fetcher.mock.calls[1]![1].headers)['Idempotency-Key']).toBe(original.key)
  expect(fetcher.mock.calls[1]![1].body).toBe(fetcher.mock.calls[0]![1].body)
})
test('duplicate clicks cannot issue a second POST while the first is in progress', async () => {
  let finish!: (response: Response) => void
  const fetcher = vi.fn(() => new Promise<Response>(resolve => { finish = resolve }))
  vi.stubGlobal('fetch', fetcher)
  const first = trade.execute(command)
  await expect(trade.execute(command)).rejects.toMatchObject({ code: 'TRADE_BUSY' })
  finish(response()); await first
  expect(fetcher).toHaveBeenCalledTimes(1)
})
test('quota or malformed persistence blocks HTTP before any effect', async () => {
  const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
  localStorage.setItem(storageKey('1'), '{broken')
  await expect(trade.execute(command)).rejects.toMatchObject({ code: 'TRADE_STORAGE_ERROR' })
  expect(localStorage.getItem(storageKey('1'))).toBe('{broken')
  localStorage.clear()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  await expect(trade.execute(command)).rejects.toMatchObject({ code: 'TRADE_STORAGE_ERROR' })
  expect(fetcher).not.toHaveBeenCalled()
})
test('storage failure after a success receipt keeps the original unknown journal recoverable', async () => {
  const setter = Storage.prototype.setItem
  vi.stubGlobal('fetch', vi.fn(() => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('disk failure') })
    return Promise.resolve(response())
  }))
  await expect(trade.execute(command)).rejects.toMatchObject({ code: 'TRADE_STORAGE_ERROR' })
  expect(readRecord('1')?.state).toBe('UNKNOWN')
  expect(readRecord('1')?.key).toBeTruthy()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(setter)
})
test('401, 5xx and key conflicts keep UNKNOWN while exact business rejections close the attempt', async () => {
  for (const [status, code, state] of [[401, 'UNAUTHENTICATED', 'UNKNOWN'], [503, 'TEMPORARILY_UNAVAILABLE', 'UNKNOWN'], [409, 'IDEMPOTENCY_CONFLICT', 'UNKNOWN'], [409, 'SOLD_OUT', 'REJECTED'], [409, 'ASYNC_REQUIRED', 'REJECTED'], [422, 'PAYMENT_SIMULATED_FAILURE', 'REJECTED']] as const) {
    localStorage.clear(); saveToken('owner-token'); useAuthStore().user = { userId: '1', username: 'owner', role: 'USER' }
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({ code }), { status }))))
    await expect(trade.execute(command)).rejects.toMatchObject({ code })
    expect(readRecord('1')?.state).toBe(state)
  }
})
test('rate limiting reserves the same key and honors Retry-After without an automatic retry', async () => {
  const fetcher = vi.fn(() => Promise.resolve(new Response(JSON.stringify({ code: 'TOO_MANY_REQUESTS' }), { status: 429, headers: { 'Retry-After': '5' } })))
  vi.stubGlobal('fetch', fetcher)
  await expect(trade.execute(command)).rejects.toMatchObject({ status: 429 })
  expect(readRecord('1')?.retryAt).toBeGreaterThan(Date.now())
  await expect(trade.execute()).rejects.toMatchObject({ code: 'RETRY_WAIT' })
  expect(fetcher).toHaveBeenCalledTimes(1)
})
test('wrong order, amount, payment identity or operation never count as a valid receipt', () => {
  const payment: TradeCommand = { ...command, operation: 'PAY', targetId: '123' }
  const paid = { ...receipt, operationStatus: 'PAID', currentOrderStatus: 'PAID', paymentId: '456' }
  expect(validReceipt(paid, payment)).toBe(true)
  for (const bad of [{ ...paid, orderId: '124' }, { ...paid, amountFen: 9999 }, { ...paid, paymentId: null }, { ...paid, operationStatus: 'PENDING' }, { ...paid, expiresAt: null }]) expect(validReceipt(bad, payment)).toBe(false)
})
test('logout/account switch hides old unresolved records and prevents their recovery', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('lost'))))
  await expect(trade.execute(command)).rejects.toBeTruthy()
  const first = readRecord('1')!
  saveToken('other-token'); useAuthStore().user = { userId: '2', username: 'other', role: 'USER' }
  expect(trade.record).toBeNull()
  await expect(trade.execute()).rejects.toMatchObject({ code: 'NO_RECOVERY' })
  expect(readRecord('1')?.key).toBe(first.key)
})
test('unknown records cannot be dismissed and missing browser locks fail closed', async () => {
  writeRecord({ ...command, schemaVersion: 1, owner: '1', origin: location.origin, key: crypto.randomUUID(), createdAt: Date.now(), retryAt: 0, state: 'UNKNOWN', result: null, errorCode: '' })
  expect(() => dismissRecord('1', readRecord('1')!.key)).toThrow()
  vi.stubGlobal('navigator', {})
  await expect(trade.execute()).rejects.toMatchObject({ code: 'BROWSER_UNSUPPORTED' })
})
test('a lock held by another tab rejects before persisting a new key or sending HTTP', async () => {
  const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
  vi.stubGlobal('navigator', { locks: { request: (_name: string, _options: unknown, callback: (lock: null) => unknown) => Promise.resolve(callback(null)) } })
  await expect(trade.execute(command)).rejects.toMatchObject({ code: 'TRADE_BUSY' })
  expect(readRecord('1')).toBeNull(); expect(fetcher).not.toHaveBeenCalled()
})
