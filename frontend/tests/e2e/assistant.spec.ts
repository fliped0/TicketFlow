import { expect, test, type Page } from '@playwright/test'
import { mockApi, example } from './mock-api'
import type { Order } from '../../src/api/types'
import type { Confirmation } from '../../src/api/agent'

const id = '11111111-1111-4111-8111-111111111111'
async function setup(page: Page) {
  const java = await mockApi(page)
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill('demo_user'); await page.getByLabel('密码', { exact: true }).fill('TestPassword_123')
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page.locator('.account-name')).toBeVisible()
  await page.goto('/events/101'); await page.getByRole('button', { name: /标准票/ }).click(); await page.getByRole('button', { name: '确认选票并下单' }).click()
  const dialog = page.getByRole('dialog', { name: '确认下单' }); await dialog.getByRole('checkbox').check(); await dialog.getByRole('button', { name: '确认提交订单' }).click()
  await expect(page).toHaveURL(/orders\/500$/)
  return java
}
async function assistant(page: Page, order: Order) {
  let lose = false; let unknown = false; let expiredSession = false; let calls = 0
  const requests: { path: string; body: unknown }[] = []
  const card: Confirmation = { confirmationId: id, sessionId: id, orderId: order.orderId, operation: 'cancel', state: 'PENDING', expiresAt: new Date(Date.now() + 300_000).toISOString(), amountFen: 0, simulation: true, result: null, snapshot: structuredClone(order) }
  await page.route('**/agent/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/agent/v1', '')
    const body = route.request().postData() ? route.request().postDataJSON() : null
    requests.push({ path, body })
    const ok = (data: unknown) => route.fulfill({ json: { code: 'OK', data } })
    if (path === '/query') return ok({ tool: 'search_events', data: { items: [{ ...example, name: '<img src=x onerror=alert(1)>活动' }], total: 1, page: 1 }, source: { method: 'GET', path: '/api/v1/events', queriedAt: new Date().toISOString() } })
    if (path === '/chat') {
      if (expiredSession) return route.fulfill({ status: 404, json: { code: 'SESSION_NOT_FOUND' } })
      return ok({ sessionId: id, modelMode: 'demo', message: body.message === '确认' ? '聊天不会执行交易。' : '操作尚未执行。', cards: [], confirmations: body.message === '确认' ? [] : [card] })
    }
    if (path.startsWith('/sessions/')) { expiredSession = false; return ok({ deleted: true }) }
    if (path.endsWith('/execute') || path.endsWith('/recover')) {
      calls++
      if (unknown && path.endsWith('/execute')) { card.state = 'UNKNOWN'; return ok(card) }
      order.status = 'CANCELLED'; card.state = 'SUCCEEDED'; card.result = { code: 'OK', data: { orderId: order.orderId, operationStatus: 'CANCELLED', currentOrderStatus: 'CANCELLED', amountFen: order.amountFen, paymentId: null, refundId: null, expiresAt: order.expiresAt } }
      if (lose) { lose = false; return route.abort('connectionreset') }
      return ok(card)
    }
    if (route.request().method() === 'DELETE') { card.state = 'CANCELLED'; return ok(card) }
    return ok(card)
  })
  await page.goto('/assistant')
  return { requests, card, calls: () => calls, lose: () => { lose = true }, unknown: () => { unknown = true }, expireSession: () => { expiredSession = true } }
}
async function prepare(page: Page) {
  await page.getByLabel('你的问题').fill('取消 500'); await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('complementary', { name: '助手独立确认' })).toContainText('等待独立确认')
}
async function approve(page: Page) {
  await page.getByRole('button', { name: '核对并确认操作' }).click()
  const dialog = page.getByRole('dialog', { name: '确认取消订单' }); await expect(dialog.getByRole('button', { name: '确认取消订单' })).toBeDisabled()
  await dialog.getByRole('checkbox').check(); await dialog.getByRole('button', { name: '确认取消订单' }).click()
  return dialog
}
test('assistant login return, direct safe-text cards and chat never execute a transaction', async ({ page }) => {
  await mockApi(page); await page.goto('/assistant'); await expect(page).toHaveURL(/login\?redirect=/)
  // A fresh mock isolates authentication from the purchase fixture used by this scenario.
  await page.unrouteAll(); const java = await setup(page); const api = await assistant(page, java.orders[0]!)
  await page.getByRole('button', { name: '查询活动', exact: true }).click()
  await expect(page.locator('.agent-data-card')).toContainText('<img src=x onerror=alert(1)>活动')
  await expect(page.locator('.agent-data-card img')).toHaveCount(0)
  await prepare(page); await page.getByLabel('你的问题').fill('确认'); await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.locator('.agent-history')).toContainText('聊天不会执行交易')
  expect(api.calls()).toBe(0)
  await page.getByRole('button', { name: '撤销此确认' }).click()
  await expect(page.locator('.agent-state')).toHaveText('确认已撤销')
  expect(java.orders[0]!.status).toBe('PENDING')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
test('lost assistant execution receipt survives refresh, blocks direct trading and resolves by original confirmation GET', async ({ page }) => {
  const java = await setup(page); const api = await assistant(page, java.orders[0]!); await prepare(page); api.lose()
  const dialog = await approve(page)
  await expect(dialog.getByRole('alert')).toContainText('无法取得助手响应')
  expect(api.calls()).toBe(1)
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.goto('/events/101'); await page.getByRole('button', { name: /标准票/ }).click()
  await expect(page.getByRole('button', { name: '确认选票并下单' })).toBeDisabled()
  await page.getByRole('link', { name: '前往智能助手查询和处理' }).click()
  await expect(page).toHaveURL(/\/assistant$/)
  await expect(page.getByRole('button', { name: '查询确认结果' })).toBeVisible()
  await page.reload(); await page.getByRole('button', { name: '查询确认结果' }).click()
  await expect(page.locator('.agent-state')).toHaveText('操作已完成')
  expect(api.calls()).toBe(1)
  expect(api.requests.filter(r => r.path.endsWith('/execute'))[0]!.body).toEqual({ approved: true })
  await page.getByRole('link', { name: '查看订单当前状态' }).click()
  await expect(page.locator('.order-detail-card .order-status')).toHaveText('已取消')
})
test('server UNKNOWN uses the original recover endpoint while expired sessions require an explicit new session', async ({ page }) => {
  const java = await setup(page); const api = await assistant(page, java.orders[0]!); await prepare(page); api.unknown()
  await approve(page); await expect(page.locator('.agent-state')).toContainText('结果未知')
  await page.reload(); await page.getByRole('button', { name: '恢复原确认操作' }).click()
  await expect(page.locator('.agent-state')).toHaveText('操作已完成')
  expect(api.requests.filter(r => r.path.endsWith('/recover'))).toEqual([{ path: '/confirmations/' + id + '/recover', body: {} }])
  await page.getByLabel('你的问题').fill('查活动'); await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByLabel('你的问题')).toHaveValue('')
  api.expireSession(); await page.getByLabel('你的问题').fill('查活动'); await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.locator('.agent-composer').getByRole('alert')).toContainText('会话已过期')
  const count = api.requests.filter(r => r.path === '/chat').length
  await page.getByRole('button', { name: '新建会话' }).click()
  await page.getByLabel('你的问题').fill('查活动'); await page.getByRole('button', { name: '发送问题' }).click()
  await expect.poll(() => api.requests.filter(r => r.path === '/chat').length).toBe(count + 1)
  expect((api.requests.filter(r => r.path === '/chat').at(-1)!.body as { sessionId: string | null }).sessionId).toBeNull()
})
