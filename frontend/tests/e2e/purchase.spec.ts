import { expect, test, type Page } from '@playwright/test'
import { example, mockApi } from './mock-api'
import type { Order } from '../../src/api/types'
async function login(page: Page) {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill('demo_user'); await page.getByLabel('密码', { exact: true }).fill('TestPassword_123')
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page.locator('.account-name')).toBeVisible()
}
async function setup(page: Page) {
  const java = await mockApi(page); const requests: { method: string; key?: string; body: unknown }[] = []
  let lost = false; let finished = false; let queryError = 0; let reject = false
  const id = '11111111-1111-4111-8111-111111111111'
  const result = { requestId: id, state: 'ACCEPTED', acceptedAt: new Date().toISOString(), createDeadline: new Date(Date.now() + 30000).toISOString(), orderId: null as string | null, failureCode: null as string | null, currentOrderStatus: null as string | null, snapshot: null as Order['snapshot'] | null }
  const order: Order = { orderId: '500', status: 'PENDING', quantity: 1, amountFen: 9900, unitPriceFen: 9900, createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 900000).toISOString(), payment: null, refund: null,
    snapshot: { schemaVersion: 1, eventId: '101', eventName: example.name, city: '上海', venue: example.venue, sessionId: '11', startsAt: '2026-11-01T11:00:00Z', tierId: '21', tierName: '标准票', quantity: 1, amountFen: 9900, unitPriceFen: 9900, refundPolicy: 'FULL_BEFORE_START' } }
  await page.route('**/api/v1/events/101/sessions?*', route => route.fulfill({ json: { code: 'OK', data: { items: [{ id: '11', eventId: '101', purchaseMode: 'ASYNC', startsAt: order.snapshot.startsAt, saleStartAt: '2026-10-01T00:00:00Z', saleEndAt: order.snapshot.startsAt, saleStatus: 'ON_SALE', version: 1 }], total: 1, page: 1, size: 6 } } }))
  await page.route('**/api/v1/purchase-requests**', async route => {
    const r = route.request(); requests.push({ method: r.method(), key: r.headers()['idempotency-key'], body: r.postData() ? r.postDataJSON() : null })
    if (r.method() === 'POST' && lost) { lost = false; return route.abort('connectionreset') }
    if (r.method() === 'GET' && queryError) return route.fulfill({ status: queryError, headers: { 'Retry-After': '3' }, json: { code: queryError === 404 ? 'NOT_FOUND' : 'TOO_MANY_REQUESTS' } })
    if (reject) return route.fulfill({ status: 409, json: { code: 'SOLD_OUT', replayed: false, data: { ...result, state: 'REJECTED', acceptedAt: null, createDeadline: null, failureCode: 'SOLD_OUT' } } })
    if (finished) { if (!java.orders.length) java.orders.push(order); Object.assign(result, { state: 'SUCCEEDED', orderId: order.orderId, snapshot: order.snapshot, currentOrderStatus: 'PENDING' }) }
    return route.fulfill({ status: finished || r.method() === 'GET' ? 200 : 202, headers: { 'Retry-After': '2' }, json: { code: 'OK', data: result, replayed: requests.filter(x => x.method === 'POST').length > 1 } })
  })
  await login(page)
  return { requests, lose: () => { lost = true }, finish: () => { finished = true }, error: (status: number) => { queryError = status }, reject: () => { reject = true } }
}
async function submit(page: Page) {
  await page.goto('/events/101'); await page.getByRole('button', { name: /标准票/ }).click(); await page.getByRole('button', { name: '确认选票并抢票' }).click()
  const dialog = page.getByRole('dialog', { name: '确认抢票' }); await expect(dialog).toContainText('受理不代表出票成功')
  await expect(dialog.getByRole('button', { name: '确认提交抢票' })).toBeDisabled(); await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '确认提交抢票' }).evaluate(el => { (el as HTMLButtonElement).click(); (el as HTMLButtonElement).click() })
  return dialog
}
test('async admission is not an order; refresh keeps polling GET and success enters the payable order', async ({ page }) => {
  const api = await setup(page); await submit(page)
  const panel = page.getByRole('region', { name: '抢票结果与恢复' })
  await expect(panel).toContainText('已受理，正在排队处理'); await expect(page.getByRole('button', { name: '确认选票并抢票' })).toBeDisabled()
  await page.reload(); await expect(panel).toContainText('已受理'); api.finish()
  await expect(page).toHaveURL(/orders\/500$/); await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toBeVisible()
  expect(api.requests.filter(r => r.method === 'POST')).toHaveLength(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
test('lost admission remains explicit after refresh and original replay keeps key and tier', async ({ page }) => {
  const api = await setup(page); api.lose(); const dialog = await submit(page)
  await expect(dialog.getByRole('alert')).toBeVisible(); await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.reload(); await expect(page.getByRole('region', { name: '抢票结果与恢复' })).toContainText('抢票受理结果待确认')
  expect(api.requests.filter(r => r.method === 'POST')).toHaveLength(1)
  api.finish(); await page.getByRole('button', { name: '恢复原抢票请求' }).click()
  await expect(page.getByRole('region', { name: '抢票结果与恢复' }).getByRole('heading', { level: 2 })).toHaveText('抢票成功，订单已生成')
  const posts = api.requests.filter(r => r.method === 'POST'); expect(posts).toHaveLength(2); expect(posts[0]!.key).toBe(posts[1]!.key); expect(posts[1]!.body).toEqual({ tierId: '21', quantity: 1 })
  await page.getByRole('link', { name: '查看订单并处理' }).click(); await expect(page).toHaveURL(/orders\/500$/)
})
test('query failures and relogin retain an accepted request without new admission', async ({ page }) => {
  const api = await setup(page); api.error(404); await submit(page)
  const panel = page.getByRole('region', { name: '抢票结果与恢复' }); await expect(panel.getByRole('alert')).toBeVisible({ timeout: 8000 })
  await expect(panel).toContainText('已受理'); await page.getByRole('button', { name: '退出登录' }).click(); await expect(panel).not.toBeVisible()
  await login(page); await page.goto('/events/101'); await expect(panel).toContainText('已受理')
  api.error(0); api.finish(); await expect(page).toHaveURL(/orders\/500$/, { timeout: 12000 })
  expect(api.requests.filter(r => r.method === 'POST')).toHaveLength(1)
})
test('definite sold-out admission rejection permits a newly reviewed attempt', async ({ page }) => {
  const api = await setup(page); api.reject(); await submit(page)
  await expect(page.getByRole('region', { name: '抢票结果与恢复' })).toContainText('票档已售罄，本次没有生成订单')
  await expect(page.getByRole('button', { name: '确认选票并抢票' })).toBeEnabled()
  expect(api.requests.filter(r => r.method === 'POST')).toHaveLength(1)
})
