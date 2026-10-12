import { randomUUID } from 'node:crypto'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { expect, test as base, type Page, type APIRequestContext } from '@playwright/test'
import type { Order, TradeResult } from '../../src/api/types'

const fixture = JSON.parse(process.env.TF_FRONTEND_LIVE_FIXTURE!) as { runId: string; events: { id: string; name: string }[] }
const test = base.extend<{ remember: (token: string) => void }>({
  remember: async ({ request }, use, info) => {
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
        if (cleanup.status() === 409 && action === 'refunds' && info.title === 'real refund deadline disables a previously checked confirmation and server refuses a late refund' && Date.now() >= Date.parse(order.snapshot.startsAt)) {
          expect((await cleanup.json()).code).toBe('REFUND_CLOSED')
          // The deadline scenario intentionally retains its paid historical order; never bypass policy to clean it.
          info.annotations.push({ type: 'retained-paid-order', description: order.orderId + ': REFUND_CLOSED boundary fixture' })
        } else expect(cleanup.status()).toBe(200)
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
  const folder = path.resolve('../docs/assets/04/20261012-frontend-c')
  await mkdir(folder, { recursive: true })
  const modal = name.endsWith('-confirm')
  if (modal) await expect(page.getByRole('dialog')).toBeVisible()
  else await expect(page.getByRole('dialog')).not.toBeVisible()
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
test('real same-session limit, other-user order privacy and recovery journal ownership', async ({ page, request, remember }) => {
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
  const other = await user(page, remember)
  for (const action of ['cancel', 'refunds']) {
    const response = await request.post('/api/v1' + orderUrl + '/' + action, { data: {}, headers: { Authorization: 'Bearer ' + other.token, 'Idempotency-Key': randomUUID() } })
    expect(response.status()).toBe(404); expect((await response.json()).code).toBe('NOT_FOUND')
  }
  await expect(page.getByRole('region', { name: '交易结果与恢复' })).toHaveCount(0)
  await page.goto(orderUrl)
  await expect(page.locator('.orders-container').getByRole('alert')).toContainText('不存在')
  await page.getByRole('button', { name: '退出登录' }).click()
  await page.getByLabel('账号', { exact: true }).fill(first.username)
  await page.getByLabel('密码', { exact: true }).fill(first.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('待支付')
})
async function isolatedSale(request: APIRequestContext, duration = 7 * 86400_000) {
  const headers = { Authorization: 'Bearer ' + process.env.TF_FRONTEND_LIVE_ADMIN_TOKEN! }
  async function post(url: string, data: unknown) {
    const result = await request.post('/api/v1' + url, { data, headers }); expect(result.status()).toBe(201)
    return (await result.json()).data
  }
  const event = await post('/admin/events', { name: 'Lifecycle_' + fixture.runId + '_' + randomUUID().slice(0, 8), description: '隔离订单生命周期测试', category: 'TEST', city: '上海', venue: '测试场地' })
  const now = Date.now()
  const session = await post(`/admin/events/${event.id}/sessions`, { startsAt: new Date(now + duration).toISOString(), saleStartAt: new Date(now + 4000).toISOString(), saleEndAt: new Date(now + duration - 1000).toISOString() })
  const tier = await post(`/admin/sessions/${session.id}/tiers`, { name: '标准票', priceFen: 9900, capacity: 2 })
  const publish = await request.put(`/api/v1/admin/events/${event.id}/status`, { headers, data: { status: 'ON_SALE', expectedVersion: event.version } }); expect(publish.status()).toBe(200)
  while (Date.now() < now + 4500) await new Promise(resolve => setTimeout(resolve, 100))
  return { eventId: event.id as string, sessionId: session.id as string, tierId: tier.id as string, startsAt: session.startsAt as string }
}
test('real payment deadline disables the UI and server rejects a late payment', async ({ page, request, remember }) => {
  const account = await user(page, remember)
  const sale = await isolatedSale(request, 16_000)
  const dialog = await confirmCreate(page, sale.eventId); await dialog.getByRole('button', { name: '确认提交订单' }).click()
  await expect(page).toHaveURL(/orders\/\d+$/)
  const orderId = new URL(page.url()).pathname.split('/').at(-1)!
  await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toBeDisabled({ timeout: 20_000 })
  await expect(page.locator('.payment-panel')).toContainText('支付期限已到')
  await page.waitForTimeout(1500)
  const response = await request.post(`/api/v1/orders/${orderId}/payments`, { data: {}, headers: { Authorization: 'Bearer ' + account.token, 'Idempotency-Key': randomUUID() } })
  expect(response.status()).toBe(409); expect((await response.json()).code).toBe('ORDER_EXPIRED')
  await page.getByRole('button', { name: '取消订单', exact: true }).click()
  const cancel = page.getByRole('dialog', { name: '确认取消订单' })
  await cancel.getByRole('checkbox').check(); await cancel.getByRole('button', { name: '确认取消订单' }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已关闭')
})

async function createOrder(page: Page, eventId: string) {
  const dialog = await confirmCreate(page, eventId)
  await dialog.getByRole('button', { name: '确认提交订单' }).click()
  await expect(page).toHaveURL(/orders\/\d+$/)
  return new URL(page.url()).pathname.split('/').at(-1)!
}
async function paidOrder(page: Page) {
  const dialog = await confirmPay(page)
  await dialog.getByRole('button', { name: '确认模拟支付' }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已支付')
}
async function inventory(request: APIRequestContext, sessionId: string, tierId: string) {
  const response = await request.get(`/api/v1/sessions/${sessionId}/tiers`)
  expect(response.status()).toBe(200)
  return (await response.json()).data.items.find((item: { id: string }) => item.id === tierId).available as number
}
async function replay(request: APIRequestContext, orderId: string, action: string, token: string, key: string) {
  const response = await request.post(`/api/v1/orders/${orderId}/${action}`, { data: {}, headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': key } })
  expect(response.status()).toBe(200)
  return await response.json() as { replayed: boolean; data: TradeResult }
}

test('real cancellation and full refund return stock once, retain identities and release same-session eligibility', async ({ page, request, remember }, info) => {
  const account = await user(page, remember)
  const sale = await isolatedSale(request)
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(2)
  const cancelledId = await createOrder(page, sale.eventId)
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(1)
  await page.getByRole('button', { name: '取消订单', exact: true }).click()
  const cancel = page.getByRole('dialog', { name: '确认取消订单' })
  await expect(cancel.getByRole('button', { name: '确认取消订单' })).toBeDisabled()
  await expect(cancel).toContainText('退款金额 ¥0.00')
  await cancel.getByRole('checkbox').check(); await screenshot(page, 'cancel-confirm', info.project.name)
  const response = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/cancel'))
  await cancel.getByRole('button', { name: '确认取消订单' }).evaluate(el => { (el as HTMLButtonElement).click(); (el as HTMLButtonElement).click() })
  const receipt = await response; const cancelKey = receipt.request().headers()['idempotency-key']!
  expect((await receipt.json()).data.amountFen).toBe(9900)
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已取消')
  await screenshot(page, 'order-cancelled', info.project.name)
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(2)
  expect((await replay(request, cancelledId, 'cancel', account.token, cancelKey)).replayed).toBe(true)
  await replay(request, cancelledId, 'cancel', account.token, randomUUID())
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(2)
  const refundedId = await createOrder(page, sale.eventId); expect(refundedId).not.toBe(cancelledId)
  await paidOrder(page); expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(1)
  await page.getByRole('button', { name: '申请模拟退款' }).click()
  const refund = page.getByRole('dialog', { name: '确认模拟退款' })
  await expect(refund.getByRole('button', { name: '确认模拟退款' })).toBeDisabled()
  await refund.getByRole('checkbox').check(); await screenshot(page, 'refund-confirm', info.project.name)
  const refundResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/refunds'))
  await refund.getByRole('button', { name: '确认模拟退款' }).evaluate(el => { (el as HTMLButtonElement).click(); (el as HTMLButtonElement).click() })
  const refunded = await refundResponse; const refundKey = refunded.request().headers()['idempotency-key']!
  const data = (await refunded.json()).data as TradeResult
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已退款')
  await expect(page.getByRole('region', { name: '模拟退款记录' })).toContainText(data.refundId!)
  await screenshot(page, 'order-refunded', info.project.name)
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(2)
  const same = await replay(request, refundedId, 'refunds', account.token, refundKey)
  expect(same.replayed).toBe(true); expect(same.data).toEqual(data)
  expect((await replay(request, refundedId, 'refunds', account.token, randomUUID())).data).toEqual(data)
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(2)
  await page.getByRole('link', { name: '返回我的订单' }).click(); await page.getByLabel('订单状态').selectOption('REFUNDED')
  await expect(page.locator('.order-card')).toHaveCount(1)
  await createOrder(page, sale.eventId)
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(1)
})

test('real committed cancellation and refund response loss recover the original key without another stock release', async ({ page, request, remember }) => {
  test.setTimeout(45_000)
  await user(page, remember); const sale = await isolatedSale(request, 30_000)
  for (const operation of ['CANCEL', 'REFUND'] as const) {
    const orderId = await createOrder(page, sale.eventId)
    if (operation === 'REFUND') await paidOrder(page)
    const action = operation === 'CANCEL' ? 'cancel' : 'refunds'
    let committed: { key: string; result: TradeResult } | undefined
    await page.route(`**/api/v1/orders/${orderId}/${action}`, async route => {
      if (committed) return route.continue()
      const response = await route.fetch(); expect(response.status()).toBe(200)
      committed = { key: route.request().headers()['idempotency-key']!, result: (await response.json()).data }
      await route.abort('connectionreset')
    })
    await page.getByRole('button', { name: operation === 'CANCEL' ? '取消订单' : '申请模拟退款', exact: true }).click()
    const name = operation === 'CANCEL' ? '确认取消订单' : '确认模拟退款'
    const dialog = page.getByRole('dialog', { name })
    await dialog.getByRole('checkbox').check(); await dialog.getByRole('button', { name }).click()
    await expect(page.getByRole('region', { name: '交易结果与恢复' })).toContainText('有一笔操作结果待确认')
    expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(2)
    await dialog.getByRole('button', { name: '关闭', exact: true }).click(); await page.reload()
    if (operation === 'REFUND') {
      while (Date.now() < Date.parse(sale.startsAt) + 1500) await new Promise(resolve => setTimeout(resolve, 100))
      // A committed refund stays recoverable after its first-execution deadline.
    }
    const response = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/' + action))
    await page.getByRole('button', { name: '恢复原操作结果' }).click()
    const recovered = await response
    expect(recovered.request().headers()['idempotency-key']).toBe(committed!.key)
    const result = await recovered.json(); expect(result.replayed).toBe(true); expect(result.data).toEqual(committed!.result)
    await expect(page.locator('.order-detail-card .order-status')).toHaveText(operation === 'CANCEL' ? '已取消' : '已退款')
    expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(2)
    if (operation === 'REFUND') await expect(page.getByRole('region', { name: '模拟退款记录' })).toContainText(committed!.result.refundId!)
  }
})

test('real refund deadline disables a previously checked confirmation and server refuses a late refund', async ({ page, request, remember }) => {
  const account = await user(page, remember); const sale = await isolatedSale(request, 20_000)
  const orderId = await createOrder(page, sale.eventId); await paidOrder(page)
  await page.getByRole('button', { name: '申请模拟退款' }).click()
  const dialog = page.getByRole('dialog', { name: '确认模拟退款' }); await dialog.getByRole('checkbox').check()
  await expect(dialog.getByRole('button', { name: '确认模拟退款' })).toBeDisabled({ timeout: 25_000 })
  await dialog.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page.getByRole('button', { name: '申请模拟退款' })).toBeDisabled()
  await expect(page.locator('.payment-panel')).toContainText('场次已开场')
  while (Date.now() < Date.parse(sale.startsAt) + 1500) await new Promise(resolve => setTimeout(resolve, 100))
  const response = await request.post(`/api/v1/orders/${orderId}/refunds`, { data: {}, headers: { Authorization: 'Bearer ' + account.token, 'Idempotency-Key': randomUUID() } })
  expect(response.status()).toBe(409); expect((await response.json()).code).toBe('REFUND_CLOSED')
  await page.getByRole('button', { name: '刷新订单状态' }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已支付')
  await expect(page.getByRole('region', { name: '模拟退款记录' })).toHaveCount(0)
  expect(await inventory(request, sale.sessionId, sale.tierId)).toBe(1)
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
