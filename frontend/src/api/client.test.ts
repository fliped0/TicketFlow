import { afterEach, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from './client'
import { accessToken, clearToken, saveToken } from '../stores/session'
import { useAuthStore } from '../stores/auth'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 })
afterEach(() => { clearToken(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
test('public reads do not carry bearer tokens; protected reads do', async () => {
  saveToken('private-token')
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(ok({ items: [] })))
  vi.stubGlobal('fetch', fetcher)
  await api('/events')
  expect(fetcher.mock.calls[0]![1].headers.Authorization).toBeUndefined()
  await api('/users/me', { authenticated: true })
  expect(fetcher.mock.calls[1]![1].headers.Authorization).toBe('Bearer private-token')
})
test('late unauthorized response cannot clear a newer login', async () => {
  saveToken('old-token')
  let finish!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { finish = resolve })))
  const pending = api('/users/me', { authenticated: true }).catch(error => error)
  saveToken('new-token')
  finish(new Response(JSON.stringify({ code: 'UNAUTHENTICATED' }), { status: 401 }))
  await pending
  expect(accessToken.value).toBe('new-token')
})
test('overlapping identity checks remain bound to their own token', async () => {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  let finishOld!: (response: Response) => void
  vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => {
    const token = (options.headers as Record<string, string>).Authorization
    return token === 'Bearer old-token' ? new Promise<Response>(resolve => { finishOld = resolve }) : Promise.resolve(ok({ userId: '2', username: 'new_user', role: 'USER' }))
  }))
  saveToken('old-token'); const old = auth.restore()
  saveToken('new-token'); await auth.restore()
  expect(auth.user?.userId).toBe('2')
  finishOld(ok({ userId: '1', username: 'old_user', role: 'USER' })); await old
  expect(auth.user?.userId).toBe('2')
  auth.logout(); expect(auth.user).toBeNull(); expect(sessionStorage.getItem('ticketflow.auth.v1')).toBeNull()
})
test('malformed and failed responses produce controlled messages without upstream body leakage', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>private upstream diagnostic</html>', { status: 502 })))
  await expect(api('/events')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', message: '服务返回异常，请稍后重试。' })
})
