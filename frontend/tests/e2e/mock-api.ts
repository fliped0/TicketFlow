import type { Page } from '@playwright/test'
import type { Order, TradeResult } from '../../src/api/types'

export const example = { id: '101', name: '城市回声 · 演示', description: '<img src=x onerror="window.injected=true">这是普通文案', category: '音乐', city: '上海', venue: '城市音乐厅', status: 'ON_SALE', version: 0 }
export async function mockApi(page: Page) {
  let failNext = false
  let empty = false
  let delayed = false
  let registered = false
  let username = 'demo_user'
  const orders: Order[] = []
  const attempts: { operation: string; key: string; body: unknown }[] = []
  const replies = new Map<string, { data?: TradeResult; code?: string; status: number }>()
  let loseCreate = false; let losePay = false; let creationError = ''; let paymentError = ''
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname.replace('/api/v1', '')
    const ok = (data: unknown, status = 200, replayed = false) => route.fulfill({ status, headers: { Date: new Date().toUTCString() }, json: { code: 'OK', message: '成功', data, traceId: 'mock', replayed } })
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
    if (path.startsWith('/orders')) {
      if (request.headers().authorization !== 'Bearer mock.token.value') return route.fulfill({ status: 401, json: { code: 'UNAUTHENTICATED' } })
      if (request.method() === 'GET') {
        if (path === '/orders') {
          const filtered = orders.filter(item => !url.searchParams.get('status') || item.status === url.searchParams.get('status'))
          return ok({ items: filtered, page: 1, size: 10, total: filtered.length })
        }
        const order = orders.find(item => path === '/orders/' + item.orderId)
        return order ? ok(order) : route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } })
      }
      const operation = path === '/orders' ? 'CREATE' : 'PAY'
      const key = request.headers()['idempotency-key'] ?? ''
      attempts.push({ operation, key, body: request.postDataJSON() })
      const old = replies.get(operation + key)
      if (old) return old.data ? ok({ ...old.data, currentOrderStatus: orders.find(item => item.orderId === old.data!.orderId)!.status }, old.status, true) : route.fulfill({ status: old.status, json: { code: old.code, replayed: true } })
      const code = operation === 'CREATE' ? creationError : paymentError
      if (code) {
        const status = code === 'PAYMENT_SIMULATED_FAILURE' ? 422 : 409
        replies.set(operation + key, { status, code })
        return route.fulfill({ status, json: { code, replayed: false } })
      }
      if (operation === 'CREATE') {
        if (orders.some(item => ['PENDING', 'PAID'].includes(item.status))) return route.fulfill({ status: 409, json: { code: 'PURCHASE_LIMIT', replayed: false } })
        const tierId = request.postDataJSON().tierId
        const amount = tierId === '31' ? 19900 : 9900
        const order: Order = { orderId: '500', status: 'PENDING', quantity: 1, amountFen: amount, unitPriceFen: amount,
          createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 900_000).toISOString(), payment: null, refund: null,
          snapshot: { schemaVersion: 1, eventId: '101', eventName: example.name, city: '上海', venue: example.venue, sessionId: tierId === '31' ? '12' : '11', startsAt: '2026-11-01T11:00:00Z', tierId, tierName: tierId === '31' ? '第二场票' : '标准票', quantity: 1, amountFen: amount, unitPriceFen: amount, refundPolicy: 'FULL_BEFORE_START' } }
        orders.push(order)
        const data: TradeResult = { orderId: '500', operationStatus: 'PENDING', currentOrderStatus: 'PENDING', amountFen: amount, expiresAt: order.expiresAt, paymentId: null, refundId: null }
        replies.set(operation + key, { status: 201, data })
        if (loseCreate) { loseCreate = false; return route.abort('connectionreset') }
        return ok(data, 201)
      }
      const order = orders.find(item => path === '/orders/' + item.orderId + '/payments')
      if (!order) return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } })
      order.status = 'PAID'; order.payment = { paymentId: '700', amountFen: order.amountFen, paidAt: new Date().toISOString() }
      const data: TradeResult = { orderId: order.orderId, operationStatus: 'PAID', currentOrderStatus: 'PAID', amountFen: order.amountFen, expiresAt: order.expiresAt, paymentId: '700', refundId: null }
      replies.set(operation + key, { status: 200, data })
      if (losePay) { losePay = false; return route.abort('connectionreset') }
      return ok(data)
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
  return { fail: () => { failNext = true }, empty: () => { empty = true }, delay: () => { delayed = true },
    loseCreate: () => { loseCreate = true }, losePay: () => { losePay = true },
    rejectCreate: (code: string) => { creationError = code }, rejectPay: (code: string) => { paymentError = code },
    expire: () => { for (const order of orders) order.expiresAt = new Date(Date.now() - 1000).toISOString() }, attempts, orders }
}
