import type { Page } from '@playwright/test'

export const example = { id: '101', name: '城市回声 · 演示', description: '<img src=x onerror="window.injected=true">这是普通文案', category: '音乐', city: '上海', venue: '城市音乐厅', status: 'ON_SALE', version: 0 }
export async function mockApi(page: Page) {
  let failNext = false
  let empty = false
  let delayed = false
  let registered = false
  let username = 'demo_user'
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname.replace('/api/v1', '')
    const ok = (data: unknown) => route.fulfill({ json: { code: 'OK', message: '成功', data, traceId: 'mock', replayed: false } })
    if (path === '/auth/register') {
      if (registered) return route.fulfill({ status: 409, json: { code: 'USERNAME_EXISTS' } })
      registered = true; username = request.postDataJSON().username.toLowerCase()
      return ok({ userId: '1', username, role: 'USER' })
    }
    if (path === '/auth/login') {
      if (request.postDataJSON().password !== 'TestPassword_123') return route.fulfill({ status: 401, json: { code: 'BAD_CREDENTIALS' } })
      return ok({ accessToken: 'mock.token.value', tokenType: 'Bearer', expiresIn: 1800 })
    }
    if (path === '/users/me') {
      if (request.headers().authorization !== 'Bearer mock.token.value') return route.fulfill({ status: 401, json: { code: 'UNAUTHENTICATED' } })
      return ok({ userId: '1', username, role: 'USER' })
    }
    if (path === '/events') {
      if (failNext) { failNext = false; return route.fulfill({ status: 503, json: { code: 'TEMPORARILY_UNAVAILABLE' } }) }
      const all = Array.from({ length: 12 }, (_, i) => ({ ...example, id: String(101 + i), name: i ? `活动 ${i}` : example.name }))
      const city = url.searchParams.get('city'); const keyword = url.searchParams.get('keyword')
      const filtered = empty || (city && city !== '上海') ? [] : all.filter(item => !keyword || item.name.includes(keyword))
      const current = Number(url.searchParams.get('page') ?? 1); const size = Number(url.searchParams.get('size') ?? 9)
      return ok({ items: filtered.slice((current - 1) * size, current * size), total: filtered.length, page: current, size })
    }
    if (path === '/events/101') return ok(example)
    if (path === '/events/101/sessions') return ok({ items: [11, 12].map((id, index) => ({ id: String(id), eventId: '101', startsAt: `2026-11-0${index + 1}T11:00:00Z`, saleStartAt: '2026-10-01T00:00:00Z', saleEndAt: '2026-11-01T00:00:00Z', saleStatus: 'ON_SALE', version: 0 })), page: 1, size: 6, total: 2 })
    if (/^\/sessions\/(11|12)\/tiers$/.test(path)) {
      const first = path.includes('/11/')
      if (delayed && first) await new Promise(resolve => setTimeout(resolve, 350))
      const items = [{ id: first ? '21' : '31', sessionId: first ? '11' : '12', name: first ? '标准票' : '第二场票', priceFen: first ? 9900 : 19900, available: 20, capacity: 20, saleStatus: 'ON_SALE', refundPolicy: 'FULL_BEFORE_START', version: 0 }, { id: '22', sessionId: '11', name: '售罄票', priceFen: 19900, available: 0, capacity: 0, saleStatus: 'SOLD_OUT', refundPolicy: 'FULL_BEFORE_START', version: 0 }]
      return ok({ items, page: 1, size: 6, total: 2 })
    }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } })
  })
  return { fail: () => { failNext = true }, empty: () => { empty = true }, delay: () => { delayed = true } }
}
