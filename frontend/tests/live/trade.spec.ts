import { randomUUID } from 'node:crypto'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { expect, test as base, type Page, type APIRequestContext } from '@playwright/test'
import type { Order, TradeResult } from '../../src/api/types'

const fixture = JSON.parse(process.env.TF_FRONTEND_LIVE_FIXTURE!) as { runId: string; events: { id: string; name: string }[] }
const test = base.extend<{ remember: (token: string) => void }>({
  remember: async ({ request }, use) => {
    const tokens: string[] = []
    await use(token => tokens.push(token))
    for (const token of tokens) {
      const result = await request.get('/api/v1/orders?size=100', { headers: { Authorization: 'Bearer ' + token } })
      expect(result.status()).toBe(200)
      const orders = (await result.json()).data.items as Order[]
      for (const order of orders) {
        if (!['PENDING', 'PAID'].includes(order.status)) continue
        const action = order.status === 'PAID' ? 'refunds' : 'cancel'
        const cleanup = await request.post(`/api/v1/orders/${order.orderId}/${action}`, { data: {}, headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': randomUUID() } })
        expect(cleanup.status()).toBe(200)
      }
    }
  },
})
async function user(page: Page, remember: (token: string) => void) {
  const credentials = { username: 'trade_' + randomUUID().replaceAll('-', '').slice(0, 18), password: 'Test_' + randomUUID() }
  await page.goto('/register')
  await page.getByLabel('账号', { exact: true }).fill(credentials.username)
  await page.getByLabel('密码', { exact: true }).fill(credentials.password)
  await page.getByLabel('确认密码').fill(credentials.password)
  await page.getByRole('button', { name: '注册账号' }).click()
  await expect(page.getByText('注册成功，请使用刚才的账号和密码登录。')).toBeVisible()
  await page.getByLabel('密码', { exact: true }).fill(credentials.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.account-name')).toHaveText(credentials.username)
  const token = (await page.evaluate(() => sessionStorage.getItem('ticketflow.auth.v1')))!
  remember(token)
  return { ...credentials, token }
}
async function confirmCreate(page: Page, eventId = fixture.events[0]!.id) {
  await page.goto('/events/' + eventId)
  await page.getByRole('button', { name: /标准票/ }).click()
  await page.getByRole('button', { name: '确认选票并下单' }).click()
  const dialog = page.getByRole('dialog', { name: '确认下单' })
  await dialog.getByRole('checkbox').check()
  return dialog
}
async function confirmPay(page: Page) {
  await page.getByRole('button', { name: '模拟支付', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '确认模拟支付' })
  await dialog.getByRole('checkbox').check()
  return dialog
}
async function screenshot(page: Page, name: string, project: string) {
  const folder = path.resolve('../docs/assets/04/20261012-frontend-b')
  await mkdir(folder, { recursive: true })
  const modal = await page.getByRole('dialog', { name: '确认下单' }).isVisible()
  if (!modal) {
    await page.evaluate(() => window.scrollTo(0, 0))
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  }
  // Fixed overlays belong to the viewport; a full-document shot duplicates offscreen fixed content.
  await page.screenshot({ path: path.join(folder, name + '-' + (project.endsWith('mobile') ? 'mobile' : 'desktop') + '.png'), fullPage: !modal, animations: 'disabled' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
}
test('real one-ticket purchase, double click, order snapshot, payment and own list', async ({ page, request, remember }, info) => {
  const account = await user(page, remember)
  const dialog = await confirmCreate(page)
  await screenshot(page, 'purchase-confirm', info.project.name)
  await dialog.getByRole('button', { name: '确认提交订单' }).evaluate(el => { (el as HTMLButtonElement).click(); (el as HTMLButtonElement).click() })
  await expect(page).toHaveURL(/orders\/\d+$/)
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('待支付')
  await expect(page.locator('.order-detail-card')).toContainText('¥99.00')
  await screenshot(page, 'order-pending', info.project.name)
  const list = await request.get('/api/v1/orders', { headers: { Authorization: 'Bearer ' + account.token } })
  expect((await list.json()).data.total).toBe(1)
  const payment = await confirmPay(page)
  await payment.getByRole('button', { name: '确认模拟支付' }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已支付')
  await expect(page.getByRole('region', { name: '模拟支付记录' })).toContainText('¥99.00')
  await screenshot(page, 'order-paid', info.project.name)
  await page.getByRole('link', { name: '返回我的订单' }).click()
  await page.getByLabel('订单状态').selectOption('PAID')
  await expect(page.locator('.order-card')).toHaveCount(1)
  await screenshot(page, 'order-list', info.project.name)
})
test('real committed create and payment response loss replay their original key and result identity', async ({ page, request, remember }) => {
  const account = await user(page, remember)
  let firstCreate: { key: string; result: TradeResult } | undefined
  let firstPay: { key: string; result: TradeResult } | undefined
  await page.route('**/api/v1/orders', async route => {
    if (route.request().method() !== 'POST' || firstCreate) return route.continue()
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    firstCreate = { key: route.request().headers()['idempotency-key']!, result: (await response.json()).data }
    await route.abort('connectionreset') // Real upstream has committed, browser receives no receipt.
  })
  const dialog = await confirmCreate(page)
  await dialog.getByRole('button', { name: '确认提交订单' }).click()
  await expect(page.getByRole('region', { name: '交易结果与恢复' })).toContainText('有一笔操作结果待确认')
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.reload()
  const replayCreate = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/orders')
  await page.getByRole('button', { name: '恢复原操作结果' }).click()
  const created = await replayCreate
  expect(created.request().headers()['idempotency-key']).toBe(firstCreate!.key)
  const data = await created.json()
  expect(data.replayed).toBe(true); expect(data.data.orderId).toBe(firstCreate!.result.orderId)
  await expect(page).toHaveURL(new RegExp('orders/' + firstCreate!.result.orderId + '$'))
  await page.route('**/api/v1/orders/*/payments', async route => {
    if (firstPay) return route.continue()
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    firstPay = { key: route.request().headers()['idempotency-key']!, result: (await response.json()).data }
    await route.abort('connectionreset')
  })
  const payment = await confirmPay(page); await payment.getByRole('button', { name: '确认模拟支付' }).click()
  await expect(page.getByRole('region', { name: '交易结果与恢复' })).toContainText('有一笔操作结果待确认')
  await payment.getByRole('button', { name: '关闭', exact: true }).click(); await page.reload()
  const replayPay = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/payments'))
  await page.getByRole('button', { name: '恢复原操作结果' }).click()
  const paid = await replayPay
  expect(paid.request().headers()['idempotency-key']).toBe(firstPay!.key)
  const result = await paid.json()
  expect(result.replayed).toBe(true); expect(result.data.paymentId).toBe(firstPay!.result.paymentId)
  await expect(page.getByRole('region', { name: '模拟支付记录' })).toContainText(firstPay!.result.paymentId!)
  const list = await request.get('/api/v1/orders', { headers: { Authorization: 'Bearer ' + account.token } })
  expect((await list.json()).data.total).toBe(1)
})
test('real same-session limit, other-user order privacy and recovery journal ownership', async ({ page, remember }) => {
  const first = await user(page, remember)
  const dialog = await confirmCreate(page)
  await dialog.getByRole('button', { name: '确认提交订单' }).click()
  await expect(page).toHaveURL(/orders\/\d+$/)
  const orderUrl = new URL(page.url()).pathname
  const repeated = await confirmCreate(page)
  await repeated.getByRole('button', { name: '确认提交订单' }).click()
  await expect(repeated.getByRole('alert')).toContainText('同一场次已有有效订单')
  await repeated.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: '退出登录' }).click()
  await user(page, remember)
  await expect(page.getByRole('region', { name: '交易结果与恢复' })).toHaveCount(0)
  await page.goto(orderUrl)
  await expect(page.locator('.orders-container').getByRole('alert')).toContainText('不存在')
  await page.getByRole('button', { name: '退出登录' }).click()
  await page.getByLabel('账号', { exact: true }).fill(first.username)
  await page.getByLabel('密码', { exact: true }).fill(first.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('待支付')
})
async function shortSale(request: APIRequestContext) {
  const headers = { Authorization: 'Bearer ' + process.env.TF_FRONTEND_LIVE_ADMIN_TOKEN! }
  async function post(url: string, data: unknown) {
    const result = await request.post('/api/v1' + url, { data, headers }); expect(result.status()).toBe(201)
    return (await result.json()).data
  }
  const event = await post('/admin/events', { name: 'Deadline_' + fixture.runId + '_' + randomUUID().slice(0, 8), description: '隔离支付期限测试', category: 'TEST', city: '上海', venue: '测试场地' })
  const now = Date.now()
  const session = await post(`/admin/events/${event.id}/sessions`, { startsAt: new Date(now + 16_000).toISOString(), saleStartAt: new Date(now + 4000).toISOString(), saleEndAt: new Date(now + 15_000).toISOString() })
  await post(`/admin/sessions/${session.id}/tiers`, { name: '标准票', priceFen: 9900, capacity: 2 })
  const publish = await request.put(`/api/v1/admin/events/${event.id}/status`, { headers, data: { status: 'ON_SALE', expectedVersion: event.version } }); expect(publish.status()).toBe(200)
  while (Date.now() < now + 4500) await new Promise(resolve => setTimeout(resolve, 100))
  return event.id as string
}
test('real payment deadline disables the UI and server rejects a late payment', async ({ page, request, remember }) => {
  const account = await user(page, remember)
  const id = await shortSale(request)
  const dialog = await confirmCreate(page, id); await dialog.getByRole('button', { name: '确认提交订单' }).click()
  await expect(page).toHaveURL(/orders\/\d+$/)
  const orderId = new URL(page.url()).pathname.split('/').at(-1)!
  await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toBeDisabled({ timeout: 20_000 })
  await expect(page.locator('.payment-panel')).toContainText('支付期限已到')
  await page.waitForTimeout(1500)
  const response = await request.post(`/api/v1/orders/${orderId}/payments`, { data: {}, headers: { Authorization: 'Bearer ' + account.token, 'Idempotency-Key': randomUUID() } })
  expect(response.status()).toBe(409); expect((await response.json()).code).toBe('ORDER_EXPIRED')
})
test('real last-ticket competition rejects a stale selection without creating a second order', async ({ page, context, request, remember }) => {
  const first = await user(page, remember)
  const headers = { Authorization: 'Bearer ' + process.env.TF_FRONTEND_LIVE_ADMIN_TOKEN! }
  const now = Date.now()
  const created = await request.post('/api/v1/admin/events', { headers, data: { name: 'Scarce_' + fixture.runId + '_' + randomUUID().slice(0, 8), description: '隔离最后一张票测试', category: 'TEST', city: '上海', venue: '测试场地' } })
  expect(created.status()).toBe(201); const event = (await created.json()).data
  const sessionResponse = await request.post(`/api/v1/admin/events/${event.id}/sessions`, { headers, data: { startsAt: new Date(now + 7 * 86400_000).toISOString(), saleStartAt: new Date(now + 4000).toISOString(), saleEndAt: new Date(now + 6 * 86400_000).toISOString() } })
  expect(sessionResponse.status()).toBe(201); const session = (await sessionResponse.json()).data
  const tierResponse = await request.post(`/api/v1/admin/sessions/${session.id}/tiers`, { headers, data: { name: '标准票', priceFen: 9900, capacity: 1 } }); expect(tierResponse.status()).toBe(201)
  const published = await request.put(`/api/v1/admin/events/${event.id}/status`, { headers, data: { status: 'ON_SALE', expectedVersion: event.version } }); expect(published.status()).toBe(200)
  while (Date.now() < now + 4500) await new Promise(resolve => setTimeout(resolve, 100))
  const firstDialog = await confirmCreate(page, event.id)
  const other = await context.newPage()
  try {
    const second = await user(other, remember)
    const secondDialog = await confirmCreate(other, event.id)
    await secondDialog.getByRole('button', { name: '确认提交订单' }).click()
    await expect(other).toHaveURL(/orders\/\d+$/)
    await firstDialog.getByRole('button', { name: '确认提交订单' }).click()
    await expect(firstDialog.getByRole('alert')).toContainText('已售罄')
    const firstOrders = await request.get('/api/v1/orders', { headers: { Authorization: 'Bearer ' + first.token } })
    const secondOrders = await request.get('/api/v1/orders', { headers: { Authorization: 'Bearer ' + second.token } })
    expect((await firstOrders.json()).data.total).toBe(0)
    expect((await secondOrders.json()).data.total).toBe(1)
  } finally { await other.close() }
})
