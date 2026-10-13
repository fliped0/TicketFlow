import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { useAgentStore } from './agent'
import { useTradeStore } from './trade'
import { clearToken, saveToken } from './session'
import { agentStorageKey, readAgentRecord, saveAgentRecord } from '../trade/agent-journal'
import { storageKey, writeRecord } from '../trade/journal'
import type { Confirmation } from '../api/agent'

const id = '11111111-1111-4111-8111-111111111111'
const card = { confirmationId: id, sessionId: id, orderId: '123', operation: 'cancel', state: 'PENDING', expiresAt: '2099-01-01T00:00:00Z', amountFen: 0, simulation: true, result: null,
  snapshot: { orderId: '123', status: 'PENDING', amountFen: 9900, snapshot: { eventName: '音乐会', tierName: '标准票', startsAt: '2099-01-02T00:00:00Z' } } } as Confirmation
const result = { ...card, state: 'SUCCEEDED', result: { code: 'OK', data: { orderId: '123', operationStatus: 'CANCELLED', currentOrderStatus: 'CANCELLED', amountFen: 9900, paymentId: null, refundId: null, expiresAt: '2099-01-01T00:00:00Z' } } } as Confirmation
const response = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 })
let agent: ReturnType<typeof useAgentStore>; let trade: ReturnType<typeof useTradeStore>
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); saveToken('owner-token'); useAuthStore().user = { userId: '1', username: 'owner', role: 'USER' }
  vi.stubGlobal('navigator', { locks: { request: (_name: string, _options: unknown, callback: (lock: object | null) => unknown) => Promise.resolve(callback({})) } })
  agent = useAgentStore(); trade = useTradeStore()
  saveAgentRecord({ schemaVersion: 1, owner: '1', origin: location.origin, attempted: false, card })
})
afterEach(() => { agent.$dispose(); trade.$dispose(); clearToken(); localStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
test('approved intent is durable before HTTP; response loss blocks direct trades and querying keeps its identifier', async () => {
  const fetcher = vi.fn((_url: string, options: RequestInit) => {
    expect(readAgentRecord('1')?.attempted).toBe(true)
    expect(JSON.parse(options.body as string)).toEqual({ approved: true })
    return Promise.reject(new TypeError('lost'))
  }); vi.stubGlobal('fetch', fetcher)
  await expect(agent.action('execute')).rejects.toMatchObject({ code: 'AGENT_NETWORK_ERROR' })
  expect(agent.unresolved).toBe(true); expect(trade.blocked).toBe(true)
  await expect(trade.execute({ operation: 'CANCEL', targetId: '123', preview: { eventName: '音乐会', tierName: '标准票', startsAt: card.snapshot.snapshot.startsAt, amountFen: 9900 } })).rejects.toMatchObject({ code: 'TRADE_UNRESOLVED' })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(result)))
  await agent.action('query')
  expect(readAgentRecord('1')?.card.confirmationId).toBe(id); expect(agent.unresolved).toBe(false); expect(trade.blocked).toBe(false)
})
test('UNKNOWN recovers the same confirmation; PENDING never silently becomes execution during recovery', async () => {
  const fetcher = vi.fn().mockResolvedValue(response(result)); vi.stubGlobal('fetch', fetcher)
  await expect(agent.action('recover')).rejects.toMatchObject({ code: 'NO_RECOVERY' })
  expect(fetcher).not.toHaveBeenCalled()
  saveAgentRecord({ schemaVersion: 1, owner: '1', origin: location.origin, attempted: true, card: { ...card, state: 'UNKNOWN' } })
  await agent.action('recover')
  expect(fetcher.mock.calls[0]![0]).toBe('/agent/v1/confirmations/' + id + '/recover')
  expect(fetcher.mock.calls[0]![1].body).toBe('{}')
})
test('quota errors and unresolved direct trades prevent assistant execution', async () => {
  const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  await expect(agent.action('execute')).rejects.toMatchObject({ code: 'AGENT_STORAGE_ERROR' })
  expect(fetcher).not.toHaveBeenCalled(); vi.restoreAllMocks()
  writeRecord({ schemaVersion: 1, owner: '1', origin: location.origin, key: crypto.randomUUID(), operation: 'CREATE', targetId: '21', preview: { eventName: '音乐会', tierName: '标准票', startsAt: card.snapshot.snapshot.startsAt, amountFen: 9900 }, createdAt: Date.now(), retryAt: 0, state: 'UNKNOWN', result: null, errorCode: '' })
  await expect(agent.action('execute')).rejects.toMatchObject({ code: 'TRADE_UNRESOLVED' })
  expect(fetcher).not.toHaveBeenCalled(); expect(localStorage.getItem(storageKey('1'))).toBeTruthy()
})
test('foreign or mismatched receipts never clear the original unknown attempt', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...result, confirmationId: '22222222-2222-4222-8222-222222222222' })))
  await expect(agent.action('execute')).rejects.toMatchObject({ code: 'INVALID_RECEIPT' })
  expect(agent.unresolved).toBe(true); expect(readAgentRecord('1')?.card.confirmationId).toBe(id)
  saveToken('other-token'); useAuthStore().user = { userId: '2', username: 'other', role: 'USER' }
  expect(agent.record).toBeNull(); await expect(agent.action('recover')).rejects.toMatchObject({ code: 'NO_RECOVERY' })
  expect(localStorage.getItem(agentStorageKey('1'))).toBeTruthy()
})
test('malformed assistant storage and another-tab locks fail closed', async () => {
  const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
  await expect(agent.action('execute', '22222222-2222-4222-8222-222222222222')).rejects.toMatchObject({ code: 'CONFIRMATION_CHANGED' })
  expect(fetcher).not.toHaveBeenCalled()
  localStorage.setItem(agentStorageKey('1'), '{broken'); agent.refresh()
  expect(agent.storageError).toBeTruthy()
  await expect(agent.action('execute')).rejects.toMatchObject({ code: 'AGENT_STORAGE_ERROR' })
  vi.stubGlobal('navigator', { locks: { request: (_name: string, _options: unknown, callback: (lock: null) => unknown) => Promise.resolve(callback(null)) } })
  await expect(agent.action('execute')).rejects.toMatchObject({ code: 'TRADE_BUSY' })
  expect(fetcher).not.toHaveBeenCalled()
})
